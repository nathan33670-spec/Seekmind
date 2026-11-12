# Spec — Porte de confidentialité (`engine:privacy`)

| | |
|---|---|
| **Exigences** | EF-PRIV-01 à 07, EF-PRIV-12, EF-ONB-07, INV-05 |
| **Tickets** | M2-05 (règles, listes, masquage), M3-02 (fenêtres sécurisées) |
| **Voir aussi** | [07 — Sécurité](../07-securite-vie-privee.md) · [ADR-0008](../adr/0008-detection-des-fenetres-securisees.md) |

## 1. Principe

Défense en profondeur : plusieurs couches indépendantes, chacune suffisante pour rejeter un écran.
Dans le doute, on rejette. Aucun réglage, aucun mode de test ne désactive la porte.

```kotlin
interface PrivacyGate {
    /** Décision sur le paquet seul, avant toute lecture du contenu. */
    fun checkPackage(meta: ScreenMeta): GateDecision

    /** Décision après lecture de l'arbre. */
    fun checkTree(meta: ScreenMeta, extraction: TreeExtraction): GateDecision

    /** Masquage du texte avant stockage. Ne rejette jamais : remplace. */
    fun redact(text: String): Redaction
}

sealed interface GateDecision {
    data object Allow : GateDecision
    data class Deny(val reason: DropReason) : GateDecision
}

data class Redaction(val text: String, val maskedCount: Int)
```

## 2. Règles

| ID | Couche | Condition | Effet |
|---|---|---|---|
| PG-01 | Paquet | Paquet toujours ignoré : SeekMind, interface système, lanceur, clavier ([capture §5.3](capture.md)) | `APP_IGNORED` |
| PG-02 | Paquet | Paquet de la liste **stricte** (gestionnaires de mots de passe, authentification) | `APP_SENSITIVE` — non modifiable par l'utilisateur |
| PG-03 | Paquet | Règle utilisateur « exclure » | `APP_EXCLUDED` |
| PG-04 | Paquet | Règle utilisateur « inclure » | `Allow` — prime sur PG-05 et PG-06 |
| PG-05 | Paquet | Paquet de la liste **souple** (banque, paiement, santé, administration) ou correspondant à un motif | `APP_SENSITIVE` |
| PG-06 | Paquet | `ApplicationInfo.category == CATEGORY_GAME` | `APP_CATEGORY` |
| PG-14 | Paquet | Famille d'applications désactivée par l'utilisateur (§3.4) | `APP_FAMILY_DISABLED` |
| PG-07 | Arbre | `extraction.hasPasswordField` | `PASSWORD_FIELD` |
| PG-08 | Arbre | Navigateur connu et identifiant de vue correspondant à un marqueur de navigation privée (§4) | `PRIVATE_BROWSING` |
| PG-09 | Fenêtre | Fenêtre sécurisée détectée par la sonde ([ocr §3](ocr.md)) | `SECURE_WINDOW` |
| PG-10 | Nœud | Champ de saisie | Texte du nœud ignoré (dans `TreeExtractor`) |
| PG-11 | Texte | Numéro de carte bancaire valide | Remplacé par le marqueur |
| PG-12 | Texte | IBAN valide | Remplacé par le marqueur |
| PG-13 | Texte | Numéro de sécurité sociale français valide | Remplacé par le marqueur |

Ordre d'évaluation de `checkPackage` : PG-01, PG-02, PG-03, PG-04, PG-05, PG-06, PG-14. Une règle
utilisateur « inclure » (PG-04) prime donc aussi sur une famille désactivée.

Android applique de lui-même une quatorzième protection : à partir d'Android 14, les vues marquées
`accessibilityDataSensitive` ne sont pas exposées aux services qui ne sont pas des outils d'accessibilité.

## 3. Listes d'applications

### 3.1 Format

`engine/privacy/src/main/assets/sensitive_apps.json` :

```json
{
  "version": 1,
  "strict": {
    "packages": ["com.x8bit.bitwarden", "com.google.android.apps.authenticator2"],
    "patterns": ["keepass", "authenticator", "password", "passwd", "\\.vault(\\.|$)", "\\.otp(\\.|$)"]
  },
  "soft": {
    "packages": ["com.boursorama.android.clients", "com.paypal.android.p2pmobile"],
    "patterns": ["(^|\\.)bank", "banque", "banking", "\\.wallet(\\.|$)", "\\.pay(ment)?s?(\\.|$)", "crypto", "credit"]
  }
}
```

