# Spec — OCR et captures d'écran en mémoire (`engine:ocr`)

| | |
|---|---|
| **Exigences** | EF-OCR-01 à 04, EF-PRIV-02, INV-03, ENF-BAT-01 |
| **Tickets** | M0-07 (prototype), M3-01 à M3-05 |
| **Voir aussi** | [ADR-0008](../adr/0008-detection-des-fenetres-securisees.md) |

## 1. Responsabilités

- Obtenir une image de l'écran **en mémoire vive** et garantir sa libération.
- Détecter les fenêtres sécurisées.
- Reconnaître le texte de l'image.
- Décider quand l'OCR vaut son coût, et le suspendre quand l'appareil est chaud ou à court d'énergie.

## 2. Capture d'écran en mémoire

```kotlin
interface ScreenshotSource {
    /**
     * Capture la fenêtre (API 34+) ou l'écran recadré sur la fenêtre (API 30–33), puis exécute [block]
     * avec un bitmap logiciel valide uniquement pendant l'appel.
     */
    suspend fun <T> withScreenshot(windowId: Int, windowBounds: IntRect, block: suspend (Bitmap) -> T): Shot<T>
}

sealed interface Shot<out T> {
    data class Ok<T>(val value: T) : Shot<T>
    data object SecureWindow : Shot<Nothing>
    data object RateLimited : Shot<Nothing>
    data class Failed(val code: Int) : Shot<Nothing>
}
```

Implémentation (`A11yScreenshotSource`) :

| API | Appel | Fenêtre sécurisée |
|---|---|---|
| 34 et plus | `takeScreenshotOfWindow(windowId, executor, callback)` | `onFailure(ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)` → `SecureWindow` |
| 30 à 33 | `takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`, puis recadrage sur `windowBounds` | Le système noircit le contenu sécurisé → heuristique d'image noire (§3.2) |

Cycle de vie de l'image, **toujours dans un `try/finally`** :

```kotlin
val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
try {
    // ML Kit lit les pixels : un bitmap matériel ne convient pas, on en fait une copie logicielle.
    val sw = requireNotNull(hw).copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
    try { return block(crop(sw)) } finally { sw.recycle() }
} finally {
    hw?.recycle()
    result.hardwareBuffer.close()
}
```

- Si le plus grand côté dépasse 1 600 px, l'image est réduite à 1 600 px avant l'OCR.
- Le système impose un intervalle minimal entre deux captures
  (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`) : 1 000 ms mesurés sous Android 11, 500 ms à partir
  d'Android 14 ([prototype S3](../prototypes/S3-accessibilite.md)). Un limiteur interne garantit au moins 1 000 ms entre
  deux demandes ; au-delà, `RateLimited`.
- **Interdits** (INV-03) : `Bitmap.compress`, écriture de fichier, `MediaStore`, cache, passage de
  l'image à un autre module. Le bitmap ne sort jamais de `block`.

## 3. Sonde de fenêtre sécurisée

```kotlin
interface SecureWindowProbe {
    suspend fun probe(meta: ScreenMeta, windowBounds: IntRect, needImage: Boolean): ProbeResult
}

sealed interface ProbeResult {
    data object Secure : ProbeResult
    data class NotSecure(val ocr: OcrOutcome?) : ProbeResult   // OCR effectué au passage si demandé
    data object Skipped : ProbeResult                          // politique : pas de sonde nécessaire
    data object Unknown : ProbeResult                          // échec technique de la capture
}
```

Une seule capture d'écran sert à la sonde et, si `needImage`, à l'OCR.

### 3.1 API 34 et plus

Sonde à **chaque** capture. `Shot.SecureWindow` → `Secure`. Sans besoin d'OCR, le tampon est fermé
immédiatement, sans copie.

### 3.2 API 30 à 33

Aucune API ne signale une fenêtre sécurisée ; le système en noircit le contenu dans la capture.

- **Quand sonder :** à chaque capture pour les paquets de `OcrConfig.probeAlwaysPackages` (navigateurs
  de `BrowserProfiles` et messageries à contenu éphémère) ; sinon une fois par couple
  `(paquet, activité)` et par 24 h, résultat mémorisé dans la table `secure_probe`.
- **Heuristique d'image noire :** échantillonner une grille de 24 × 24 pixels dans la fenêtre ; si au
  moins 98 % des échantillons ont une luminance inférieure à 8 sur 255 → `Secure`.
- Un résultat `Secure` est mémorisé 10 min pour le couple `(paquet, activité)`.

Un écran réellement noir est pris pour sécurisé : c'est l'erreur sans conséquence. Le cas inverse (fenêtre
sécurisée non détectée) ne peut venir que de la mémorisation de 24 h, d'où la sonde systématique pour
les applications dont l'attribut varie d'un écran à l'autre.

### 3.3 Résultat `Unknown`

Capture impossible (limite de débit, erreur interne) : pour les paquets de `probeAlwaysPackages`, la
capture est **rejetée** (`SECURE_WINDOW`) ; pour les autres, elle se poursuit sans OCR.

## 4. Reconnaissance

```kotlin
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrOutcome
}

