# Spec — Indexation (`engine:index`)

| | |
|---|---|
| **Exigences** | EF-CAP-07, EF-IDX-01 à 03, ENF-PERF-05, ENF-BAT-01 |
| **Dépend de** | `core:database`, `engine:embedding` |
| **Tickets** | M4-01, M4-02, M4-05 |

## 1. Responsabilités

Transformer une `RawCapture` validée par la porte de confidentialité en lignes de base : normaliser,
détecter les quasi-doublons, découper, insérer, puis faire calculer les vecteurs plus tard, par lots.

```kotlin
interface CaptureSink {                       // implémentée par IngestService
    suspend fun ingest(raw: RawCapture): IngestResult
}

sealed interface IngestResult {
    data class Stored(val captureId: Long) : IngestResult
    data class Deduplicated(val captureId: Long) : IngestResult
}
```

`ingest` s'exécute sur le thread `sm-capture` ; les appels sont donc sérialisés.

## 2. Normalisation du texte

`TextNormalizer.normalize(text): String`, Kotlin pur :

1. Normalisation Unicode NFC.
2. Suppression des caractères de contrôle (sauf `\n`), des caractères de largeur nulle, et des
   caractères U+0002 et U+0003 (réservés au surlignage des extraits).
3. Par ligne : tabulations et espaces multiples réduits à une espace ; espaces de bord retirées.
4. Lignes vides retirées ; lignes strictement identiques dédoublonnées (première occurrence conservée).
5. Troncature à `maxTextChars`, sur une fin de ligne.

Le texte est stocké tel quel (casse et accents conservés) ; FTS5 se charge du pliage à l'indexation.

## 3. SimHash

`SimHash.of(text): Long`, Kotlin pur, déterministe.

1. **Mots** : suites de lettres ou de chiffres Unicode, en minuscules, diacritiques retirés.
2. **Traits** : trigrammes de mots consécutifs joints par une espace. Avec moins de trois mots : les mots eux-mêmes.
3. **Hachage** de chaque trait : FNV-1a 64 bits sur ses octets UTF-8.
4. **Accumulation** : pour chaque bit `i` de 0 à 63, `v[i] += 1` si le bit est à 1 dans le hachage, `−1` sinon.
5. **Empreinte** : bit `i` à 1 si `v[i] > 0`.

Similarité de deux empreintes : `1 − distance de Hamming / 64`. Seuil de doublon : similarité ≥ 0,85,
soit **distance ≤ 9**, confirmé au ticket M4-01 (`SimHashThresholdTest`, écrans synthétiques de
12 lignes, 2 000 paires par catégorie) :

| Paires | Médiane | p90 | ≤ 9 |
|---|---|---|---|
| Écrans identiques | 0 | 0 | 100 % |
| Une ligne modifiée | 7 | 11 | 77 % |
| Défilement de 1 à 3 lignes | 9 | 15 | 53 % |
| Écrans sans rapport du corpus (gabarits partagés) | 25 | 30 | 0 % (minimum 13) |
| Textes indépendants | 32 | 37 | 0 % (moyenne 31,7) |

Aucun faux doublon ; la marge entre le seuil et le minimum des écrans sans rapport est de 4. Un seuil
plus haut attraperait plus de défilements au prix de cette marge : il reste à 9.

## 4. Déduplication

Une capture est un doublon d'une capture existante `c` si **toutes** ces conditions sont vraies :

- même `package_name` ;
- `hamming(simhash, c.simhash) ≤ dedupMaxHamming` (9) ;
- `c.last_seen_at ≥ maintenant − dedupRecentMs` (6 h) ;
- `c.first_seen_at ≥ maintenant − dedupSpanMs` (24 h).

Les deux bornes de temps garantissent qu'une capture ne couvre jamais plus de 24 h : les requêtes
temporelles restent justes, et un écran revu le lendemain redevient une capture datée de ce jour.

Candidats : les `dedupWindow` (8) dernières captures du paquet, tenues dans un cache en mémoire par
paquet, rempli à la demande par `CaptureStore.recentHashes`.

Doublon → `CaptureStore.touch(c.id, maintenant)` et `IngestResult.Deduplicated`. Le texte existant n'est
pas modifié.

## 5. Découpage en fragments

`Chunker.split(text): List<ChunkSpan>`, Kotlin pur :

