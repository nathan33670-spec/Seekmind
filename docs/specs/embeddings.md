# Spec — Embeddings (`engine:embedding`, `tools/model`)

| | |
|---|---|
| **Exigences** | EF-IDX-02, EF-SRCH-03, ENF-MEM-02, ENF-STO-02 |
| **Tickets** | M0-09 (prototype), M0-10 (outillage), M4-03, M4-04, M4-06 |
| **Voir aussi** | [ADR-0006](../adr/0006-modele-d-embeddings.md) |

## 1. Modèle

| | |
|---|---|
| Modèle | `intfloat/multilingual-e5-small` (licence MIT) |
| Révision figée | `614241f622f53c4eeff9890bdc4f31cfecc418b3` |
| Architecture | Encodeur de type XLM-R, 12 couches, dimension 384, vocabulaire de 250 002 jetons |
| Longueur maximale | 512 jetons ; SeekMind tronque les passages à 256 et les requêtes à 64 |
| Préfixes obligatoires | `"query: "` pour une requête, `"passage: "` pour un texte indexé |
| Agrégation | Moyenne des états cachés pondérée par le masque d'attention, puis normalisation L2 |
| Exécution | ONNX Runtime, quantification dynamique int8, processeur uniquement |

Un texte encodé sans son préfixe, ou avec le mauvais, dégrade nettement la recherche : les préfixes
sont ajoutés par le moteur, jamais par l'appelant.

## 2. Fabrication des artefacts (`tools/model`)

Scripts Python, versions figées dans `tools/model/requirements.txt`.