Les motifs sont des expressions régulières appliquées au nom de paquet en minuscules. Le chargement
est fait une fois ; la décision par paquet est mise en cache.

### 3.2 Contenu initial

À compléter et à **vérifier identifiant par identifiant** au ticket M2-05. Marché de lancement
(décision D-12) : France, Belgique, Suisse, Luxembourg, Espagne, Allemagne, Italie. Objectif : au moins
250 entrées, dont les principales banques de détail, néobanques et applications de paiement de chacun
de ces pays.

| Liste | Exemples d'identifiants |
|---|---|
| Stricte — mots de passe | `com.x8bit.bitwarden`, `com.onepassword.android`, `com.agilebits.onepassword`, `com.lastpass.lpandroid`, `com.dashlane`, `keepass2android.keepass2android`, `com.kunzisoft.keepass.free`, `com.kunzisoft.keepass.libre`, `proton.android.pass`, `com.nordpass.android.app`, `com.samsung.android.samsungpass` |
| Stricte — authentification | `com.google.android.apps.authenticator2`, `com.azure.authenticator`, `com.authy.authy`, `com.beemdevelopment.aegis` |
| Souple — banque (France) | `com.boursorama.android.clients`, `fr.creditagricole.androidapp`, `net.bnpparibas.mescomptes`, `mobi.societegenerale.mobile.lappli`, `fr.lcl.android.customerarea`, `com.caisseepargne.android.mobilebanking`, `fr.banquepopulaire.cyberplus`, `com.fullsix.android.labanquepostale.accountaccess`, `com.cm_prod.bad`, `com.cic_prod.bad`, `com.fortuneo.android` |
| Souple — paiement et néobanques | `com.revolut.revolut`, `de.number26.android`, `com.paypal.android.p2pmobile`, `com.lydia`, `com.transferwise.android`, `com.google.android.apps.walletnfcrel`, `com.samsung.android.spay` |
| Souple — banque (six autres pays du lancement) | À établir au ticket, pays par pays |
| Souple — santé et administration | À établir au ticket, pour chaque pays (assurance maladie, impôts, identité numérique, prise de rendez-vous médicaux) |
| Souple — système | `com.android.settings` |

### 3.3 Règles de l'utilisateur

Table `app_rule(package_name, mode, updated_at)` avec `mode ∈ {EXCLUDED, INCLUDED}`. L'écran Réglages →
Applications liste les applications lançables avec leur état effectif et son origine (« exclue par
défaut : application bancaire »). Réintégrer une application de la liste souple demande une
confirmation. Les applications de la liste stricte sont affichées verrouillées.

### 3.4 Familles d'applications

À l'installation, avant d'activer le service, l'utilisateur choisit ce que SeekMind mémorise (EF-ONB-07).
Le choix est modifiable dans Réglages → Applications.

| Famille (`AppFamily`) | Appartenance, dans cet ordre de priorité |
|---|---|
| `MESSAGING` — messageries et e-mails | Application de SMS par défaut ; applications répondant à `mailto:` ou `smsto:` ; liste intégrée (`app_families.json` : WhatsApp, Messenger, Telegram, Signal, Discord, Gmail, Outlook…) |
| `BROWSERS` — navigateurs | Paquets de `BrowserProfiles` ; applications répondant à un Intent `VIEW` sur `https` sans hôte particulier |
| `SOCIAL_MEDIA` — réseaux sociaux et vidéo | `ApplicationInfo.category` valant `CATEGORY_SOCIAL` ou `CATEGORY_VIDEO` ; liste intégrée (Instagram, TikTok, YouTube, X, Facebook, Reddit, Snapchat, LinkedIn, Pinterest…) |
| `OTHER` — autres applications | Tout le reste : achats, actualités, cartes, productivité… |

- Une application appartient à une seule famille : la première qui correspond.
- Les applications des listes sensibles (§3.1) ne relèvent d'aucune famille : elles restent exclues.
- **État initial : les quatre familles sont cochées** ; l'utilisateur décoche ce qu'il ne veut pas
  (décision D-13 ; la valeur est `PrivacyConfig.defaultEnabledFamilies`). L'écran rappelle que banques,
  mots de passe et navigation privée ne sont jamais mémorisés, et montre pour chaque famille les icônes
  de quelques applications installées qui en font partie.
