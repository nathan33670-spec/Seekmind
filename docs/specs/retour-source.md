# Spec — Retour à la source (`engine:source`)

| | |
|---|---|
| **Exigences** | EF-SRC-01 à 05 |
| **Tickets** | M7-01 à M7-03 |

## 1. Ce qui est possible, et ce qui ne l'est pas

Un service d'accessibilité ne voit pas l'Intent qui a ouvert un écran d'une autre application, et une
application ne peut pas lancer une activité non exportée d'une autre. Le « retour exact » n'existe donc
que lorsque l'écran s'identifie par une **URL** : c'est le cas des navigateurs. Ailleurs, SeekMind
propose le meilleur retour disponible et **le dit dans le libellé du bouton** (EF-SRC-03).

| Niveau | Signification | Libellé (FR) |
|---|---|---|
| `EXACT_URL` | La page d'origine | « Ouvrir la page » |
| `APP_LINK` | Un écran précis de l'application, déduit du contenu (profil, fiche) | « Ouvrir dans {application} » |
| `APP_SEARCH` | Une recherche dans l'application, pré-remplie | « Rechercher dans {application} » |
| `APP_ONLY` | L'application, sur son écran d'accueil | « Ouvrir {application} » |
| `UNAVAILABLE` | Application désinstallée | Bouton désactivé : « Application désinstallée » |

## 2. Résolution tardive

Le brief prévoyait d'enregistrer un Intent sérialisé avec chaque capture. La v1 enregistre les
**éléments** (paquet, activité, titre, URL, texte) et construit l'Intent **à l'affichage**. Même
fonction, deux avantages : les règles de résolution s'améliorent rétroactivement pour toutes les
captures déjà stockées, et aucun Intent figé ne devient invalide après une mise à jour de l'application source.

```kotlin
data class SourceRef(
    val level: SourceLevel,
    val intentUri: String?,          // Intent.toUri(Intent.URI_INTENT_SCHEME), null si UNAVAILABLE
    val appLabel: String?,
)

interface SourceResolver {
    fun resolve(capture: Capture): SourceRef
    fun linksIn(capture: Capture): List<String>      // URL http(s) présentes dans le texte (EF-SRC-05)
}

interface SourceLauncher {
    fun launch(context: Context, ref: SourceRef): LaunchResult    // Launched, Fallback(level), Failed
}
```

## 3. Chaîne de résolution

Le premier résolveur qui répond l'emporte.

| # | Résolveur | Condition | Intent | Niveau |
|---|---|---|---|---|
| 1 | `BrowserUrlResolver` | `capture.url` non nul | `ACTION_VIEW` sur l'URL, `setPackage(paquet du navigateur)` s'il est installé | `EXACT_URL` |
| 2 | `AppRuleResolver` | Une règle existe pour le paquet et ses variables sont disponibles | Selon la règle (§4) | `APP_LINK` ou `APP_SEARCH` |
| 3 | `LaunchAppResolver` | L'application est installée | `PackageManager.getLaunchIntentForPackage` | `APP_ONLY` |
| 4 | — | Sinon | — | `UNAVAILABLE` |

## 4. Règles par application

`engine/source/src/main/assets/source_rules.json` — données, pas du code : on ajoute une application
sans toucher au moteur.

```json
{
  "version": 1,
  "rules": [
    { "package": "com.google.android.youtube", "level": "APP_SEARCH",
      "uri": "https://www.youtube.com/results?search_query={title}", "requires": ["title"] },
    { "package": "com.instagram.android", "level": "APP_LINK",
      "uri": "https://www.instagram.com/{handle}/", "requires": ["handle"] }
  ]
}
```

Variables disponibles, toutes encodées pour une URL et bornées à 100 caractères :

| Variable | Valeur |
|---|---|
| `{title}` | `window_title`, sinon la première ligne du texte |
| `{handle}` | Premier identifiant de la forme `@nom` dans le texte (`@([A-Za-z0-9._]{2,30})`), sans l'arobase |
| `{firstLine}` | Première ligne du texte |

L'Intent est un `ACTION_VIEW` sur l'URI, avec `setPackage(paquet)`. Si l'application ne prend pas en
charge ce lien, le lanceur retombe sur le niveau suivant.

Règles initiales, **chacune à valider sur appareil** au ticket M7-02 (une règle non validée est retirée) :

| Application | Paquet | URI | Niveau |
|---|---|---|---|
| YouTube | `com.google.android.youtube` | `https://www.youtube.com/results?search_query={title}` | `APP_SEARCH` |
| Reddit | `com.reddit.frontpage` | `https://www.reddit.com/search/?q={title}` | `APP_SEARCH` |
| Instagram | `com.instagram.android` | `https://www.instagram.com/{handle}/` | `APP_LINK` |
| TikTok | `com.zhiliaoapp.musically` | `https://www.tiktok.com/@{handle}` | `APP_LINK` |
| X | `com.twitter.android` | `https://x.com/{handle}` | `APP_LINK` |
| Google Maps | `com.google.android.apps.maps` | `geo:0,0?q={title}` | `APP_SEARCH` |
| Spotify | `com.spotify.music` | `spotify:search:{title}` | `APP_SEARCH` |

Messageries et courriel (WhatsApp, Messages, Gmail) : aucun lien vers une conversation n'est
constructible à partir du contenu affiché ; niveau `APP_ONLY`.

## 5. Lancement

- Drapeau `FLAG_ACTIVITY_NEW_TASK`.
- `ActivityNotFoundException` ou `SecurityException` → essai du niveau suivant
  (`EXACT_URL` sans `setPackage` → navigateur par défaut ; `APP_LINK`/`APP_SEARCH` → `APP_ONLY`).
- Schémas admis dans un Intent construit : `http`, `https`, `geo`, et ceux qui figurent dans
  `source_rules.json`. Jamais `file`, `content`, `javascript` ni `intent`.
- Les liens extraits du texte (`linksIn`) ne sont ouverts que sur tap, et seulement en `http` ou `https`.

## 6. Détail d'une capture

Sous le bouton principal : « Copier le texte », « Partager », et la liste des liens détectés dans le
texte (cinq au plus).

## 7. Tests attendus

- **JVM (Robolectric)** : chaque résolveur ; ordre de la chaîne ; substitution et encodage des
  variables ; règle ignorée si une variable manque ; schémas refusés ; application désinstallée.
- **Bout en bout** : E2E-14 (lien vers `DeepLinkActivity` de l'application de test).
- **Validation manuelle** (M7-02) : tableau « application, règle, résultat observé » dans la PR.