data class OcrOutcome(val lines: List<OcrLine>, val durationMs: Long)
data class OcrLine(val text: String, val bounds: IntRect, val confidence: Float?)
```

- Bibliothèque : `com.google.mlkit:text-recognition` (modèle **embarqué**, script latin). Elle tire
  `com.google.android.gms:play-services-mlkit-text-recognition`, qui porte l'API publique
  (`TextRecognizerOptions`) ; l'artefact embarqué fournit le module local (modèle et bibliothèque
  native), qui est celui chargé. Est interdit : déclarer `play-services-mlkit-text-recognition` **sans**
  l'artefact embarqué, ou déclarer la méta-donnée `com.google.mlkit.vision.DEPENDENCIES` (téléchargement
  du modèle par les services Play).
- Un seul `TextRecognizer` pour le processus, fermé à la déconnexion du service.
- La `Task` de ML Kit est adaptée en fonction suspendue par `suspendCancellableCoroutine` (pas de
  dépendance supplémentaire).
- Filtrage : lignes d'au moins 3 caractères alphanumériques ; confiance ≥ 0,45 lorsqu'elle est fournie.

### 4.1 Télémétrie de ML Kit

ML Kit dépend de `com.google.android.datatransport`, qui déclare `INTERNET` et planifie l'envoi de
statistiques d'usage à Google. SeekMind n'en veut dans aucune variante :

- variante `offline` : la permission est retirée du manifeste fusionné (INV-01) ;
- variante `ads` : la permission existe pour la publicité ; laisser ML Kit s'en servir romprait le
  silence réseau (EF-MON-10) et l'absence de télémétrie (INV-08).

Réglage retenu, établi par le [prototype S2](../prototypes/S2-mlkit-sans-telemetrie.md) : **option B**,
dans les deux variantes. Le manifeste commun retire, par `tools:node="remove"`, les trois composants de
transport : `TransportBackendDiscovery`, `JobInfoSchedulerService` et
`AlarmManagerSchedulerBroadcastReceiver`. Sans `TransportBackendDiscovery`, aucun service d'envoi n'est
enregistré : les statistiques de ML Kit sont écartées avant toute planification.

L'option A (retrait de la seule permission `INTERNET`, variante `offline`) a été mesurée et écartée : en
variante `ads`, ML Kit émettait environ 2 Ko après 100 reconnaissances ; en variante `offline`, l'envoi
échouait en laissant une quarantaine d'erreurs `TransportRuntime` dans le journal.

La variante `offline` retire en outre `ACCESS_NETWORK_STATE`, qu'aucun composant restant n'utilise.

Chaque montée de version de ML Kit rejoue le test `MlKitTelemetryPrototypeTest` (aucun octet émis,
aucune erreur de transport). Si une version future rendait l'option B instable (risque R-11), le moteur
de repli est un modèle de reconnaissance exécuté par ONNX Runtime, déjà embarqué ; `OcrEngine` isole ce
choix.

## 5. Quand lancer l'OCR

```kotlin
class OcrPolicy(private val config: OcrConfig, private val governor: ThermalGovernor, private val clock: Clock) {
    fun shouldOcr(meta: ScreenMeta, extraction: TreeExtraction, treeSimHash: Long): Boolean
}
```

L'OCR est lancé si **toutes** ces conditions sont vraies :

| Condition | Détail |
|---|---|
| L'écran en a besoin | `textChars < 80` **ou** (`textCoverage < 0,15` **et** `textChars < 600`) |
| L'écran n'a pas déjà été lu | `(paquet, treeSimHash)` absent du mémo des 20 derniers OCR |
| Le gouverneur l'autorise | `governor.state.value.allowsOcr` |
| Le budget le permet | ≥ 5 s depuis le dernier OCR, ≤ 6 par minute, ≤ 400 par jour |

Les seuils sont des valeurs de départ ; le ticket M3-04 les ajuste sur dix applications réelles et
consigne les mesures.

## 6. Gouverneur thermique et énergétique

```kotlin
interface ThermalGovernor {
    val state: StateFlow<GovernorState>
}

