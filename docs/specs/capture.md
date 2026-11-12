# Spec — Capture (`engine:capture`)

| | |
|---|---|
| **Exigences** | EF-CAP-01 à 07, ENF-PERF-01, ENF-PERF-02, ENF-REL-01 |
| **Dépend de** | `engine:privacy`, `engine:ocr`, `engine:index` |
| **Tickets** | M2-01 à M2-08 |

## 1. Responsabilités

Recevoir les événements d'accessibilité, décider quand l'écran est stable, lire le texte de la fenêtre
active, et orchestrer le pipeline jusqu'au stockage. Le module **lit** ; il n'exécute aucune action
(INV-02) et ne conserve aucune référence à l'arbre au-delà d'une capture.

Hors périmètre : la décision de confidentialité (`engine:privacy`), l'OCR (`engine:ocr`), la
déduplication et l'écriture (`engine:index`).

## 2. Service

```kotlin
@AndroidEntryPoint
class SeekMindAccessibilityService : AccessibilityService() {
    @Inject lateinit var router: EventRouter

    override fun onServiceConnected() = router.attach(this)

    // Thread principal : uniquement des copies de primitives, puis délégation. ≤ 2 ms.
    override fun onAccessibilityEvent(event: AccessibilityEvent) =
        router.onEvent(event.eventType, event.packageName?.toString(), event.className?.toString(), event.windowId)

    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { router.detach(); return super.onUnbind(intent) }
}
```

