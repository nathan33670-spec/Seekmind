# Spec — Stockage (`core:database`)

| | |
|---|---|
| **Exigences** | EF-IDX-01, EF-PRIV-08, EF-PRIV-09, ENF-PERF-05, ENF-STO-01, ENF-REL-02, INV-06 |
| **Tickets** | M0-06 (prototype), M1-02 à M1-07 |
| **Voir aussi** | [ADR-0004](../adr/0004-stockage-room-sqlcipher-fts5.md) · [07 §4](../07-securite-vie-privee.md#4-chiffrement) |

## 1. Pile

- **Room 2.8.5** pour les tables ordinaires (entités, DAO, migrations, observation par `Flow`).
- **SQLCipher 4.20.0** (`net.zetetic:sqlcipher-android`) comme moteur, via
  `net.zetetic.database.sqlcipher.SupportOpenHelperFactory`. La bibliothèque native est chargée
  explicitement (`System.loadLibrary("sqlcipher")`) avant toute ouverture.
- **FTS5** (compilé dans SQLCipher) pour le plein texte. Room ne génère pas de tables FTS5 : la table
  virtuelle et ses déclencheurs sont créés par SQL brut et interrogés par requêtes brutes.
- Un seul fichier : `databases/seekmind.db`. Une seule instance de base pour le processus.

## 2. Ouverture

1. `DbKeyStore.getOrCreateKey(databaseExists)` rend la clé de 32 octets (désenveloppée par le Keystore) ;
   si la base existe sans fichier de clé, il rend l'erreur `KeyMissing`, jamais une clé neuve.
2. La clé est passée à SQLCipher au format clé brute `x'<64 caractères hexadécimaux>'` : pas de
   dérivation, ouverture immédiate. Le tableau d'octets est effacé après usage.
3. Room est construit avec la fabrique SQLCipher, en mode WAL. `fallbackToDestructiveMigration` est interdit.
4. Dans le rappel `onOpen` : `PRAGMA foreign_keys = ON; PRAGMA secure_delete = FAST; PRAGMA temp_store = MEMORY;`.
5. Dans le rappel `onCreate` : exécution de `FtsSchema.CREATE` (§3.2).
6. Échec d'ouverture (clé absente, fichier illisible) → `DatabaseState.Unavailable(reason)` ; l'interface
   propose la réinitialisation (ENF-REL-02). Aucune tentative de réparation.

## 3. Schéma — version 1

Le SQL ci-dessous est normatif. Les entités Room le reproduisent exactement (noms de colonnes en
`snake_case`, dates en millisecondes depuis l'époque, UTC).

Une clé primaire qui n'est pas `INTEGER` porte `NOT NULL` explicitement : sans lui, SQLite y admet
`NULL` (comportement hérité), et Room l'ajoute de toute façon. Deux points que Room ne sait pas
exprimer, et qui sont les seuls écarts admis : la contrainte
`UNIQUE` de `embedding.chunk_id` devient l'index unique `idx_embedding_chunk` ; la contrainte
`CHECK (id = 1)` de `license` n'est pas déclarée, et le dépôt n'écrit que la ligne 1. Le test
`SchemaConformanceTest` exécute le SQL de ce paragraphe et du §3.2, lu dans ce fichier, et compare
colonne par colonne le schéma obtenu à celui que crée l'application.

### 3.1 Tables

```sql
CREATE TABLE capture (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  seen_count    INTEGER NOT NULL DEFAULT 1,
  package_name  TEXT    NOT NULL,
  activity_name TEXT,
  window_title  TEXT,
  url           TEXT,
  text          TEXT    NOT NULL,          -- texte normalisé et masqué, lignes séparées par \n
  text_source   INTEGER NOT NULL,          -- bits : 1 = arbre, 2 = OCR
  simhash       INTEGER NOT NULL,          -- empreinte 64 bits (entier signé)
  char_count    INTEGER NOT NULL
);
CREATE INDEX idx_capture_last_seen ON capture(last_seen_at);
CREATE INDEX idx_capture_pkg_seen  ON capture(package_name, last_seen_at);

CREATE TABLE chunk (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  capture_id   INTEGER NOT NULL REFERENCES capture(id) ON DELETE CASCADE,
  ordinal      INTEGER NOT NULL,
  start_offset INTEGER NOT NULL,           -- position dans capture.text
  end_offset   INTEGER NOT NULL,
  state        INTEGER NOT NULL DEFAULT 0, -- 0 en attente, 1 vectorisé, 2 en échec
  attempts     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_chunk_capture ON chunk(capture_id);
CREATE INDEX idx_chunk_state   ON chunk(state, id);

CREATE TABLE embedding (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,   -- ordre d'insertion : sert au chargement incrémental
  chunk_id   INTEGER NOT NULL UNIQUE REFERENCES chunk(id) ON DELETE CASCADE,
  capture_id INTEGER NOT NULL,
  model_id   INTEGER NOT NULL,
  vec_i8     BLOB    NOT NULL,                    -- 384 octets
  vec_bin    BLOB    NOT NULL                     -- 48 octets
);
CREATE INDEX idx_embedding_capture ON embedding(capture_id);

CREATE TABLE app_rule (
  package_name TEXT PRIMARY KEY NOT NULL,
  mode         INTEGER NOT NULL,           -- 1 exclue, 2 incluse
  updated_at   INTEGER NOT NULL
);

CREATE TABLE secure_probe (
  package_name  TEXT NOT NULL,
  activity_name TEXT NOT NULL,             -- '' si inconnue
  is_secure     INTEGER NOT NULL,
  probed_at     INTEGER NOT NULL,
  PRIMARY KEY (package_name, activity_name)
);

CREATE TABLE credit_ledger (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     INTEGER NOT NULL,
  delta  INTEGER NOT NULL,
  reason TEXT    NOT NULL,                 -- WELCOME, REWARD, COURTESY, DAILY, SEARCH
  ref    TEXT                              -- clé de requête, le cas échéant
);

CREATE TABLE license (
  id           INTEGER PRIMARY KEY CHECK (id = 1),
  license_id   TEXT    NOT NULL,
  plan         TEXT    NOT NULL,
  token        TEXT    NOT NULL,
  activated_at INTEGER NOT NULL
);

CREATE TABLE search_history (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  at           INTEGER NOT NULL,
  query        TEXT    NOT NULL,
  query_key    TEXT    NOT NULL,           -- requête normalisée + filtres
  result_count INTEGER NOT NULL,
  charged      INTEGER NOT NULL            -- 0 ou 1
);
CREATE INDEX idx_search_history_at ON search_history(at);

CREATE TABLE metric_daily (
  day   TEXT    NOT NULL,                  -- AAAA-MM-JJ, heure locale
  key   TEXT    NOT NULL,
  value INTEGER NOT NULL,
  PRIMARY KEY (day, key)
);

CREATE TABLE meta (
  key   TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL                      -- schema_version, model_id (modèle actif), index_generation
);
```

### 3.2 Plein texte

```sql
CREATE VIRTUAL TABLE capture_fts USING fts5(
  text, window_title, url,
  content = 'capture',
  content_rowid = 'id',
  tokenize = "unicode61 remove_diacritics 2"
);

CREATE TRIGGER capture_ai AFTER INSERT ON capture BEGIN
  INSERT INTO capture_fts(rowid, text, window_title, url)
  VALUES (new.id, new.text, new.window_title, new.url);
END;

CREATE TRIGGER capture_ad AFTER DELETE ON capture BEGIN
  INSERT INTO capture_fts(capture_fts, rowid, text, window_title, url)
  VALUES ('delete', old.id, old.text, old.window_title, old.url);
END;

-- Restreint aux colonnes indexées : la mise à jour de last_seen_at ne réindexe rien.
CREATE TRIGGER capture_au AFTER UPDATE OF text, window_title, url ON capture BEGIN
  INSERT INTO capture_fts(capture_fts, rowid, text, window_title, url)
  VALUES ('delete', old.id, old.text, old.window_title, old.url);
  INSERT INTO capture_fts(rowid, text, window_title, url)
  VALUES (new.id, new.text, new.window_title, new.url);
END;
```

- Table à **contenu externe** : l'index ne duplique pas le texte.
- `remove_diacritics 2` : « pâtes » et « pates » se correspondent.
- Pas d'index de préfixes : les requêtes par préfixe parcourent le dictionnaire de termes, ce qui
  suffit à ce volume et évite de grossir l'index.
- Le SQL de ce paragraphe vit dans un seul objet Kotlin (`FtsSchema`), utilisé par `onCreate` et par
  les migrations.

## 4. Interface du module

`core:database` n'expose ni Room ni SQL. Il expose des dépôts suspendus.

```kotlin
interface CaptureStore {
    suspend fun insert(capture: NewCapture, chunks: List<NewChunk>): Long     // une transaction
    suspend fun insertAll(items: List<Pair<NewCapture, List<NewChunk>>>): List<Long>  // une transaction ; imports, bases synthétiques
    suspend fun touch(captureId: Long, seenAt: Long): Boolean                 // last_seen_at, seen_count + 1 ; false si la capture n'existe plus
    suspend fun recentHashes(packageName: String, limit: Int): List<HashRef>  // id, simhash, first/last_seen_at
    suspend fun get(captureId: Long): Capture?
    suspend fun page(before: Long?, packageName: String?, limit: Int): List<CaptureSummary>  // curseur sur last_seen_at
    suspend fun delete(captureId: Long)
    suspend fun deleteByPackage(packageName: String)
    suspend fun deleteByRange(from: Long, to: Long)
    suspend fun stats(): StorageStats                                         // nombre, taille du fichier, plus ancienne capture
}

interface SearchStore {
    suspend fun lexical(match: String, filters: SearchFilters, limit: Int): List<LexicalRow>
    suspend fun captures(ids: List<Long>): List<Capture>
}

interface VectorStore {
    suspend fun pendingChunks(limit: Int): List<PendingChunk>                 // les plus récents d'abord
    suspend fun saveEmbeddings(rows: List<NewEmbedding>)                      // + chunk.state = 1, une transaction
    suspend fun markFailed(chunkId: Long)
    suspend fun binaryCodesAfter(embeddingId: Long, limit: Int): List<BinaryRow>
    suspend fun int8Vectors(embeddingIds: List<Long>): List<Int8Row>
    suspend fun pendingCount(): Int
    suspend fun resetForModel(modelId: Int)                                   // vide embedding, remet chunk.state à 0, écrit meta.model_id
    suspend fun indexGeneration(): Long                                       // meta.index_generation, 0 au départ
}

interface WipeService { suspend fun wipeEverything() }                        // base, clé, alias Keystore
```

Auxquels s'ajoutent `AppRuleStore`, `SecureProbeStore`, `MonetizationStore`, `SearchHistoryStore`,
`MetricsStore` (un par table) et `SettingsRepository` (DataStore).

Ces dépôts annexes lisent et écrivent des lignes ; les règles (plafonds de crédits, gratuité d'une
requête répétée, familles) appartiennent aux moteurs qui les emploient. `MonetizationStore` porte le
registre (`append`, `balance`, `countSince`, `lastAt`, `hasAny`) et la licence, ligne unique remplacée par
`setLicense`. `SettingsRepository` expose un `Flow<Settings>` et des flux par réglage ; ses valeurs par
défaut sont prises dans les objets `*Config` : rétention 90 jours, budget 500 Mo, toutes les familles,
modèle `fr`. S'y ajoutent le verrouillage actif avec 5 min de délai de grâce, et l'historique actif. Il
n'écrit que ses dix clés, et aucune ne peut contenir de donnée capturée (INV-06).

Toute suppression de captures incrémente `meta.index_generation` dans la même transaction : c'est le
signal de rechargement de l'index vectoriel en mémoire ([recherche §6](recherche.md)). `resetForModel`
l'incrémente aussi, puisqu'il vide les vecteurs. Une suppression qui ne supprime rien ne l'incrémente pas.

Précisions (M1-04) :

- `deleteByRange(from, to)` supprime toute capture **vue** pendant l'intervalle :
  `first_seen_at ≤ to` et `last_seen_at ≥ from`. C'est le choix le plus protecteur : un écran affiché
  pendant la période à effacer disparaît, même s'il avait été vu avant.
- `page` : curseur strict sur `last_seen_at` ; l'aperçu (`CaptureSummary.preview`) est fait des
  160 premiers caractères du texte.
- `pendingChunks` : fragments en attente par identifiant décroissant (derniers insérés d'abord), avec le
  texte du fragment découpé en Kotlin dans `capture.text`. Les positions sont des indices de `String`
  (UTF-16), que `substr` de SQLite ne connaît pas.
- `markFailed` : `attempts + 1`, état « en échec » à `IndexConfig.maxAttempts`.
- `stats().sizeBytes` : taille logique, `page_count × page_size`.
- Types échangés : `NewCapture`, `NewChunk`, `HashRef`, `StorageStats`, `LexicalRow`, `PendingChunk`,
  `NewEmbedding`, `BinaryRow`, `Int8Row` (paquet `core.database.store`). Les autres modules reçoivent
  un `DatabaseHandle`, qui donne les dépôts et `close()`, jamais la base Room.

## 5. Requête lexicale de référence

```sql
SELECT c.id,
       rank,                                                       -- plus petit = meilleur
       snippet(capture_fts, 0, char(2), char(3), '…', 24) AS snip
FROM capture_fts
CROSS JOIN capture c ON c.id = capture_fts.rowid
WHERE capture_fts MATCH :match
  AND rank MATCH 'bm25(1.0, 2.0, 0.5)'
  AND (:fromTs IS NULL OR c.last_seen_at  >= :fromTs)
  AND (:toTs   IS NULL OR c.first_seen_at <= :toTs)
  AND (:pkgFilter = 0 OR c.package_name IN (:packages))
ORDER BY rank
LIMIT :limit;
```

Les caractères `char(2)` et `char(3)` délimitent les termes à surligner ; ils sont retirés du texte à
l'ingestion, donc ne peuvent pas apparaître dans une capture.

Cette forme est **obligatoire** ([prototype S1](../prototypes/S1-sqlcipher-fts5.md)) :

- `rank MATCH 'bm25(…)'` choisit les poids du classement, et `ORDER BY rank` laisse FTS5 trier
  lui-même. Les lignes sortent dans l'ordre, et `snippet` n'est calculé que pour celles que garde
  `LIMIT`.
- `CROSS JOIN` interdit au planificateur de mettre `capture` en boucle externe.
- La forme naïve, `ORDER BY bm25(capture_fts, …)` avec une jointure ordinaire, calcule l'extrait de
  **chaque** ligne trouvée avant le tri. Sur 10 000 captures, un mot fréquent y coûte 100 à 190 ms au
  p95, contre 9 ms pour la forme ci-dessus ; un préfixe, 120 à 145 ms contre 22 à 27 ms.

## 6. Rétention et maintenance

`MaintenanceWorker` : WorkManager, périodique 24 h, batterie non faible. Dans l'ordre :

1. **Âge** : `DELETE FROM capture WHERE last_seen_at < :now − rétention` (sauf rétention illimitée).
2. **Budget** : tant que la taille utilisée dépasse `storageBudgetBytes`, supprimer les 500 captures
   les plus anciennes (par `last_seen_at`), puis optimiser l'index FTS5. La taille utilisée vaut
   `(page_count − freelist_count) × page_size` : le fichier, lui, ne rétrécit pas après une suppression,
   et les marques de suppression de FTS5 occupent de la place jusqu'à la fusion des segments.
3. **Annexes** : `metric_daily` de plus de 30 jours, `search_history` de plus de 90 jours,
   `secure_probe` périmées.
4. **Compactage** : si `freelist_count / page_count > 0,25` et que l'appareil est en charge,
   `PRAGMA wal_checkpoint(TRUNCATE)` puis `VACUUM`.
5. **Optimisation** : `INSERT INTO capture_fts(capture_fts) VALUES ('optimize')` une fois par semaine
   (date de la dernière dans `meta.fts_optimized_at`), et après toute suppression pour budget.

`secure_probe` périmées : sondes de plus de `OcrConfig.probeCacheTtlMs` (24 h). Le travail ne porte
aucune contrainte de réseau (`NetworkType.NOT_REQUIRED`) : la variante `offline` n'a pas
`ACCESS_NETWORK_STATE`. Il est planifié au démarrage de l'application (`KEEP`) ; WorkManager y est
initialisé à la demande, avec la fabrique de workers du graphe Hilt.

« Tout effacer » (`DatabaseWipeService`) ferme la base, supprime ses fichiers (WAL compris), la clé
enveloppée et l'alias du Keystore, puis remet les réglages à leurs valeurs par défaut.

Sans suppression massive, SQLite réutilise les pages libérées : la taille du fichier se stabilise.

## 7. Migrations

- `exportSchema = true` ; schémas JSON dans `core/database/schemas/`, versionnés.
- Chaque changement de schéma : incrément de version, objet `Migration`, test `MigrationTestHelper`
  exécuté avec la fabrique SQLCipher.
- Les objets hors Room (table FTS5, déclencheurs) sont créés et modifiés par `FtsSchema` ; une migration
  qui touche `capture` reconstruit l'index (`INSERT INTO capture_fts(capture_fts) VALUES ('rebuild')`).

## 8. Ordres de grandeur

Hypothèse d'un utilisateur intensif : 800 captures par jour, 600 caractères en moyenne.

| Poste | Par jour | À 90 jours |
|---|---|---|
| Texte | ~0,5 Mo | ~45 Mo |
| Index FTS5 | ~0,3 Mo | ~27 Mo |
| Vecteurs (432 octets par fragment, ~1,2 fragment par capture) | ~0,4 Mo | ~37 Mo |
| **Total** | **~1,2 Mo** | **~110 Mo** |

## 9. Configuration

```kotlin
data class StorageConfig(
    val retentionDays: Int? = 90,                      // null = illimitée
    val storageBudgetBytes: Long = 500L * 1024 * 1024,
    val budgetEvictionBatch: Int = 500,
    val vacuumFreeRatio: Double = 0.25,
    val historyRetentionDays: Int = 90,
    val metricsRetentionDays: Int = 30,
)
```

## 10. Tests attendus

Tous instrumentés (SQLCipher est natif), sur émulateur en CI.

- Ouverture avec clé ; fichier illisible sans la clé ; `PRAGMA compile_options` contient `ENABLE_FTS5`.
- Insertion → ligne FTS présente ; suppression → ligne FTS absente ; `touch` → index inchangé.
- Requête de référence : classement, extrait, filtres de dates et de paquets, diacritiques ; mêmes rangs
  que la forme naïve `ORDER BY bm25(…)` sur un jeu de requêtes.
- Suppression en cascade : fragments et vecteurs disparaissent ; `index_generation` incrémenté.
- `MaintenanceWorker` avec horloge simulée : âge, budget, annexes.
- Migrations : chaque version vers la suivante, données conservées.
- `wipeEverything` : fichiers, clé et alias supprimés ; réouverture → base neuve.
- Microbenchmark d'insertion (ENF-PERF-05) et de recherche lexicale à 100 000 captures (ENF-PERF-03).
