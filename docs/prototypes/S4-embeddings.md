# Prototype S4 — Embeddings sur l'appareil

| | |
|---|---|
| **Ticket** | [M0-09](../plan/M0-fondations.md#m0-09--prototype-s4--embeddings-sur-appareil) |
| **Date** | 12 novembre 2026 |
| **Code** | `engine/embedding/src/main/…/OrtModelLoader.kt` · `engine/embedding/src/androidTest/…/prototype/EmbeddingPrototypeTest.kt` · `tools/model/eval_pairs.py`, `tools/model/data/quality_pairs.jsonl` |
| **Exécution** | GitHub Actions, émulateurs `google_apis` x86_64, API 30, 34 et 36 (exécution `device-tests` 43228969713) ; qualité : Python, ONNX Runtime 1.31.1, modèles quantifiés |
| **Résultat** | 15 tests sur 15 verts (5 par niveau d'API) ; cosinus ≥ 0,997 avec les vecteurs de référence pour les deux modèles |

## 1. Questions posées

1. ONNX Runtime 1.31.1 exécute-t-il sur l'appareil les deux modèles quantifiés de `tools/model`, avec les
   mêmes vecteurs que la référence Python (cosinus ≥ 0,99) ?
2. Quel chemin de chargement retenir pour le modèle `fr` embarqué : projection en mémoire de l'asset, ou
   copie unique puis ouverture par chemin ? Et pour un pack (`multi`), l'ouverture par chemin suffit-elle ?
3. Combien coûtent le chargement, une requête, un passage de 256 jetons, avec 1, 2 ou 4 threads ? Quelle
   mémoire occupe une session ?
4. Les bibliothèques natives sont-elles alignées sur 16 Ko ? Quel poids ajoutent-elles ?
5. Le modèle `fr` se comporte-t-il comme attendu en français, et que perd-il face à `multi` (risque R-06) ?

## 2. Méthode

- Le test exécute les modèles sur les identifiants de jetons enregistrés dans
  `testdata/embedding_golden_<id>.json` (50 textes, préfixes et troncature compris) : le prototype ne
  dépend pas du tokenizer Kotlin. Les fichiers de référence sont empaquetés dans l'APK de test.
- `fr` est l'asset non compressé `model/fr/model.onnx` du module ; `multi` est poussé sur l'émulateur par
  le workflow, puis copié dans les fichiers privés de l'application et ouvert par son chemin.
- Agrégation : moyenne de `last_hidden_state` sur les jetons, normalisation L2 (spec §4).
- Latence : séquences de 16, 128 et 256 jetons, une session par nombre de threads intra-opérateurs
  (1, 2, 4 ; un thread inter-opérateurs), 2 exécutions d'échauffement puis médiane de 7.
- Mémoire : PSS totale du processus de test (`Debug.getMemoryInfo`) avant la création de la session,
  puis après la première inférence.

## 3. Mesures

### 3.1 Exactitude

| | API 30 | API 34 | API 36 |
|---|---|---|---|
| `fr`, projection de l'asset — cosinus le plus bas sur 50 textes | 0,9971 | 0,9971 | 0,9971 |
| `fr`, copie ouverte par chemin | 0,9971 | 0,9971 | 0,9971 |
| `multi`, fichier | 0,9973 | 0,9973 | 0,9973 |
| Normes | 1 (± 10⁻⁴) | 1 | 1 |

### 3.2 Chargement et mémoire

| | API 30 | API 34 | API 36 | Cible (spec §8, appareil moyen) |
|---|---|---|---|---|
| `fr` projeté : première session | 153 ms | 248 ms | 157 ms | ≤ 0,5 s |
| `fr` projeté : PSS ajoutée | **70 Mo** | **70 Mo** | **61 Mo** | |
| Copie de l'asset vers `noBackupFilesDir` (une fois) | 47 ms | 61 ms | 39 ms | |
| `fr` copié : première session | 155 ms | 169 ms | 152 ms | ≤ 0,5 s |
| `fr` copié : PSS ajoutée | **35 Mo** | **34 Mo** | **27 Mo** | |
| `fr` copié : PSS totale du processus de test | 159 Mo | 134 Mo | 102 Mo | ≤ 150 Mo |
| `multi` par fichier : première session | 262 ms | 432 ms | 232 ms | ≤ 1 s |
| `multi` : PSS ajoutée / totale | 113 / 185 Mo | 114 / 231 Mo | 104 / 182 Mo | ≤ 300 Mo |

La PSS totale inclut le processus d'instrumentation et le runtime de test ; celle de l'application seule
sera mesurée sur le téléphone.

### 3.3 Latence (médianes)

| | 1 thread | 2 threads | 4 threads |
|---|---|---|---|
| `fr`, 16 jetons | 19,0–19,3 ms | **10,9–11,7 ms** | 28–54 ms |
| `fr`, 128 jetons | 130–131 ms | **71–77 ms** | 200–231 ms |
| `fr`, 256 jetons | 279–285 ms | **151–165 ms** | 385–487 ms |
| `multi`, 16 jetons | 18,7–22,1 ms | 10,4–54 ms | 28–39 ms |
| `multi`, 128 jetons | 126–298 ms | 67–93 ms | 184–317 ms |
| `multi`, 256 jetons | 274–367 ms | 139–171 ms | 364–518 ms |

Fourchettes sur les trois niveaux d'API. Cibles de la spec §8 (appareil moyen) : requête ≤ 40 ms,
passage de 256 jetons à 2 threads ≤ 150 ms.

### 3.4 Poids et alignement

| Élément | arm64-v8a | armeabi-v7a |
|---|---|---|
| `libonnxruntime.so` + `libonnxruntime4j_jni.so` | 34,3 Mo | 24,3 Mo |
| Modèle `fr` (`model.onnx` stocké, `vocab.bin`) | 36,8 Mo | 36,8 Mo |
| Alignement des segments `LOAD` d'ONNX Runtime | 2¹⁴ (16 Ko) | 2¹⁴ |

APK `adsRelease` universel : 57,8 Mo avec le modèle seul, **116,5 Mo** avec ONNX Runtime. Dans un AAB
découpé par ABI, l'arm64 pèse environ 85 Mo sur l'appareil (ML Kit 13 Mo, ONNX Runtime 34 Mo, modèle
37 Mo, code et ressources). `scripts/check-elf-alignment.sh` passe.

### 3.5 Qualité : `fr` contre `multi`

Vingt paires requête/passage rédigées pour l'occasion (`tools/model/data/quality_pairs.jsonl`) : dix
sujets de la vie courante, chacun en français et en anglais. Chaque requête classe les passages ; modèles
quantifiés, exécutés par `tools/model/eval_pairs.py` sur une machine à arithmétique int8 exacte.

| Modèle | Jeu | Top-1 | MRR | Marge moyenne |
|---|---|---|---|---|
| `fr` | français (10) | 90 % | 0,933 | 0,043 |
| `fr` | anglais (10) | 100 % | 1,000 | 0,076 |
| `fr` | les 20 mélangés | 90 % | 0,942 | 0,041 |
| `fr` | requête FR → passages EN | 90 % | 0,925 | 0,045 |
| `multi` | français (10) | 90 % | 0,933 | 0,043 |
| `multi` | anglais (10) | 100 % | 1,000 | 0,075 |
| `multi` | les 20 mélangés | 95 % | 0,967 | 0,040 |
| `multi` | requête FR → passages EN | 100 % | 1,000 | 0,048 |

Erreurs : « comment résilier mon abonnement de sport » préfère, avec les deux modèles, le passage sur le
remboursement d'un billet (0,844 contre 0,825) ; avec `fr` seulement, « location de voiture à
l'aéroport » place le passage anglais du même sujet devant le passage français.

## 4. Constats

1. **Les deux modèles tournent sur l'appareil avec les vecteurs de la référence** : cosinus ≥ 0,997 sur
   les 50 textes et les trois niveaux d'API, normes à 1. L'écart résiduel vient de l'arithmétique int8 de
   l'émulateur x86 ; il reste loin du seuil de 0,99.
2. **La projection en mémoire coûte deux fois la taille du modèle.** ONNX Runtime ne lit pas les poids en
   place dans le tampon projeté : il les recopie en analysant le graphe, et les pages de l'asset projeté
   s'ajoutent à la PSS (61 à 70 Mo pour un modèle de 36 Mo). Ouvert par son chemin, le même modèle ne
   coûte que sa taille (27 à 35 Mo). Le chargement dure autant dans les deux cas (150 à 250 ms), et la
   copie unique de l'asset prend 40 à 60 ms.
3. **Deux threads sont le bon réglage par défaut.** Ils divisent la latence par 1,7 à 1,8. Quatre threads
   sont nettement plus lents sur l'émulateur, qui n'expose que deux cœurs : le réglage `4 en charge` de la
   spec reste à valider sur téléphone.
4. **Les cibles de la spec §8 sont atteignables** : requête de 16 jetons en 11 ms (cible 40 ms), passage de
   256 jetons à 2 threads en 151 à 165 ms (cible 150 ms, atteinte sur un téléphone récent, plus rapide que
   l'émulateur), chargement de `fr` en 0,15 à 0,25 s (cible 0,5 s), de `multi` en 0,23 à 0,43 s (cible 1 s),
   mémoire de `multi` sous 300 Mo. La PSS avec `fr` (102 à 159 Mo, processus de test compris) est à
   remesurer sur le téléphone, application seule.
5. **Le modèle `fr` se comporte comme attendu en français** : résultats identiques à ceux de `multi` (son
   vocabulaire réduit couvre tous les jetons des textes français). Il reste bon en anglais simple ; il perd
   un peu sur les cas mixtes et interlangues. Vingt paires ne font qu'un premier regard : le verdict
   chiffré revient au jeu d'évaluation de M5-07.
6. **ONNX Runtime est le plus gros poste natif** (34 Mo en arm64). Le paquet réduit `onnxruntime-mobile` n'est plus
   publié ; une construction sur mesure limitée aux opérateurs des deux modèles réduirait ce
   poids (ticket d'optimisation éventuel, hors M0).

## 5. Décisions et corrections

| Sujet | Décision |
|---|---|
| Chemin de chargement de `fr` (spec §4) | **Copie unique** de l'asset vers `noBackupFilesDir/model/fr/`, puis ouverture **par chemin** : moitié moins de mémoire que la projection, pour 40 à 60 ms une fois et 36 Mo de stockage. La copie est refaite quand le `MODEL_MANIFEST.json` de l'asset diffère de celui de la copie (mise à jour de l'application). La projection reste le repli si la copie est impossible (stockage plein). Spec §4 corrigée. |
| Packs (`multi`) | Ouverture par chemin, confirmée. |
| Threads | 2 par défaut, confirmé ; 4 en charge à valider sur téléphone (validation manuelle), sinon 2 partout. |
| Cibles de la spec §8 | Atteignables ; aucune ne justifie un processus dédié (M10-02). |
| Modèle `fr` (R-06) | Conservé comme modèle par défaut. |

## 6. Validation manuelle (téléphone du porteur du projet)

À consigner dans la PR :

1. Construire et installer l'APK de test : `python tools/model/build_model.py --model fr --install`, puis
   `./gradlew :engine:embedding:installDebugAndroidTest`.
2. Facultatif, pour `multi` : `python tools/model/build_model.py --model multi`, puis
   `adb shell mkdir -p /data/local/tmp/seekmind/multi` et
   `adb push tools/model/out/multi/model.onnx /data/local/tmp/seekmind/multi/`.
3. `adb shell am instrument -w -e class fr.corsicajack.seekmind.engine.embedding.prototype.EmbeddingPrototypeTest
   fr.corsicajack.seekmind.engine.embedding.test/androidx.test.runner.AndroidJUnitRunner`
4. Relever `adb logcat -d -s SeekMindProto:I` : en particulier `fr_t2_len256_ms_median`,
   `fr_t4_len256_ms_median` (téléphone en charge), `fr_copied_pss_total_mb`.