- Configuration XML : [05 §3](../05-conformite-play-store.md#3-engagements-de-conception). Elle n'est pas modifiée à l'exécution.
- L'objet `AccessibilityEvent` n'est jamais conservé ni transmis à un autre thread.
- `EventRouter` écarte sur le thread principal, par simple test d'appartenance à un ensemble en mémoire,
  les événements des paquets toujours ignorés (§5.3), et, par lecture de deux champs `@Volatile`, ceux qui
  arrivent sans consentement enregistré ou pendant une pause (§7). Tant que l'ensemble des paquets
  ignorés et les réglages n'ont pas été lus une première fois, il écarte tout. Les autres événements
  passent au thread `sm-capture`, où `CaptureLoop` réarme l'anti-rebond.
- Un événement `TYPE_WINDOW_STATE_CHANGED` met à jour, sur `sm-capture`, la fenêtre courante
  `(package, className, windowId)` et l'activité (§5.1), et remet à zéro le ralentissement adaptatif (§3.2).
- `AndroidCaptureEnvironment` publie l'état de connexion, calcule l'ensemble des paquets ignorés et suit
  les réglages, le tout sur `sm-capture` : le thread principal ne lit ni le disque ni la base.
- Un écran stable est confié à `StableScreenHandler.onStable(): PipelineEvent`, implémenté par
  `CapturePipeline` (§6). Une issue `Deduplicated` alimente le ralentissement adaptatif. Tant qu'aucune
  implémentation n'est liée, un écran stable ne lit rien.

## 3. Stabilité de l'écran

### 3.1 Anti-rebond

Kotlin pur, piloté par une horloge injectée ; exécuté sur le thread `sm-capture`.

```kotlin
class Debouncer(private val config: CaptureConfig, private val clock: Clock, private val timer: Timer) {
    private var firstEventAt: Long? = null
    private var lastFireAt: Long = 0

    fun onEvent() {
        val now = clock.elapsedRealtime()
        val first = firstEventAt ?: now.also { firstEventAt = it }
        val deadline = minOf(now + config.quietMs, first + currentMaxWaitMs())
        timer.schedule(maxOf(deadline, lastFireAt + config.minIntervalMs)) { fire() }
    }

    private fun fire() { firstEventAt = null; lastFireAt = clock.elapsedRealtime(); onStable() }
}
```

- `quietMs` = 1 500 : délai de calme. Chaque événement le réarme.
- `maxWaitMs` = 8 000 : si l'écran change sans cesse (vidéo, animation), on capture quand même.
- `minIntervalMs` = 1 000 : jamais deux captures à moins d'une seconde.
- Si une capture est en cours quand le délai expire, le déclenchement est mémorisé (un seul) et rejoué à la fin.

### 3.2 Ralentissement adaptatif

Quand trois captures consécutives de la même fenêtre sont dédupliquées (rien de nouveau),
`currentMaxWaitMs()` double, puis double encore à chaque nouvelle capture dédupliquée, jusqu'à 60 000. Il revient à `maxWaitMs` dès qu'une capture est stockée ou
que la fenêtre change. Effet : une vidéo regardée dix minutes coûte une dizaine de lectures, pas soixante-quinze.

## 4. Lecture de l'arbre

### 4.1 Abstraction

`TreeExtractor` ne connaît que `UiNode` ; `A11yUiNode` enveloppe `AccessibilityNodeInfo`. Les tests JVM
construisent des arbres factices.

```kotlin
interface UiNode {
    val className: String?
    val viewId: String?            // viewIdResourceName
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isVisibleToUser: Boolean
    val isPassword: Boolean
    val isEditable: Boolean
    val isClickable: Boolean
    val isFocused: Boolean
    val isHeading: Boolean
    val bounds: IntRect            // en coordonnées écran
    val childCount: Int
    fun child(index: Int): UiNode?
    fun findByViewId(viewId: String): List<UiNode>   // "<paquet>:id/<nom>", dans le sous-arbre (§5.2)
    fun release()                  // recycle() avant l'API 33, sans effet ensuite
}

data class TextLine(val text: String, val bounds: IntRect, val fromDescription: Boolean)

data class TreeExtraction(
    val lines: List<TextLine>,
    val headings: List<String>,
    val hasPasswordField: Boolean,
    val viewIds: Set<String>,          // identifiants rencontrés (pour les marqueurs de navigation privée)
    val nodeCount: Int,
    val truncated: Boolean,
    val textChars: Int,
    val textCoverage: Float,           // aire cumulée des lignes de texte / aire de la fenêtre, bornée à 1
    val windowBounds: IntRect,
    val durationMs: Long,
    val pageTitle: String? = null,     // titre de la page d'un navigateur (§5.2), 200 caractères au plus
)
```

### 4.2 Algorithme

Parcours en profondeur, préfixe, itératif (pile explicite), sur le thread `sm-capture`.

1. Racine : `service.rootInActiveWindow`. À partir de l'API 33, utiliser la surcharge avec
   `AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID`, et la même stratégie pour `getChild`,
   afin de regrouper les appels inter-processus. Racine nulle, ou sans nom de paquet → rejet `NO_ROOT`.
2. Fenêtre : si `root.window?.type != AccessibilityWindowInfo.TYPE_APPLICATION` → rejet `NOT_APP_WINDOW`.
3. Pour chaque nœud :
   - non visible (`!isVisibleToUser`) → ignoré, sous-arbre compris ;
   - `isPassword` → `hasPasswordField = true` et **arrêt immédiat** du parcours ;
   - `isEditable` → ignoré, sous-arbre compris (EF-PRIV-06) ;
   - `text` non vide → une `TextLine` ;
   - sinon `contentDescription` non vide **et** (nœud non cliquable **ou** `className` contenant `Image`)
     → une `TextLine` marquée `fromDescription`. Cette règle garde les textes alternatifs d'images et
     écarte les libellés de boutons (« Retour », « J'aime », « Partager ») ;
   - `isHeading` avec du texte → ajouté à `headings`.
4. Nettoyage d'une ligne : espaces normalisés, bornée à 2 000 caractères ; ignorée si elle contient
   moins de deux caractères alphanumériques ; ignorée si une ligne identique existe déjà dans l'écran.
5. Budgets, vérifiés tous les 50 nœuds :

   | Limite | Valeur | Effet |
   |---|---|---|
   | `maxNodes` | 1 500 | Arrêt, `truncated = true`, résultat conservé |
   | `maxDepth` | 40 | Sous-arbre ignoré |
   | `softBudgetMs` | 150 | Arrêt, `truncated = true`, résultat conservé |
   | `hardBudgetMs` | 400 | Abandon, rejet `TREE_TIMEOUT` |
   | `maxTextChars` | 12 000 | Arrêt, `truncated = true` |

6. Chaque nœud obtenu est libéré (`release()`) dès qu'il n'est plus utile, y compris sur abandon
   (`try/finally`). La racine reste à l'appelant : il y lit encore la barre d'adresse (§5.2) puis la libère.
7. Titre de la page : le texte (à défaut, la description) du premier nœud visible dont la classe est
   celle du titre de page d'un navigateur (`BrowserProfiles`, §5.2) devient `pageTitle`.

L'ordre préfixe correspond à l'ordre de lecture dans la grande majorité des mises en page ; aucun tri
géométrique n'est appliqué en v1.

## 5. Métadonnées de l'écran

```kotlin
data class ScreenMeta(
    val packageName: String,
    val activityName: String?,   // nom de classe qualifié si c'est une activité, sinon null
    val windowTitle: String?,
    val windowId: Int,
    val capturedAt: Long,        // époque, ms
)
```

### 5.1 Activité et titre

- `activityName` : le `className` du dernier `TYPE_WINDOW_STATE_CHANGED` qui désigne une activité.
  Plusieurs événements suivent l'ouverture d'un écran et le dernier peut nommer un conteneur de vues
  ([prototype S3](../prototypes/S3-accessibilite.md)) : un `className` n'est retenu que si
  `PackageManager.getActivityInfo(ComponentName(package, className))` réussit (résultat mis en cache,
  200 entrées). Les dialogues et vues ne sont pas des activités.
- `windowTitle` : `root.window?.title`, sinon le premier élément de `headings`, sinon `null`. Borné à 200 caractères.

### 5.2 URL des navigateurs

Table de configuration `BrowserProfiles` (ressource `res/raw/browser_profiles.json` du module, lue par
`org.json`). Chaque entrée donne les paquets, l'identifiant de la barre d'adresse et, si elle diffère de la
valeur commune `android.webkit.WebView`, la classe du nœud qui porte le titre de la page. Le nom de
cette classe est une donnée de la table : le code ne la nomme pas, le garde-fou `network-webview`
refusant toute mention de `WebView` dans les sources. Valeurs initiales, **à vérifier sur appareil** au
ticket M2-04 :

| Paquets | Identifiant de la barre d'adresse |
|---|---|
| `com.android.chrome`, `com.chrome.beta`, `com.chrome.dev`, `com.brave.browser`, `com.microsoft.emmx`, `com.vivaldi.browser`, `com.kiwibrowser.browser` | `url_bar` |
| `org.mozilla.firefox`, `org.mozilla.fenix`, `org.mozilla.focus` | `mozac_browser_toolbar_url_view` |
| `com.sec.android.app.sbrowser` | `location_bar_edit_text` |
| `com.opera.browser`, `com.opera.mini.native` | `url_field` |
| `com.duckduckgo.mobile.android` | `omnibarTextInput` |

Algorithme (`UrlExtractor`) :

1. Si le paquet n'a pas de profil → pas d'URL.
2. `root.findByViewId("<paquet>:id/<identifiant>")` (`findAccessibilityNodeInfosByViewId`), avant la
   lecture de l'arbre, sur la racine que l'appelant détient encore. Le premier nœud visible est lu
   directement, bien qu'il soit éditable ; s'il a le focus (l'utilisateur tape), l'URL est ignorée.
   Aucun nœud visible : barre masquée (étape 4).
3. Normalisation (`UrlNormalizer`, sans `android.net.Uri` pour être testable sur la JVM) : espaces de
   début et de fin retirés ; rejet si la chaîne contient un blanc, un caractère de contrôle ou aucun
   point (les adresses IPv6 littérales, sans point, sont donc rejetées) ; préfixe `https://` si aucun
   schéma ; seuls `http` et `https` sont acceptés ; hôte non vide, fait de lettres, chiffres, tirets et
   points, sans point en tête, en fin ni doublé ; port de 1 à 5 chiffres ; schéma et hôte en minuscules ;
   informations d'utilisateur (`nom:mot-de-passe@`) retirées ; rejet au-delà de 2 048 caractères.
4. Barre d'adresse masquée par le défilement : `UrlTracker` garde en mémoire, par paquet, la dernière
   URL lue et le titre de la page (`TreeExtraction.pageTitle`, §4.2) qui l'accompagnait. Si la barre est
   masquée, que le titre est le même et que l'URL a été lue il y a moins de `urlMemoryMs` (10 min),
   l'URL mémorisée est réutilisée ; sinon aucune URL. Une URL lue sans titre de page n'est pas mémorisée
   et efface la précédente. Barre ayant le focus ou texte rejeté : aucune URL, même mémorisée.

L'URL affichée par le navigateur peut être abrégée : le retour à la source est alors approximatif
(risque R-10).

### 5.3 Paquets toujours ignorés

Filtrés dès le thread principal, sans lecture : le paquet de SeekMind, `com.android.systemui`, le ou les
lanceurs (activités répondant à `ACTION_MAIN` + `CATEGORY_HOME`), les claviers (méthodes de saisie
activées, via `InputMethodManager`). L'ensemble est recalculé à la connexion du service et à chaque
`ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REMOVED`.

## 6. Orchestration

```kotlin
class CapturePipeline(/* dépendances injectées */) {
    val events: SharedFlow<PipelineEvent>     // sans contenu : identifiants et motifs seulement
    suspend fun onStable()
}

sealed interface PipelineEvent {
    data class Stored(val captureId: Long) : PipelineEvent
    data class Deduplicated(val captureId: Long) : PipelineEvent
    data class Dropped(val reason: DropReason) : PipelineEvent
}

enum class DropReason {
    CONSENT_MISSING, PAUSED, DEVICE_LOCKED, NO_ROOT, NOT_APP_WINDOW,
    APP_IGNORED, APP_EXCLUDED, APP_FAMILY_DISABLED, APP_SENSITIVE, APP_CATEGORY,
    PASSWORD_FIELD, PRIVATE_BROWSING, SECURE_WINDOW,
    TREE_TIMEOUT, TOO_SHORT, ERROR,
}
```

Étapes de `onStable()`, dans cet ordre — la première qui échoue termine la capture :

| # | Étape | Rejet possible |
|---|---|---|
| 1 | Consentement courant, mémorisation non en pause, appareil déverrouillé (`KeyguardManager.isKeyguardLocked`) | `CONSENT_MISSING`, `PAUSED`, `DEVICE_LOCKED` |
| 2 | Racine et type de fenêtre | `NO_ROOT`, `NOT_APP_WINDOW` |
| 3 | `PrivacyGate.checkPackage(meta)` — **avant toute lecture du contenu** : paquets ignorés, listes sensibles, règles de l'utilisateur, familles désactivées | `APP_*` |
| 4 | `TreeExtractor.extract(root)` | `TREE_TIMEOUT` |
| 5 | `PrivacyGate.checkTree(meta, extraction)` | `PASSWORD_FIELD`, `PRIVATE_BROWSING` |
| 6 | Sonde de fenêtre sécurisée et, si `OcrPolicy` le décide, OCR — une seule capture d'écran sert aux deux ([ocr §3](ocr.md)) | `SECURE_WINDOW` |
| 7 | Fusion des lignes de l'arbre et de l'OCR, URL, `PrivacyGate.redact(text)` | — |
| 8 | Longueur minimale (`minTextChars` = 20) | `TOO_SHORT` |
| 9 | `CaptureSink.ingest(RawCapture)` | — → `Stored` ou `Deduplicated` |

En v1.1, une dixième étape notifiera le module des actions contextuelles ; elle n'existe pas en v1.

Toute exception est interceptée au niveau de `onStable()`, comptée (`ERROR`) et n'atteint jamais le
service. Chaque issue incrémente un compteur de `metric_daily`.

```kotlin
data class RawCapture(
    val meta: ScreenMeta,
    val url: String?,
    val text: String,              // lignes jointes par '\n', déjà masquées
    val textSource: Int,           // bits : 1 = arbre, 2 = OCR
    val treeSimHash: Long,         // empreinte du texte de l'arbre seul (mémo OCR)
)
```

## 7. Pause

- État : `pausedUntil: Long?` dans les réglages (`null` = active, `Long.MAX_VALUE` = sans limite).
- Depuis l'application : 1 h, jusqu'à demain 6 h, sans limite, reprendre.
- `PauseTileService` : un tap bascule entre active et en pause sans limite ; le sous-titre indique l'état ;
  un appui long ouvre l'application.
- En pause, le service reste lié mais `EventRouter` écarte tout sur le thread principal.

## 8. État

```kotlin
sealed interface CaptureStatus {
    data object ServiceDisabled : CaptureStatus        // non activé dans les réglages système
    data object ConsentMissing : CaptureStatus
    data class Paused(val until: Long?) : CaptureStatus
    data class Active(val lastCaptureAt: Long?) : CaptureStatus
}
```

`CaptureStatusRepository` combine : la présence du service dans
`AccessibilityManager.getEnabledAccessibilityServiceList(...)`, l'état de connexion publié par le service,
le consentement et la pause. L'interface l'observe (EF-CAP-05).

Priorité, du premier état qui s'applique au dernier : `ConsentMissing` (sans consentement, l'interface
rouvre la divulgation avant tout passage par les réglages système), `ServiceDisabled`, `Paused`, `Active`.
`Paused.until` vaut `null` pour une pause sans limite (`pausedUntil = Long.MAX_VALUE`). `lastCaptureAt`
est publié par le pipeline (`onCaptureStored`).

## 9. Configuration

```kotlin
data class CaptureConfig(
    val quietMs: Long = 1_500,
    val maxWaitMs: Long = 8_000,
    val maxWaitCeilingMs: Long = 60_000,
    val minIntervalMs: Long = 1_000,
    val dedupStreakForBackoff: Int = 3,
    val maxNodes: Int = 1_500,
    val maxDepth: Int = 40,
    val softBudgetMs: Long = 150,
    val hardBudgetMs: Long = 400,
    val maxTextChars: Int = 12_000,
    val minTextChars: Int = 20,
    val urlMemoryMs: Long = 600_000,   // §5.2 : réutilisation de la dernière URL lue
)
```

## 10. Tests attendus

- **JVM** : `Debouncer` (calme, attente maximale, intervalle minimal, fusion des déclenchements,
  ralentissement adaptatif) avec horloge virtuelle ; `TreeExtractor` sur arbres factices (chaque règle du
  §4.2, chaque budget, libération des nœuds) ; `UrlExtractor` (table de cas).
- **Instrumenté / bout en bout** : E2E-01, 04, 07 à 11, 13, 18 de [06 §4](../06-qualite-tests-ci.md#4-tests-de-bout-en-bout).
- **Mesure** : ENF-PERF-01 et 02.
