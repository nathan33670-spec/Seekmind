# Spec — Actions contextuelles (`engine:actions`)

| | |
|---|---|
| **Exigences** | EF-ACT-01 à 06, INV-02 |
| **Tickets** | M9-01 à M9-05 |
| **Statut** | **Reporté en v1.1** (décision D-05) — non réalisé dans la première publication |

> Cette spécification est prête mais hors du périmètre de la v1. À sa réalisation : le module
> `engine:actions` est créé, l'étape de notification est ajoutée au pipeline de capture, et le texte de
> la divulgation passe en version 2 (nouvelle finalité : proposer des actions sur ce qui est affiché).

## 1. Principe

Quand un écran affiche une information « actionnable », SeekMind **propose** — par une pastille
discrète — de l'envoyer vers l'application adéquate. Rien n'est fait sans un tap de l'utilisateur ; le
tap se limite à lancer un Intent standard d'Android (INV-02). La détection est entièrement locale.

```kotlin
interface CaptureListener {                       // déclarée dans core:model
    fun onCapture(event: CaptureEvent)
}

data class CaptureEvent(val captureId: Long, val meta: ScreenMeta, val text: String, val isNew: Boolean)
```

`ActionsCoordinator` implémente `CaptureListener`. Il reçoit l'événement sur le thread de capture,
détecte sur `Dispatchers.Default`, puis affiche sur le thread principal. Il n'est appelé que pour des
écrans acceptés par la porte de confidentialité : aucune pastille sur un écran sensible.

## 2. Entités

```kotlin
sealed interface Entity {
    val span: IntRange
    data class DateTime(override val span: IntRange, val start: ZonedDateTime, val hasTime: Boolean) : Entity
    data class Address(override val span: IntRange, val text: String) : Entity
    data class Tracking(override val span: IntRange, val number: String, val carrier: Carrier?) : Entity
    data class Phone(override val span: IntRange, val number: String) : Entity
    data class Email(override val span: IntRange, val address: String) : Entity
    data class Url(override val span: IntRange, val url: String) : Entity
}

interface EntityDetector {
    suspend fun detect(text: String, now: ZonedDateTime, locale: Locale): List<Entity>
}
```

| Entité | Détection |
|---|---|
| Date et heure | `AbsoluteDateTimeExtractor`, Kotlin pur (§2.1) |
| Adresse postale | Classifieur de texte du système : `TextClassifier.generateLinks` restreint à `TYPE_ADDRESS`. Si le classifieur est absent (`TextClassifier.NO_OP`), aucune adresse n'est détectée. |
| Numéro de suivi | `TrackingDetector`, Kotlin pur (§2.2) |
| Téléphone, e-mail, URL | Classifieur du système, sinon `android.util.Patterns` |

Le texte soumis au classifieur est borné à `TextClassifier.getMaxGenerateLinksTextLength()`.

### 2.1 Dates

Formes reconnues, en français et en anglais :

- `14 juin`, `samedi 14 juin 2027`, `le 14 juin à 20h30`, `14/06`, `14/06/2027 20:30` ;
- `June 14`, `Sat, June 14, 2027 at 8:30 pm`, `06/14/2027` (selon la locale) ;
- heure seule adjacente à une date : `20h`, `20h30`, `20:30`, `8 pm`.

Une date sans année prend la prochaine occurrence à venir. Les dates passées sont ignorées.

### 2.2 Numéros de suivi

| Transporteur | Forme | Validation | Lien de suivi |
|---|---|---|---|
| Poste internationale (norme S10) | 2 lettres, 9 chiffres, 2 lettres | Chiffre de contrôle : somme des 8 premiers chiffres pondérés par 8, 6, 4, 2, 3, 5, 9, 7 ; clé = 11 − (somme mod 11), avec 10 → 0 et 11 → 5 | `https://www.laposte.fr/outils/suivre-vos-envois?code={n}` si le pays est `FR` |
| Colissimo | 1 chiffre, 1 lettre, 11 chiffres | Forme | `https://www.laposte.fr/outils/suivre-vos-envois?code={n}` |
| UPS | `1Z` + 16 caractères alphanumériques | Forme | `https://www.ups.com/track?tracknum={n}` |
| Amazon | `TBA` + 12 chiffres | Forme | Aucun : action « Copier » |
| Générique | 10 à 30 caractères alphanumériques dans les 60 caractères suivant un mot-clé (`suivi`, `colis`, `tracking`, `shipment`) | Au moins 6 chiffres | Aucun : action « Copier » |

Formes et liens à vérifier au ticket M9-01 ; un transporteur non vérifié n'offre que « Copier ».

## 3. De l'entité à l'action

