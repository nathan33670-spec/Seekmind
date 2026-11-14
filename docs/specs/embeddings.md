# Spec — Embeddings et packs de langue (`engine:embedding`, `tools/model`)

| | |
|---|---|
| **Exigences** | EF-IDX-02, EF-SRCH-03, EF-LANG-01 à 04, ENF-MEM-02, ENF-STO-02 |
| **Tickets** | M0-09 (prototype), M0-10 (outillage), M4-03, M4-04, M4-06, M4-07 |
| **Voir aussi** | [ADR-0010](../adr/0010-modele-francais-et-packs-de-langue.md) · [ADR-0006](../adr/0006-modele-d-embeddings.md) |

## 1. Modèles

Un **catalogue** de modèles, dont un seul est actif à la fois.

| Identifiant | Modèle | Révision figée | Langues | Vocabulaire | Taille quantifiée | Livraison |
|---|---|---|---|---|---|---|
| `fr` | `antoinelouis/french-me5-small` (MIT) | `1605fe3b64cb22dde93f30e8fbfb1889742d2753` | Français | 37 200 jetons | ~36 Mo | Embarqué (asset du module) |
| `multi` | `intfloat/multilingual-e5-small` (MIT) | `614241f622f53c4eeff9890bdc4f31cfecc418b3` | Une centaine de langues | 250 002 jetons | ~118 Mo | Pack de langue à la demande |

Le modèle `fr` est le modèle `multi` dont le vocabulaire a été réduit au français. Tout le reste est commun :

| | |
|---|---|
| Architecture | Encodeur de 12 couches, dimension 384 |
| Longueur maximale | 512 jetons ; SeekMind tronque les passages à 256 et les requêtes à 64 |
| Préfixes obligatoires | `"query: "` pour une requête, `"passage: "` pour un texte indexé |
| Agrégation | Moyenne des états cachés pondérée par le masque d'attention, puis normalisation L2 |
| Tokenisation | Unigram (SentencePiece), pré-découpage « Metaspace », gabarit `<s> … </s>` |
| Exécution | ONNX Runtime, quantification dynamique int8, processeur uniquement |

Un texte encodé sans son préfixe, ou avec le mauvais, dégrade nettement la recherche : les préfixes sont
ajoutés par le moteur, jamais par l'appelant.

## 2. Fabrication des artefacts (`tools/model`)

Scripts Python, versions figées dans `tools/model/requirements.txt`. Un jeu d'artefacts par modèle, dans
`tools/model/out/<identifiant>/`.

| Script | Rôle |
|---|---|
| `build_model.py --model fr` | Télécharge `model.safetensors` de la révision figée (144 Mo, SHA-256 `de4508e0e6fa9b71605ce5e00af423d741a8f28a40239d31a63ffbbd80bf900d`), l'exporte au format ONNX, puis le quantifie |
| `build_model.py --model multi` | Télécharge `onnx/model.onnx` de la révision figée (470 Mo, SHA-256 `ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665`), puis le quantifie |
| `export_vocab.py --model <id>` | Convertit le `tokenizer.json` du modèle en `vocab.bin` (§3.2) |
| `gen_tokenizer_golden.py --model <id>` | Produit `testdata/tokenizer_golden_<id>.jsonl` à partir des 1 000 phrases versionnées |
| `gen_embedding_golden.py --model <id>` | Produit `testdata/embedding_golden_<id>.json` à partir des 50 textes versionnés |
| `verify.py` | Contrôles de qualité ci-dessous |
| `pack_model.py --model <id>` | Produit l'archive `.smpack` (§5.2) : ZIP sans compression, entrées `MODEL_MANIFEST.json`, `vocab.bin`, `model.onnx`, date fixe (archive reproductible) |
| `gen_sentences.py` | Produit les phrases sources versionnées de `tools/model/data/` (générateur déterministe, valeurs fictives) |
| `compare_embedding_golden.py` | Workflow `model` : compare les vecteurs régénérés aux vecteurs committés (identifiants identiques, cosinus ≥ 0,99) |

Quantification : `onnxruntime.quantization.quantize_dynamic(..., weight_type=QuantType.QInt8,
per_channel=False, reduce_range=False)`.

Contrôles de qualité, pour chaque modèle (échec du script sinon) :