- Regroupement glouton de lignes entières tant que le fragment ne dépasse pas `chunkMaxChars` (800).
- Une ligne plus longue que la limite est coupée à la dernière fin de phrase ou espace avant la limite.
- Au plus `maxChunksPerCapture` (4) fragments ; le texte restant est ignoré pour la recherche sémantique
  (il reste trouvable par mots-clés).
- Chaque fragment est décrit par ses positions `[start, end)` dans le texte de la capture : le texte
  n'est pas dupliqué en base.

Texte soumis au modèle pour un fragment : `"passage: " + titre + " · " + fragment`, le titre étant
`window_title` s'il existe (sinon le préfixe seul).

## 6. Insertion

Une seule transaction (`CaptureStore.insert`) : la ligne `capture`, l'index FTS5 par déclencheur, les
lignes `chunk` en état « en attente ». À la sortie de la transaction la capture est trouvable par
mots-clés (EF-IDX-01).

Ensuite, hors transaction : si des fragments sont en attente, s'assurer que le travail unique
`embedding-charging` est planifié (§7).

## 7. Calcul différé des vecteurs

### 7.1 Planification

| Travail WorkManager | Type | Contraintes | Politique |
|---|---|---|---|
| `embedding-periodic` | Périodique, 15 min | Batterie non faible | `KEEP`, planifié au démarrage de l'application et à la connexion du service |
| `embedding-charging` | Unique | En charge | `KEEP`, replanifié tant qu'il reste des fragments en attente |

Aucune contrainte réseau n'est utilisée (elle exigerait une permission réseau).

### 7.2 Exécution (`EmbeddingWorker`)

```text
échéance ← début + 9 min
acquérir le moteur d'embeddings (chargement du modèle)
tant que maintenant < échéance et que le travail n'est pas arrêté :
    si le gouverneur n'autorise pas les embeddings : sortir
    lot ← VectorStore.pendingChunks(32)            -- les plus récents d'abord
    si lot vide : sortir
    vecteurs ← moteur.embedPassages(textes du lot)
    VectorStore.saveEmbeddings(vecteurs quantifiés) -- une transaction
    hors charge : pause de 100 ms entre deux lots
libérer le moteur
```

- Les fragments les plus récents passent d'abord : ce que l'utilisateur vient de voir devient
  cherchable par le sens en priorité.
- Échec sur un fragment : `attempts + 1` ; au troisième, état « en échec » (le fragment reste trouvable
  par mots-clés). Échec de chargement du modèle : `Result.retry()` avec temporisation exponentielle.
- Le travail rend `Result.success()` même s'il reste des fragments : la prochaine période reprend.
- Compteurs de diagnostic : fragments vectorisés, durée médiane par fragment, arrêts par motif.

### 7.3 Changement de modèle

Le modèle actif change dans deux cas : une mise à jour de l'application qui remplace le modèle
embarqué, ou l'activation d'un pack de langue par l'utilisateur ([embeddings §5.3](embeddings.md)).
`EmbeddingEngine.modelId` est comparé à `meta.model_id` au démarrage et à chaque activation. S'ils diffèrent :
`VectorStore.resetForModel(modelId)` vide la table des vecteurs et remet tous les fragments en attente.
La recherche par mots-clés n'est pas affectée ; la recherche sémantique revient à mesure que les lots passent.

## 8. Configuration

```kotlin
data class IndexConfig(
    val maxTextChars: Int = 12_000,
    val dedupMaxHamming: Int = 9,
    val dedupWindow: Int = 8,
    val dedupRecentMs: Long = 6 * 3_600_000L,
    val dedupSpanMs: Long = 24 * 3_600_000L,
    val chunkMaxChars: Int = 800,
    val maxChunksPerCapture: Int = 4,
    val embedBatchSize: Int = 32,
    val workerBudgetMs: Long = 9 * 60_000L,
    val interBatchPauseMs: Long = 100,
    val maxAttempts: Int = 3,
)
```

## 9. Tests attendus

- **JVM** : `TextNormalizer` (table de cas, idempotence) ; `SimHash` (déterminisme, texte identique →
  distance 0, permutation de deux lignes → distance faible, textes sans rapport → distance proche de 32) ;
  règles de déduplication avec horloge simulée (les quatre conditions) ; `Chunker` (limites, ligne
  longue, plafond, positions exactes) ; `IngestService` avec dépôts factices.
- **Instrumenté** : transaction d'insertion ; `EmbeddingWorker` avec le pilote de test de WorkManager et
  `FakeEmbeddingEngine` (lots, arrêt par le gouverneur, reprise, échecs, changement de modèle).
- **Bout en bout** : E2E-08, E2E-17.