| Script | Entrée | Sortie (`tools/model/out/`) |
|---|---|---|
| `build_model.py` | `onnx/model.onnx` de la révision figée (470 Mo, SHA-256 `ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665`) | `e5s-int8.onnx` (~118 Mo) par `onnxruntime.quantization.quantize_dynamic(..., weight_type=QuantType.QInt8, per_channel=False, reduce_range=False)` |
| `export_vocab.py` | `tokenizer.json` (SHA-256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`) | `vocab.bin` (§3.2) |
| `gen_tokenizer_golden.py` | 1 000 phrases FR/EN versionnées | `testdata/tokenizer_golden.jsonl` |
| `gen_embedding_golden.py` | 50 textes versionnés, modèle quantifié | `testdata/embedding_golden.json` |
| `verify.py` | Artefacts | Contrôles de qualité ci-dessous |

Contrôles de qualité de `build_model.py` (échec du script sinon) :

- cosinus moyen entre vecteurs du modèle d'origine et du modèle quantifié ≥ 0,98 sur 200 phrases ;
- sur le jeu d'évaluation de la recherche, Rappel@5 du modèle quantifié ≥ Rappel@5 d'origine − 0,02
  (contrôle ajouté par le ticket M5-07, quand le jeu d'évaluation existe).

Le script écrit `MODEL_MANIFEST.json` (identifiant de modèle, révision source, empreintes des sorties,
versions des outils). Les empreintes attendues sont versionnées dans `engine/embedding/model.lock.json`.

**Les artefacts binaires ne sont jamais committés.** Intégration au build :

- `:engine:embedding:verifyModel` vérifie que `e5s-int8.onnx` et `vocab.bin` sont présents dans
  `engine/embedding/src/main/assets/model/` et conformes à `model.lock.json` ; sinon message indiquant
  la commande à lancer (`python tools/model/build_model.py --install`).
- La propriété `-PskipModel` désactive la vérification pour les tâches qui n'ont pas besoin du modèle
  (tests JVM, lint). Les tests JVM utilisent `FakeEmbeddingEngine`.
- En CI, les artefacts sont mis en cache avec `model.lock.json` comme clé.

## 3. Tokenizer

Implémentation en Kotlin pur de l'algorithme Unigram de SentencePiece, équivalente au tokenizer de
référence du modèle (normalisation précompilée, pré-découpage « Metaspace », modèle Unigram,
gabarit `<s> … </s>`).

```kotlin
interface Tokenizer {
    fun encode(text: String, maxTokens: Int): Encoding
}

class Encoding(val inputIds: IntArray, val attentionMask: IntArray)
```

### 3.1 Algorithme

1. **Normalisation** (approximation du jeu de règles `nmt_nfkc`) : NFKC ; caractères de contrôle
   supprimés ; tabulations, retours à la ligne, espaces insécables et espaces de largeur nulle remplacés
   par une espace ; espaces multiples réduites ; espaces de bord retirées.
2. **Pré-découpage** : chaque espace devient `▁` (U+2581) ; un `▁` est ajouté en tête ; le texte est
   découpé en mots commençant chacun par `▁`.
3. **Segmentation** de chaque mot par l'algorithme de Viterbi : parmi tous les découpages en jetons du
   vocabulaire, retenir celui dont la somme des scores est maximale. Un caractère absent du vocabulaire
   produit le jeton inconnu (`<unk>`, identifiant 3) avec le score `score minimal du vocabulaire − 10` ;
   des inconnus consécutifs sont fusionnés en un seul.
4. **Identifiants** : la position du jeton dans le vocabulaire. Jetons spéciaux : `<s>` = 0,
   `<pad>` = 1, `</s>` = 2, `<unk>` = 3.
5. **Gabarit** : `<s>` + jetons + `</s>`. **Troncature** : au plus `maxTokens`, `</s>` final conservé.
6. `attentionMask` : 1 pour chaque jeton.

### 3.2 Vocabulaire binaire

`vocab.bin`, petit-boutiste : magique `SMV1`, nombre de jetons (int32), puis pour chaque jeton, dans
l'ordre des identifiants : longueur en octets (uint16), octets UTF-8, score (float32). Environ 5 Mo.

Chargement en structures compactes : un tableau d'octets unique, un tableau de positions, un tableau de
scores, et une table de hachage à adressage ouvert (hachage 64 bits des octets du jeton → identifiant,
vérification des octets à chaque succès). **Budget mémoire : 16 Mo.** Pas de `HashMap<String, Int>`.

### 3.3 Parité

Le tokenizer est correct s'il produit exactement les mêmes identifiants que la référence Python sur au
moins **99,5 %** des 1 000 phrases de `testdata/tokenizer_golden.jsonl`. Les écarts restants sont listés
dans le rapport de test. Si le seuil n'est pas atteint (risque R-12), repli : embarquer une
bibliothèque native de tokenisation, par un ADR.

## 4. Moteur

```kotlin
interface EmbeddingEngine : AutoCloseable {
    val modelId: Int
    val dimensions: Int                                   // 384
    suspend fun embedPassages(texts: List<String>): List<FloatArray>   // ajoute "passage: "
    suspend fun embedQuery(text: String): FloatArray                   // ajoute "query: "
}

interface EmbeddingEngineProvider {
    /** Rend un moteur partagé ; le modèle est chargé au premier appel. */
    suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T
}
```

Implémentation `OrtEmbeddingEngine` :

- **Chargement** : le modèle est un asset non compressé ; il est projeté en mémoire depuis l'APK
  (`AssetFileDescriptor` → `FileChannel.map`) et passé à `OrtEnvironment.createSession(ByteBuffer, options)`.
  Si la projection est impossible, copie unique vers `noBackupFilesDir/model/` puis chargement par chemin.
  Le prototype M0-09 valide le chemin retenu et mesure la mémoire.
- **Options de session** : optimisation maximale, un thread inter-opérateurs, threads intra-opérateurs
  donnés par le gouverneur (2, ou 4 en charge), fournisseur d'exécution processeur.
- **Lots** : textes triés par longueur, sous-lots de 8, remplissage à la longueur du plus long du sous-lot
  (`<pad>`, masque à 0). Les entrées fournies sont celles que la session déclare (`input_ids`,
  `attention_mask`, et `token_type_ids` à zéro si elle l'exige).
- **Sortie** : moyenne masquée de `last_hidden_state`, puis normalisation L2.
- **Cycle de vie** : le fournisseur compte les utilisateurs ; la session est fermée 60 s après le
  dernier usage et sur `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`. L'interface de recherche la retient
  tant qu'elle est au premier plan.
- Les appels sont sérialisés (un seul calcul à la fois dans le processus).

## 5. Codage des vecteurs

`VectorCodec`, Kotlin pur.

| Forme | Calcul | Taille | Usage |
|---|---|---|---|
| int8 | `clamp(round(v[i] × S), −127, 127)` avec l'échelle fixe `S = int8Scale` | 384 octets | Rescore précis |
| Binaire | bit `i` = 1 si `v[i] > 0` ; 6 mots de 64 bits, petit-boutiste | 48 octets | Présélection par distance de Hamming |

Les composantes d'un vecteur normalisé de dimension 384 valent typiquement ±0,05 : multiplier par 127
gaspillerait la plage. L'échelle de départ est **`S = 400`** (plage utile ±0,32) ; le ticket M4-05 la
calibre pour que moins de 0,1 % des composantes soient écrêtées sur les vecteurs des 1 000 phrases de référence.

Similarité de rescore entre une requête en flottants `q` et un document int8 `d` :
`(Σ q[i] × d[i]) / S`, approximation du cosinus.

## 6. Identifiant de modèle

`modelId` est une constante entière incrémentée dès que change l'un de : fichier du modèle, vocabulaire,
règles de tokenisation, préfixes, longueurs maximales, échelle `S`. Elle est enregistrée avec chaque
vecteur ; un changement déclenche le recalcul ([indexation §7.3](indexation.md)).

## 7. Cibles de performance

À mesurer au prototype M0-09 puis à tenir en M4-04.

| Mesure | Appareil moyen | Appareil bas |
|---|---|---|
| Chargement de la session | ≤ 1 s | ≤ 2,5 s |
| Requête (≤ 16 jetons) | ≤ 40 ms | ≤ 120 ms |
| Passage de 256 jetons, 2 threads | ≤ 150 ms | ≤ 500 ms |
| Mémoire du processus, session chargée | ≤ 300 Mo | ≤ 300 Mo |

Si l'appareil bas dépasse ces cibles d'un facteur deux, le ticket M10-02 isole le moteur dans un
processus dédié et le ticket M10-07 évalue un modèle plus petit.

## 8. Configuration

```kotlin
data class EmbeddingConfig(
    val maxPassageTokens: Int = 256,
    val maxQueryTokens: Int = 64,
    val subBatchSize: Int = 8,
    val int8Scale: Float = 400f,
    val idleCloseMs: Long = 60_000,
    val threadsDefault: Int = 2,
    val threadsCharging: Int = 4,
)
```

## 9. Tests attendus

- **JVM** : tokenizer contre le jeu de référence (seuil 99,5 %) ; cas limites (chaîne vide, émojis,
  caractères inconnus, troncature) ; `VectorCodec` (aller-retour, écrêtage, bits) ; chargement de `vocab.bin`.
- **Instrumenté** : `OrtEmbeddingEngine` contre `testdata/embedding_golden.json` (cosinus ≥ 0,99 pour
  chaque texte) ; normes égales à 1 ; préfixes appliqués ; fermeture après inactivité ; mesures du §7.
- **Outils** : `pytest` sur `tools/model` (format du vocabulaire, empreintes, contrôles de qualité).