- cosinus moyen entre vecteurs du modèle d'origine et du modèle quantifié ≥ 0,98 sur 200 phrases ;
- sur le jeu d'évaluation de la recherche, Rappel@5 du modèle quantifié ≥ Rappel@5 d'origine − 0,02
  (contrôle ajouté par le ticket M5-07, quand le jeu d'évaluation existe).

Le modèle `fr` est exporté par `torch.onnx.export` (exportateur TorchScript, opset 17) : entrées
`input_ids` et `attention_mask`, sortie `last_hidden_state`, axes de lot et de séquence dynamiques. Le
modèle `multi` déclare en plus `token_type_ids`. La construction est reproductible : deux constructions
donnent les mêmes empreintes.

Chaque jeu contient `model.onnx`, `vocab.bin` et `MODEL_MANIFEST.json` (identifiant, révision source,
empreintes des sorties, versions des outils). Les empreintes attendues de `model.onnx` et `vocab.bin`, et
celles des jeux de référence de `testdata/`, sont versionnées dans `engine/embedding/model.lock.json`.

**Arithmétique int8.** Sur un processeur x86 sans VNNI, les noyaux int8 (u8s8) d'ONNX Runtime saturent :
les vecteurs s'écartent de ceux d'un téléphone (arithmétique exacte des instructions ARM) d'un cosinus
d'environ 0,995, mesuré sur un exécuteur GitHub. Les vecteurs de référence de `testdata/` sont donc
produits sur une machine ARM ou x86 avec VNNI (le générateur refuse les autres), et toute comparaison
faite sur un exécuteur x86 quelconque (workflow `model`, évaluation de la recherche sur JVM) tolère cet
écart. `reduce_range=True` éviterait la saturation au prix d'une précision moindre sur les téléphones :
écarté.

Valeurs relevées à la construction (M0-10) :

| | `fr` | `multi` |
|---|---|---|
| `model.onnx` quantifié | 36,4 Mo | 118,1 Mo |
| `vocab.bin` | 0,46 Mo | 4,1 Mo |
| Archive `.smpack` | 36,8 Mo | 122,2 Mo |
| Cosinus moyen origine / quantifié (minimum), 200 phrases | 0,9887 (0,9766) | 0,9888 (0,9788) |

**Les artefacts binaires ne sont jamais committés.** Intégration au build :

- `:engine:embedding:verifyModel` vérifie que le jeu `fr` est présent dans
  `engine/embedding/src/main/assets/model/fr/` et conforme à `model.lock.json` ; sinon message indiquant
  la commande à lancer (`python tools/model/build_model.py --model fr --install`).
- Le jeu `multi` n'est requis que pour construire le lot d'application (`bundle`) : il alimente le
  module de pack d'assets `:modelpack_multi`.
- La propriété `-PskipModel` désactive la vérification pour les tâches qui n'ont pas besoin du modèle
  (tests JVM, lint). Les tests JVM utilisent `FakeEmbeddingEngine`.
- En CI, les artefacts sont mis en cache avec `model.lock.json` comme clé.

## 3. Tokenizer

Implémentation en Kotlin pur de l'algorithme Unigram de SentencePiece, équivalente au tokenizer de
référence. Elle est **indépendante du modèle** : elle charge le `vocab.bin` du modèle actif.

```kotlin
interface Tokenizer {
    fun encode(text: String, maxTokens: Int): Encoding
}

class Encoding(val inputIds: IntArray, val attentionMask: IntArray)
```

### 3.1 Algorithme

Implémentation : `UnigramTokenizer`, `Vocabulary` et `UnigramNormalizer`, paquet
`fr.corsicajack.seekmind.engine.embedding.tokenizer`. Le tokenizer est sans état et partageable entre threads.

1. **Normalisation** (approximation du jeu de règles `nmt_nfkc`, établie point de code par point de code
   contre la référence) :
   - caractères de contrôle supprimés : U+0001 à U+0008, U+000B, U+000E à U+001F, U+007F, U+008F,
     U+009F (U+0000 et les autres contrôles C1, dont U+0085, sont conservés, comme par la référence) ;
   - remplacés par une espace : tabulation, saut de ligne, saut de page, retour chariot, U+1680,
     U+200B à U+200F (espaces et liants de largeur nulle), U+2028, U+2029, U+2581, U+FEFF, U+FFFD ;
   - NFKC sur le reste (les espaces insécables, fines et idéographiques deviennent ainsi une espace),
     sauf pour quelques caractères ajoutés à Unicode après la compilation du jeu de règles, que la
     référence laisse tels quels (par exemple U+FF5E) ;
   - toute suite d'au moins deux espaces réduite à une seule. Les espaces de bord sont **conservées** :
     la référence ne les retire pas (une espace finale produit un jeton `▁` isolé).
2. **Pré-découpage** : chaque espace devient `▁` (U+2581) ; un `▁` est ajouté en tête ; le texte est
   découpé en mots commençant chacun par `▁`. Un texte vide après normalisation ne donne aucun mot.
3. **Segmentation** de chaque mot par l'algorithme de Viterbi : parmi tous les découpages en jetons du
   vocabulaire, retenir celui dont la somme des scores est maximale (parcours et comparaisons stricts
   identiques à la référence, pour départager les égalités de la même façon). Une position sans jeton
   d'un seul caractère reçoit le jeton inconnu (`<unk>`, identifiant 3) avec le score
   `score minimal du vocabulaire − 10` ; des inconnus consécutifs sont fusionnés en un seul, dont le texte
   est recherché dans le vocabulaire (identifiant trouvé, sinon `<unk>`), comme le fait la référence.
4. **Identifiants** : la position du jeton dans le vocabulaire. Jetons spéciaux : `<s>` = 0,
   `<pad>` = 1, `</s>` = 2, `<unk>` = 3. Ils ne sont jamais reconnus dans le texte : un texte qui
   contient `</s>` est découpé comme du texte ordinaire (la référence, elle, le transformerait en jeton
   spécial).
5. **Gabarit** : `<s>` + jetons + `</s>`. **Troncature** : au plus `maxTokens` (au moins 2), `</s>` final
   conservé. Les mots étant indépendants, la segmentation s'arrête dès que le budget est atteint.
6. `attentionMask` : 1 pour chaque jeton.

### 3.2 Vocabulaire binaire

`vocab.bin`, petit-boutiste : magique `SMV1`, nombre de jetons (int32), puis pour chaque jeton, dans
l'ordre des identifiants : longueur en octets (uint16), octets UTF-8, score (float32). 0,46 Mo
pour `fr`, 4,1 Mo pour `multi`.

Chargement en structures compactes : un tableau d'octets unique, un tableau de positions, un tableau de
scores, et une table de hachage à adressage ouvert (hachage 64 bits des octets du jeton → identifiant,
vérification des octets à chaque succès). **Budget mémoire : 16 Mo** pour le plus grand vocabulaire.
Pas de `HashMap<String, Int>`. Hachage FNV-1a 64 bits, calculé octet par octet pendant la segmentation ;
taux de remplissage de la table au plus 1/2 ; les jetons spéciaux (0 à 3) n'y figurent pas. Le lecteur
refuse un fichier mal formé (magique, longueurs, octets en trop, jetons spéciaux absents de 0 à 3).

Mesures (M4-03, JVM) : structures de 1,0 Mo pour `fr`, 6,4 Mo pour `multi` (8 Mo de croissance du tas
au chargement).

### 3.3 Parité

Pour **chaque** modèle, le tokenizer doit produire exactement les mêmes identifiants que la référence
Python sur au moins **99,5 %** des 1 000 phrases de `testdata/tokenizer_golden_<id>.jsonl`. Les écarts
restants sont listés dans le rapport de test (`build/reports/tokenizer/parity-<id>.txt`). Les tests JVM lisent
le `vocab.bin` produit par `tools/model` (`tools/model/out/<id>/`, ou l'asset installé pour `fr`) et
vérifient son empreinte contre `model.lock.json` ; sans vocabulaire, ils sont ignorés en local et
échouent en CI. Résultat (M4-03) : 1 000/1 000 phrases identiques pour chacun des deux modèles.

Limite connue de l'approximation : le jeu `nmt_nfkc` traite certaines suites de diacritiques combinants
par graphème, sans le réordonnancement canonique de NFKC (par exemple `é` + U+0330 reste tel quel, et
`e` + U+0330 + U+0301 devient U+1E1B seul). Sur 20 000 phrases altérées par insertion aléatoire de
caractères rares, 0,2 % (`fr`) et 0,3 % (`multi`) divergent, toutes pour cette raison (diacritiques
combinants isolés, jamos hangûl) ; aucun texte réaliste n'est concerné. Si le seuil n'est pas atteint (risque R-12), repli :
embarquer une bibliothèque native de tokenisation, par un ADR.

## 4. Moteur

```kotlin
interface EmbeddingEngine : AutoCloseable {
    val modelId: Int
    val dimensions: Int                                   // 384
    suspend fun embedPassages(texts: List<String>): List<FloatArray>   // ajoute "passage: "
    suspend fun embedQuery(text: String): FloatArray                   // ajoute "query: "
}

interface EmbeddingEngineProvider {
    /** Rend le moteur du modèle actif ; le modèle est chargé au premier appel. */
    suspend fun <T> use(block: suspend (EmbeddingEngine) -> T): T
}
```

Implémentation `OrtEmbeddingEngine` :

- **Chargement** ([prototype S4](../prototypes/S4-embeddings.md)) : le modèle `fr`, asset non compressé
  de l'APK, est **copié une fois** vers `noBackupFilesDir/model/fr/` (40 à 60 ms), puis ouvert **par son
  chemin** (`OrtEnvironment.createSession(path, options)`). La copie est refaite quand le
  `MODEL_MANIFEST.json` de l'asset diffère de celui de la copie. Raison : ONNX Runtime recopie les poids
  d'un tampon projeté, si bien que la projection (`FileChannel.map` → `createSession(ByteBuffer)`) coûte
  deux fois la taille du modèle en mémoire (61 à 70 Mo contre 27 à 35 Mo) ; elle ne sert que de repli si
  la copie est impossible. Un modèle de pack est ouvert par son chemin de fichier.
- **Options de session** : optimisation maximale, un thread inter-opérateurs, threads intra-opérateurs
  donnés par le gouverneur (2, ou 4 en charge), fournisseur d'exécution processeur.
- **Lots** : textes triés par longueur, sous-lots de 8, remplissage à la longueur du plus long du sous-lot
  (`<pad>`, masque à 0). Les entrées fournies sont celles que la session déclare (`input_ids`,
  `attention_mask`, et `token_type_ids` à zéro si elle l'exige).
- **Sortie** : moyenne masquée de `last_hidden_state`, puis normalisation L2.
- **Cycle de vie** : le fournisseur compte les utilisateurs ; la session est fermée 60 s après le
  dernier usage et sous pression mémoire. L'interface de recherche la retient tant
  qu'elle est au premier plan.
- Les appels sont sérialisés (un seul calcul à la fois dans le processus).

**Implémentation (M4-04)**, paquet `fr.corsicajack.seekmind.engine.embedding.engine` :

| Élément | Rôle |
|---|---|
| `OrtEmbeddingEngine` | Préfixes, tokenisation, tri par longueur, sous-lots, agrégation ; vecteurs rendus dans l'ordre des textes. Calcule sur le dispatcher `default`. |
| `Encoder`, `OrtEncoder` | Exécution du réseau sur un sous-lot rembourré (`PaddedBatch`) ; `OrtEncoder` lit la dimension dans la forme de sortie de la session. Le reste du moteur est testé sur JVM avec un encodeur factice. |
| `OrtEmbeddingEngineFactory` | Charge `vocab.bin` et la session (`OrtModelLoader`) d'un `LoadableModel` : `ModelSpec` (qui donne le `modelId`) et `ModelSource` (`Bundled("model/fr")` ou `Pack(ModelFiles)`). |
| `ActiveModel` | Le modèle à charger à la prochaine session ; `BuiltInModels.bundledFr` jusqu'au catalogue de M4-07. |
| `SharedEmbeddingEngineProvider` | Implémente `EmbeddingEngineProvider` : chargement au premier `use`, rechargement si le modèle actif ou le nombre de threads (`EmbeddingThreads`, fourni par le gouverneur) a changé, `retain()` (poignées comptées), `release()` (fermeture après le calcul en cours), fermeture après `idleCloseMs`. Un bloc passé à `use` ne doit pas rappeler `use`. |
| `MemoryPressureRelease` | `ComponentCallbacks2` qui appelle `release()` sur `TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_CRITICAL`, les niveaux `TRIM_MEMORY_BACKGROUND` et au-delà, et `onLowMemory`. Les niveaux `RUNNING_*` ne sont plus envoyés à partir de l'API 34 : les niveaux d'arrière-plan sont alors le seul signal. |

## 5. Packs de langue

### 5.1 Catalogue et modèle actif

```kotlin
data class ModelSpec(
    val id: String,              // "fr", "multi"
    val modelId: Int,            // identifiant enregistré avec chaque vecteur (§7)
    val languages: Set<String>,  // codes de langue ; vide = toutes
    val sizeBytes: Long,
    val delivery: Delivery,      // BUNDLED ou PACK
    val packName: String?,       // nom du pack d'assets
)

interface ModelCatalog {
    val models: List<ModelSpec>
    val active: StateFlow<ModelSpec>
    fun state(id: String): Flow<PackState>     // NotInstalled, Downloading(progress), Installed, Failed
    suspend fun activate(id: String)           // change le modèle actif ; déclenche le recalcul des vecteurs
}

interface ModelPackInstaller {
    suspend fun install(spec: ModelSpec): Flow<PackState>
    suspend fun remove(spec: ModelSpec)
    fun location(spec: ModelSpec): ModelFiles?   // chemins de model.onnx et vocab.bin si installé
}
```

Le modèle actif est un réglage (`activeModelId`). Par défaut : `fr`.

### 5.2 Installation

| Installateur | Quand | Mécanisme |
|---|---|---|
| `PlayModelPackInstaller` | Application installée depuis Google Play | Pack d'assets à la demande : `com.google.android.play:asset-delivery` 2.3.0 (`AssetPackManager.fetch`, suivi d'état, `getPackLocation`). C'est le Play Store qui télécharge ; la bibliothèque ne déclare aucune permission réseau. |
| `FileModelPackInstaller` | Builds de développement, tests automatisés, distribution hors Play | L'utilisateur choisit un fichier `.smpack` (archive contenant `model.onnx`, `vocab.bin`, `MODEL_MANIFEST.json`) dans le sélecteur de fichiers ; les empreintes sont vérifiées contre `model.lock.json` avant la copie dans le stockage privé. |

Le module `:modelpack_multi` (plugin `com.android.asset-pack`, livraison à la demande) contient le jeu
d'artefacts `multi`.

Règles :

- Le téléchargement n'est **jamais** lancé sans une action de l'utilisateur.
- Au-delà du seuil de données mobiles fixé par Google Play, la confirmation système est affichée.
- Les fichiers d'un pack sont vérifiés (empreintes) avant toute activation.
- Un pack installé peut être supprimé ; s'il était actif, le modèle `fr` redevient actif.

### 5.3 Changer de modèle

`ModelCatalog.activate(id)` :

1. vérifie que les fichiers sont présents et intègres ;
2. enregistre le nouveau `activeModelId` ;
3. ferme la session en cours ;
4. déclenche `VectorStore.resetForModel(modelId)` : vecteurs vidés, fragments remis en attente
   ([indexation §7.3](indexation.md)) ; les lots d'embeddings reconstruisent l'index en arrière-plan.

Pendant la reconstruction, la recherche par mots-clés fonctionne normalement et l'interface indique
l'avancement. Le changement de modèle demande une confirmation qui annonce ce délai.

### 5.4 Quand proposer le pack multilingue

- À la fin de l'accueil, si aucune des langues du téléphone n'est le français.
- Dans Réglages → Langues de recherche, à tout moment.

Le texte dit ce que le pack change : « La recherche par mots-clés fonctionne dans toutes les langues.
Pour chercher aussi *par le sens* dans d'autres langues que le français, ajoutez le pack multilingue (118 Mo). »

## 6. Codage des vecteurs

`VectorCodec`, Kotlin pur, commun à tous les modèles.

| Forme | Calcul | Taille | Usage |
|---|---|---|---|
| int8 | `clamp(round(v[i] × S), −127, 127)` avec l'échelle fixe `S = int8Scale` | 384 octets | Rescore précis |
| Binaire | bit `i` = 1 si `v[i] > 0` ; 6 mots de 64 bits, petit-boutiste | 48 octets | Présélection par distance de Hamming |

Les composantes d'un vecteur normalisé de dimension 384 valent typiquement ±0,05 : multiplier par 127
gaspillerait la plage. L'échelle de départ est **`S = 400`** (plage utile ±0,32) ; le ticket M4-05 la
calibre pour que moins de 0,1 % des composantes soient écrêtées sur les vecteurs des 1 000 phrases de référence.

Similarité de rescore entre une requête en flottants `q` et un document int8 `d` :
`(Σ q[i] × d[i]) / S`, approximation du cosinus.

`round` arrondit au pair le plus proche (comme NumPy) : le code de `−v` est l'opposé de celui de `v`.
Dans le code binaire, le bit `i` est le bit `i mod 8` de l'octet `i / 8`, ce qui donne bien les octets de
6 mots de 64 bits petit-boutistes (`VectorCodec.words`).

## 7. Identifiant de modèle

Chaque entrée du catalogue porte un `modelId` entier, enregistré avec chaque vecteur. Il est incrémenté
dès que change l'un de : fichier du modèle, vocabulaire, règles de tokenisation, préfixes, longueurs
maximales, échelle `S`. Valeurs initiales : `fr` = 1, `multi` = 2. Un vecteur dont le `modelId` n'est
pas celui du modèle actif n'est jamais utilisé par la recherche.

## 8. Cibles de performance

À mesurer au prototype M0-09 puis à tenir en M4-04. Les deux modèles ont le même coût de calcul (même
encodeur) ; ils diffèrent par la mémoire et le temps de chargement.

| Mesure | Appareil moyen | Appareil bas |
|---|---|---|
| Chargement de la session, modèle `fr` | ≤ 0,5 s | ≤ 1,5 s |
| Chargement de la session, modèle `multi` | ≤ 1 s | ≤ 2,5 s |
| Requête (≤ 16 jetons) | ≤ 40 ms | ≤ 120 ms |
| Passage de 256 jetons, 2 threads | ≤ 150 ms | ≤ 500 ms |
| Mémoire du processus, session `fr` chargée | ≤ 150 Mo | ≤ 150 Mo |
| Mémoire du processus, session `multi` chargée | ≤ 300 Mo | ≤ 300 Mo |

Si l'appareil bas dépasse ces cibles d'un facteur deux, le ticket M10-02 isole le moteur dans un
processus dédié.

Mesures du moteur (M4-04, `OrtEmbeddingEngineDeviceTest`, émulateurs x86_64 de la CI, exécution
`device-tests` 43440665786, API 30 / 34 / 36) — moteur complet, tokenizer Kotlin compris, 2 threads :

| Mesure | `fr` | `multi` |
|---|---|---|
| Chargement de la session (première fois ; vocabulaire compris) | 152 / 257 / 212 ms | 216 / 416 / 359 ms |
| Requête courte, médiane | 5,8 / 10,2 / 16,6 ms | 5,8 / 10,4 / 8,9 ms |
| Passage tronqué à 256 jetons, médiane | 97 / 161 / 151 ms | 92 / 171 / 140 ms |
| Lot de 8 passages de 256 jetons, médiane | 779 / 1 347 / 1 149 ms | 722 / 1 436 / 1 064 ms |
| PSS ajoutée par la session | 36 / 35 / 28 Mo | 131 / 95 / 131 Mo |
| PSS totale du processus de test | 207 / 178 / 189 Mo | 279 / 258 / 279 Mo |
| Cosinus le plus bas avec `embedding_golden_fr.json`, texte par texte (en un lot) | 0,9971 (0,9958) | — |

Les cibles de latence et de chargement sont tenues, sauf le passage de 256 jetons sur les émulateurs
API 34 et 36, au niveau de la cible (151 à 171 ms pour 150 ms), comme au prototype S4. La PSS totale
compte le processus d'instrumentation : la session `fr` n'ajoute que 28 à 36 Mo. Ces deux points sont
à confirmer sur le téléphone, application seule (M10-01).

## 9. Configuration

```kotlin
data class EmbeddingConfig(
    val maxPassageTokens: Int = 256,
    val maxQueryTokens: Int = 64,
    val subBatchSize: Int = 8,
    val int8Scale: Float = 400f,
    val idleCloseMs: Long = 60_000,
    val threadsDefault: Int = 2,
    val threadsCharging: Int = 4,
    val defaultModel: String = "fr",
)
```

## 10. Tests attendus

- **JVM** : tokenizer contre les deux jeux de référence (seuil 99,5 %) ; cas limites (chaîne vide,
  émojis, caractères inconnus, troncature) ; `VectorCodec` (aller-retour, écrêtage, bits) ; chargement
  de `vocab.bin` ; `ModelCatalog` avec un installateur factice (états, activation, suppression du pack actif).
- **Instrumenté** : `OrtEmbeddingEngine` contre `testdata/embedding_golden_fr.json` (cosinus ≥ 0,99 pour
  chaque texte) ; normes égales à 1 ; préfixes appliqués ; fermeture après inactivité ; mesures du §8 ;
  `FileModelPackInstaller` (archive valide, empreinte incorrecte, archive incomplète) ; changement de
  modèle → vecteurs recalculés, recherche lexicale intacte.
- **Outils** : `pytest` sur `tools/model` (format du vocabulaire, empreintes, contrôles de qualité).
- **Validation manuelle** : téléchargement du pack par Google Play sur la piste de test interne.