data class GovernorState(
    val allowsOcr: Boolean,
    val allowsEmbedding: Boolean,
    val embeddingThreads: Int,
    val reason: ThrottleReason?,     // THERMAL, POWER_SAVE, LOW_BATTERY
)
```

| Signal | Source | Effet |
|---|---|---|
| État thermique ≥ `THERMAL_STATUS_MODERATE` | `PowerManager.addThermalStatusListener` | OCR et embeddings suspendus |
| Économie d'énergie | `PowerManager.isPowerSaveMode`, `ACTION_POWER_SAVE_MODE_CHANGED` | OCR et embeddings suspendus |
| Batterie < 15 % hors charge | `ACTION_BATTERY_CHANGED` | OCR et embeddings suspendus |
| En charge | `ACTION_BATTERY_CHANGED` | `embeddingThreads = 4` (sinon 2) |

Les récepteurs sont enregistrés dynamiquement, non exportés. Le gouverneur est partagé avec
`engine:index` (lots d'embeddings).

## 7. Fusion avec le texte de l'arbre

1. Forme de comparaison d'une ligne : minuscules, sans diacritiques, espaces réduits.
2. Une ligne d'OCR est écartée si sa forme de comparaison est contenue dans le texte de l'arbre, ou si
   son recouvrement de mots (Jaccard) avec une ligne de l'arbre atteint 0,8.
3. Les lignes d'OCR restantes sont ajoutées après celles de l'arbre, de haut en bas.
4. `textSource` porte le bit OCR si au moins une ligne d'OCR est conservée.

## 8. Configuration

```kotlin
data class OcrConfig(
    val minIntervalMs: Long = 5_000,
    val maxPerMinute: Int = 6,
    val maxPerDay: Int = 400,
    val screenshotMinIntervalMs: Long = 1_000,
    val maxImageSidePx: Int = 1_600,
    val lowTextChars: Int = 80,
    val lowCoverage: Float = 0.15f,
    val lowCoverageMaxChars: Int = 600,
    val minLineConfidence: Float = 0.45f,
    val ocrMemoSize: Int = 20,
    val blackSampleGrid: Int = 24,
    val blackLumaMax: Int = 8,
    val blackRatio: Float = 0.98f,
    val probeCacheTtlMs: Long = 24 * 3_600_000L,
    val securePositiveTtlMs: Long = 600_000L,
    val lowBatteryPercent: Int = 15,
    val probeAlwaysPackages: Set<String> = emptySet(),   // rempli à l'injection : navigateurs + messageries
)
```

## 9. Tests attendus

- **JVM** : `OcrPolicy` (table de décision, budgets, mémo) ; fusion (§7) ; gouverneur (transitions) avec sources simulées.
- **Instrumenté** : `OcrEngine` sur trois images de référence générées à l'exécution (texte dessiné sur
  un `Canvas`) ; heuristique d'image noire ; libération : après 200 captures, pas de croissance de la
  mémoire native au-delà d'un seuil.
- **Bout en bout** : E2E-03, 05, 06.
- **Garde-fou** : `scripts/check-forbidden-apis.sh` interdit `compress(`, `FileOutputStream`, `MediaStore`
  et `ImageWriter` dans `engine/ocr` et `engine/capture`.
