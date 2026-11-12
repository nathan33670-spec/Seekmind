# Prototype S1 — SQLCipher, Room, FTS5

| | |
|---|---|
| **Ticket** | [M0-06](../plan/M0-fondations.md#m0-06--prototype-s1--sqlcipher-room-fts5) |
| **Date** | 12 novembre 2026 |
| **Code** | `core/database/src/androidTest/…/prototype/` (uniquement dans l'APK de test) · `.github/workflows/device-tests.yml` |
| **Exécution** | GitHub Actions, émulateurs `google_apis` x86_64, API 30, 34 et 36 |
| **Résultat** | 24 tests sur 24 verts (8 par niveau d'API), sur trois exécutions successives |

## 1. Questions posées

1. SQLCipher 4.20.0 embarque-t-il FTS5 ? Room 2.8.5 s'ouvre-t-il dessus par `SupportOpenHelperFactory`,
   en WAL, avec la clé brute de la spec stockage §2 ?
2. La table FTS5 à contenu externe et ses déclencheurs (spec §3.2) gardent-ils l'index en phase avec
   `capture` ? La mise à jour de `last_seen_at` évite-t-elle toute réindexation ?
3. La requête de référence (spec §5) classe-t-elle correctement, produit-elle l'extrait attendu,
   ignore-t-elle les accents et la casse, applique-t-elle les filtres ?
4. Le fichier est-il illisible sans la bonne clé ?
5. Combien coûtent une insertion et une requête `MATCH` ?

## 2. Versions constatées

| Élément | Valeur (identique sur les trois niveaux d'API) |
|---|---|
| `PRAGMA cipher_version` | `4.20.0 community` |
| `sqlite_version()` | 3.54.1 |
| `PRAGMA compile_options` | contient `ENABLE_FTS5` |
| Mode de journal | `wal` (demandé à Room par `setJournalMode`) |
| `foreign_keys`, `secure_delete`, `temp_store` après `onOpen` | 1, 2 (`FAST`), 2 (`MEMORY`) |
| Ouverture avec une autre clé | `SQLiteNotADatabaseException` ; les 16 premiers octets du fichier ne sont pas l'en-tête SQLite |

## 3. Tests

| Test | Vérifie |
|---|---|
| `theEngineIsSqlCipherWithFts5` | Versions, `ENABLE_FTS5`, WAL et pragmas de la spec §2 |
| `anInsertedCaptureIsFoundByMatch` | Insertion par Room, ligne trouvée par `MATCH` (déclencheur `capture_ai`) |
| `bm25RanksAndSnippetMarksTheMatchedTerms` | Classement `bm25` (texte court et dense devant texte long), rang négatif, extrait balisé par `char(2)`/`char(3)` ; même résultat sous les deux formes de requête (§5) |
| `matchingIgnoresDiacriticsAndCase` | « pates » ↔ « pâtes », « PÂTES », « crème brûlée » ↔ « CREME BRULEE », préfixe `pât*` |
| `filtersOnDatesAndPackagesApply` | Filtres `from`, `to` et liste de paquets, sous les deux formes |
| `triggersKeepTheIndexInStepWithTheTable` | `touch` n'écrit rien dans l'index (`capture_fts_data` inchangé) ; mise à jour du texte puis suppression reflétées ; `integrity-check` de FTS5 après chaque étape |
| `theFileIsUnreadableWithoutTheRightKey` | En-tête chiffré, échec avec la mauvaise clé, réouverture avec la bonne |
| `measuresInsertionsAndQueries` | Mesures du §4 ; mêmes rangs sous les deux formes sur 20 requêtes |

Les textes sont synthétiques : pseudo-mots formés de syllabes françaises (accents compris), tirés avec
une fréquence très inégale comme dans une langue réelle, 600 caractères par capture, graine fixe.

## 4. Mesures

10 000 captures de 600 caractères, insérées une par une (une transaction chacune, comme l'ingestion).
Requêtes : 200 par type et par forme, `LIMIT 50`, sur la base pleine. Exécution `device-tests`
n° 43219487956.

| Mesure | API 30 | API 34 | API 36 | Cible |
|---|---|---|---|---|
| Insertion, médiane | 1,1 ms | 1,1 ms | 1,1 ms | |
| Insertion, p95 | 2,1 ms | 3,6 ms | 5,6 ms | ≤ 15 ms (ENF-PERF-05) |
| Insertion, total des 10 000 | 13,4 s | 15,9 s | 18,3 s | |
| Un mot, p95 — forme naïve | 113 ms | 93 ms | 113 ms | |
| Un mot, p95 — **forme retenue** | **9,1 ms** | **26 ms** | **9,5 ms** | ≤ 100 ms à 100 000 captures (ENF-PERF-03) |
| Deux mots, p95 — naïve / retenue | 6,5 / 5,1 ms | 6,1 / 4,3 ms | 7,6 / 6,3 ms | |
| Préfixe de 3 lettres, p95 — naïve / retenue | 117 / 22 ms | 156 / 20 ms | 118 / 22 ms | |
| Médianes, forme retenue (un mot / deux mots / préfixe) | 3,8 / 1,2 / 5,8 ms | 5,7 / 1,1 / 5,1 ms | 3,8 / 1,4 / 5,9 ms | |
| Taille du fichier (après `wal_checkpoint(TRUNCATE)`) | 11,7 Mo | 11,7 Mo | 11,7 Mo | |

Variabilité : sur deux exécutions précédentes, le p95 d'insertion en API 36 a valu 15,6 puis 11,6 ms (le
runner partagé était plus chargé) ; les autres valeurs varient de moins de 30 %.

## 5. Constats

1. **La pile fonctionne telle que spécifiée.** FTS5 est compilé dans SQLCipher ; Room s'ouvre par la
   fabrique de SQLCipher avec la clé brute `x'…'`, en WAL ; les pragmas d'`onOpen` s'appliquent ; les
   déclencheurs gardent l'index exact (`integrity-check` après insertion, `touch`, mise à jour,
   suppression) ; `touch` n'écrit rien dans l'index ; accents et casse sont ignorés à l'indexation comme
   à la requête.
2. **La requête de référence de la spec était trop lente pour les mots fréquents.** Écrite avec
   `ORDER BY bm25(capture_fts, …)` et une jointure ordinaire, elle calcule `snippet()` pour chaque ligne
   trouvée avant de trier : 93 à 113 ms au p95 pour un mot, déjà sur 10 000 captures (la cible vaut pour
   100 000). La forme retenue fait trier FTS5 sur sa colonne `rank` (`rank MATCH 'bm25(1.0, 2.0, 0.5)'`)
   et fige l'ordre de jointure (`CROSS JOIN`) : l'extrait n'est calculé que pour les 50 lignes gardées.
   Gain : un facteur 4 à 12 sur les mots seuls, 5 à 8 sur les préfixes. Les rangs sont identiques
   (vérifié sur 20 requêtes).
3. **Les préfixes ne justifient pas d'index de préfixes** : 20 à 22 ms au p95 sous la forme retenue,
   ce qui confirme le choix de la spec §3.2.
4. **Insertion** : 2 à 6 ms au p95, sous la cible de 15 ms sur les trois niveaux d'API.
5. **Taille** : 11,7 Mo pour 6 Mo de texte, soit 1,95 fois le texte avec l'index FTS5 et les pages de
   la base. La spec §8 prévoit 1,6 fois (45 Mo de texte, 27 Mo d'index à 90 jours) ; l'écart, d'environ
   20 %, reste dans le budget de 500 Mo. Les vecteurs ne sont pas comptés ici.
6. **Mesure sur 10 000 captures seulement**, comme le demande le ticket. La forme retenue coûte une
   passe de classement sur toutes les correspondances puis un `LIMIT` ; son coût croît avec le nombre de
   correspondances, pas avec la taille de la base. Le microbenchmark à 100 000 captures (spec §10,
   ENF-PERF-03) reste à faire avec le dépôt définitif (M1).

## 6. Écarts par rapport à la spec et corrections

| Sujet | Correction |
|---|---|
| Requête lexicale de référence (spec stockage §5) | Remplacée par la forme retenue (`rank MATCH`, `ORDER BY rank`, `CROSS JOIN`), déclarée obligatoire, avec la raison et les mesures. Test attendu ajouté au §10 : mêmes rangs que la forme naïve. |
| Rappels `onOpen` de Room | Les pragmas sont passés par `query(…)`, et le curseur fermé aussitôt : `secure_delete` et `temp_store` renvoient une ligne, ce que `execSQL` n'est pas fait pour recevoir. Détail d'implémentation pour M1-02, sans changement de spec. |

## 7. Conclusion

**Pile confirmée** : Room 2.8.5 sur SQLCipher 4.20.0 avec FTS5 (ADR-0004 inchangé). La seule correction
porte sur la forme de la requête lexicale, désormais fixée par la spec stockage §5. Aucun ADR nouveau.