- Réglage : `enabledFamilies` (DataStore). La classification d'un paquet est mise en cache et recalculée
  à l'installation ou à la désinstallation d'une application.
- Le choix est enregistré **avant** la redirection vers les réglages d'accessibilité : aucune capture ne
  peut précéder ce choix.

## 4. Navigation privée

Deux couches :

1. **La sonde de fenêtre sécurisée (PG-09) est la protection principale.** Chrome, Firefox, Samsung
   Internet et les navigateurs dérivés de Chromium posent `FLAG_SECURE` sur leurs onglets privés par défaut.
   Les navigateurs sont sondés à **chaque** capture, sur toutes les versions d'Android.
2. **Marqueurs dans l'arbre (PG-08)**, en complément : pour un paquet de `BrowserProfiles`, si un
   identifiant de vue visible correspond à l'un des motifs `incognito`, `private_browsing`,
   `private_mode`, `secret_mode` (insensible à la casse), l'écran est rejeté. La table des marqueurs par
   navigateur est à valider sur appareil au ticket M2-05.

Un utilisateur qui autorise les captures d'écran en navigation privée dans les réglages de son
navigateur retire la première couche ; la seconde reste. Cette limite figure dans la FAQ.

## 5. Masquage

Marqueur unique : `[•••]`. Le masquage s'applique au texte complet de la capture (arbre et OCR), avant
le calcul du SimHash et avant tout stockage.

### 5.1 Carte bancaire (PG-11)

1. Candidats : `(?<![\d])(?:\d[ \-]?){12,18}\d(?![\d])`.
2. Chiffres seuls ; le candidat est retenu si le couple (préfixe, longueur) est plausible :

   | Réseau | Préfixe | Longueur |
   |---|---|---|
   | Visa | 4 | 16 |
   | Mastercard | 51–55, 2221–2720 | 16 |
   | American Express | 34, 37 | 15 |
   | Discover | 6011, 65 | 16 |
   | UnionPay | 62 | 16 à 19 |

3. Somme de contrôle de Luhn valide → masqué.

Le filtrage par préfixe évite de masquer les numéros de suivi et de commande purement numériques.

### 5.2 IBAN (PG-12)

1. Candidats : `\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,4})?\b` (après mise en majuscules).
2. Espaces retirés ; longueur entre 15 et 34 ; si le pays est connu, longueur exacte (FR 27, DE 22,
   ES 24, IT 27, BE 16, NL 18, LU 20, CH 21, GB 22, PT 25).
3. Contrôle modulo 97 égal à 1 → masqué.

### 5.3 Numéro de sécurité sociale français (PG-13)

1. Candidats : `\b[12][ ]?\d{2}[ ]?(?:0[1-9]|1[0-2]|[2-9]\d)[ ]?(?:\d{2}|2[AB])[ ]?\d{3}[ ]?\d{3}[ ]?\d{2}\b`.
2. Clé : `97 − (nombre des 13 premiers caractères mod 97)`, avec `2A → 19` et `2B → 18` → masqué si elle correspond.

### 5.4 Ce qui n'est pas masqué, délibérément

Codes promotionnels, numéros de suivi, numéros de téléphone, adresses, codes postaux : ce sont des cas
d'usage du produit. Les codes à usage unique ne sont pas masqués : ils expirent en quelques minutes et
toute heuristique fiable masquerait aussi des codes utiles.

## 6. Configuration

```kotlin
data class PrivacyConfig(
    val redactionMark: String = "[•••]",
    val excludeGames: Boolean = true,
    val privateMarkers: List<String> = listOf("incognito", "private_browsing", "private_mode", "secret_mode"),
    val defaultEnabledFamilies: Set<AppFamily> = AppFamily.entries.toSet(),
)
```

## 7. Tests attendus

- **JVM** : table de décision de `checkPackage` (toutes les combinaisons liste × règle utilisateur ×
  catégorie × famille) ; classification en familles (priorités, application de SMS par défaut, listes) ; motifs sur des noms de paquets positifs et négatifs ; `checkTree` ; masquage sur
  `testdata/sensitive_cases.tsv` (numéros valides masqués, numéros invalides et numéros de suivi intacts) ;
  propriété : `redact` est idempotente.
- **Bout en bout** : E2E-02, 03, 11, 12, 19.
- **Manuel** : protocole des écrans sensibles ([06 §8](../06-qualite-tests-ci.md#8-validation-manuelle)).