```kotlin
data class SmartAction(val kind: ActionKind, val label: String, val intent: Intent)

interface ActionResolver {
    fun resolve(entity: Entity, context: ActionContext): SmartAction?    // null si aucune application ne répond
}
```

| Entité | Action | Intent |
|---|---|---|
| Date et heure | « Ajouter à l'agenda » | `ACTION_INSERT` sur `CalendarContract.Events.CONTENT_URI`, avec début, fin (début + 1 h) et titre (`window_title` ou première ligne). Aucune permission d'agenda n'est requise. |
| Adresse | « Voir sur la carte » | `ACTION_VIEW` sur `geo:0,0?q=<adresse encodée>` |
| Suivi | « Suivre le colis » ou « Copier le numéro » | `ACTION_VIEW` sur le lien du transporteur, ou copie dans le presse-papiers |
| Téléphone | « Appeler » | `ACTION_DIAL` sur `tel:` (compose le numéro, n'appelle pas) |
| E-mail | « Écrire » | `ACTION_SENDTO` sur `mailto:` |
| URL | « Ouvrir le lien » | `ACTION_VIEW` |

Une action n'est proposée que si `PackageManager` trouve une activité pour l'Intent.

## 4. Pastille

### 4.1 Quand la proposer

Une seule pastille par écran, pour l'entité la plus prioritaire qui passe son filtre :

| Priorité | Entité | Filtre de pertinence |
|---|---|---|
| 1 | Numéro de suivi | Toujours |
| 2 | Date et heure | Dans le futur, à moins de 18 mois, **et** (heure présente **ou** mot-clé d'événement à moins de 80 caractères : concert, rendez-vous, rdv, réservation, billet, vol, train, séance, event, booking, appointment, meeting, flight) |
| 3 | Adresse | Toujours |

Téléphone, e-mail et URL ne déclenchent jamais la pastille (trop fréquents) ; ils sont proposés dans le
détail d'une capture.

Pas de pastille si :

- le réglage « Actions contextuelles » est désactivé ;
- l'application au premier plan est la cible naturelle de l'action (agenda pour une date, cartes pour une adresse) ;
- la même entité (même valeur normalisée) a été proposée dans les 24 dernières heures ;
- une limite de fréquence est atteinte : 60 s entre deux pastilles, 10 par jour ;
- l'utilisateur a fermé trois pastilles de suite sans en utiliser : suspension de 24 h.

### 4.2 Fenêtre

- Ajoutée par le `WindowManager` du service avec le type `TYPE_ACCESSIBILITY_OVERLAY` : aucune
  permission de superposition n'est nécessaire.
- Drapeaux `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` : elle ne prend jamais le focus, ne bloque pas
  la saisie, ne recouvre pas la barre de navigation.
- Vue Android classique (mise en page XML) : icône, libellé sur une ligne (« Ajouter à l'agenda · sam.
  14 juin 20:30 »), croix de fermeture. Largeur ajustée au contenu, centrée en bas, 96 dp au-dessus du bord.
- Durée d'affichage : 6 s, puis fondu. Retirée immédiatement si la fenêtre active change.
- Tap sur le libellé : lance l'Intent (`FLAG_ACTIVITY_NEW_TASK`) et retire la pastille. Tap sur la croix : retire.
- Compatible TalkBack : la pastille est annoncée et activable.

### 4.3 Dans l'application

L'écran de détail d'une capture détecte toutes les entités de son texte à l'ouverture et les présente
en pastilles d'action (EF-ACT-04), sans limite de fréquence.

## 5. Configuration

```kotlin
data class ActionsConfig(
    val pillDurationMs: Long = 6_000,
    val minIntervalMs: Long = 60_000,
    val maxPerDay: Int = 10,
    val sameEntityCooldownMs: Long = 24 * 3_600_000L,
    val dismissStreakForPause: Int = 3,
    val pauseAfterDismissMs: Long = 24 * 3_600_000L,
    val maxFutureMonths: Int = 18,
    val eventKeywordWindow: Int = 80,
)
```

## 6. Tests attendus

- **JVM** : `AbsoluteDateTimeExtractor` (table FR/EN, année implicite, dates passées) ;
  `TrackingDetector` (formes valides, chiffre de contrôle S10, faux positifs : numéros de téléphone,
  de commande) ; priorités et filtres de pertinence ; limites de fréquence avec horloge simulée.
- **Robolectric** : Intents produits par `ActionResolver`.
- **Bout en bout** : E2E-16 ; pastille absente quand le réglage est désactivé.
- **Validation manuelle** : la pastille ne gêne ni la saisie ni la navigation gestuelle sur trois applications.
