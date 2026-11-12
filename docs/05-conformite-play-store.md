# 05 — Conformité Google Play

| | |
|---|---|
| **Version** | 1.0 — 12 novembre 2026 |
| **Source principale** | [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491) (Aide Play Console, consultée le 12 novembre 2026) |
| **À faire avant soumission** | Relire la page source : les règles évoluent (dernière évolution connue : octobre 2025) |

Ce document traduit les règles de Google Play en exigences de conception. Il n'est pas un avis juridique.

## 1. Ce que dit la politique

- Toute application ciblant l'API 31+ qui contient un `AccessibilityService` doit remplir une
  **déclaration** dans la Play Console.
- Deux parcours existent : **outil d'accessibilité** (`isAccessibilityTool="true"`) ou **autre usage**.
- Hors outil d'accessibilité, l'application doit afficher une **divulgation visible** et recueillir un
  **consentement par action affirmative**, puis fournir une **vidéo** de démonstration.
- Est **interdit** tout usage qui permet à une application « d'initier, planifier et exécuter des
  actions de façon autonome ». Sont aussi interdits de longue date : modifier des réglages sans accord,
  empêcher la désinstallation, contourner les protections de confidentialité d'Android, tromper l'utilisateur.
- Un usage non déclaré ou trompeur expose à la **suspension de l'application et à la résiliation du compte**.

## 2. Quel parcours de déclaration

**SeekMind suit le parcours « autre usage ». `isAccessibilityTool` n'est pas déclaré** (décision D-10).

Google réserve le statut d'outil d'accessibilité aux services dont l'objet principal est d'aider des
personnes en situation de handicap : lecteurs d'écran, entrée par contacteur, entrée vocale, braille.
La page cite explicitement, comme **non** éligibles : antivirus, outils d'automatisation, assistants,
applications de surveillance, nettoyeurs, gestionnaires de mots de passe, lanceurs.

Le brief initial proposait de présenter l'application comme un outil d'assistance cognitive. Cette
présentation reste légitime **comme axe de communication** (le produit aide réellement les personnes
ayant des difficultés de mémoire ou d'attention), mais elle ne doit ni servir à cocher le statut d'outil
d'accessibilité, ni être exagérée dans la déclaration ou la fiche : ce serait un usage trompeur.

Conséquences : divulgation (§4), déclaration (§5) et vidéo (§6) sont obligatoires.

## 3. Engagements de conception

| Règle de Google | Réponse de SeekMind | Vérification |
|---|---|---|
| Pas d'action autonome | Aucune API d'action dans le code ; capacités du service limitées à la lecture et à la capture d'écran | INV-02, garde-fous CI |
| Divulgation et consentement avant l'accès | Écran bloquant avant toute redirection vers les réglages ; le service ignore tout événement sans consentement enregistré | EF-ONB-02, EF-ONB-05, INV-04 |
| Usage limité à la finalité déclarée | Les données ne servent qu'à la recherche locale et aux actions contextuelles ; aucune publicité ciblée, aucune analyse d'usage | INV-08 |
| Pas de transmission cachée | Aucune permission `INTERNET` : vérifiable par quiconque dans le manifeste de l'APK | INV-01 |
| Ne pas contourner les protections d'Android | Fenêtres sécurisées, champs de mot de passe et vues marquées sensibles ne sont pas indexés | EF-PRIV-01 à 03 |
| Ne pas tromper | Icône et nom visibles, service nommé « SeekMind — mémorisation de l'écran », pause en un tap, désinstallation normale | Revue M11-06 |
| Déclaration à jour | Tout changement d'usage de l'API entraîne une nouvelle déclaration | Liste de contrôle de publication |

Configuration du service (`res/xml/accessibility_service_config.xml`) :

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/a11y_service_description"
    android:notificationTimeout="100"
    android:settingsActivity="app.seekmind.MainActivity"
    android:summary="@string/a11y_service_summary" />
```

Aucun autre attribut `can*` ; pas de `isAccessibilityTool`. La description du service
(`a11y_service_description`) reprend la divulgation en version courte : elle ne la remplace pas.

## 4. Écran de divulgation

### 4.1 Règles

- Affiché **dans l'application**, dans le parcours normal, **avant** `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Dédié** : aucune autre demande, aucune autre mention (pas de conditions d'utilisation, pas de publicité).
- Décrit les données consultées, la finalité, l'usage et le partage.
- **Action affirmative** : bouton « J'accepte ». Fermer, revenir en arrière ou quitter vaut refus.
- Les deux boutons ont le même poids visuel ; le texte entier doit avoir été affichable (défilement)
  avant que « J'accepte » soit actif.
- Refus : l'application reste utilisable ; la divulgation peut être rouverte à tout moment (EF-ONB-04).
- Le texte est **versionné** (`DISCLOSURE_VERSION`, entier). Toute modification de fond incrémente la
  version et redemande le consentement (EF-ONB-05).

### 4.2 Texte — version 1 — français

> **SeekMind a besoin de lire ce qui s'affiche sur votre écran**
>
> Pour vous permettre de retrouver ce que vous avez vu, SeekMind utilise le service d'accessibilité
> d'Android (API AccessibilityService).
>
> **Ce que SeekMind lit**
> - Le texte affiché dans les applications que vous utilisez, le nom de l'application, le titre de
>   l'écran et l'adresse des pages web.
> - Quand un écran contient surtout des images ou de la vidéo, SeekMind analyse ponctuellement l'image
>   de l'écran pour y reconnaître du texte. Cette image est traitée en mémoire et n'est jamais enregistrée.
>
> **Pourquoi**
> Uniquement pour constituer, sur ce téléphone, votre index de recherche personnel, et pour vous proposer
> des actions sur ce qui est affiché (ajouter une date à l'agenda, ouvrir une adresse…).
>
> **Ce que deviennent ces données**
> - Elles restent sur ce téléphone, dans une base chiffrée. Elles ne sont ni envoyées, ni partagées, ni
>   vendues : l'application n'a pas l'autorisation d'accéder à Internet.
> - Vous pouvez mettre la mémorisation en pause, exclure des applications et tout supprimer à tout moment.
>
> **Ce que SeekMind ne lit pas**
> Les champs de mot de passe, la navigation privée, les écrans protégés et les applications sensibles
> (banque, gestionnaires de mots de passe…).
>
> SeekMind n'effectue jamais d'action à votre place dans les autres applications.
>
> `[ Non merci ]` `[ J'accepte ]`

### 4.3 Texte — version 1 — anglais

> **SeekMind needs to read what is shown on your screen**
>
> To let you find again what you have seen, SeekMind uses Android's accessibility service
> (AccessibilityService API).
>
> **What SeekMind reads**
> - The text displayed in the apps you use, the app name, the screen title and the address of web pages.
> - When a screen mostly contains images or video, SeekMind occasionally analyses the image of the
>   screen to recognise text in it. That image is processed in memory and is never saved.
>
> **Why**
> Only to build your personal search index on this phone, and to offer actions on what is displayed
> (add a date to your calendar, open an address…).
>
> **What happens to this data**
> - It stays on this phone, in an encrypted database. It is never sent, shared or sold: the app does
>   not have permission to access the Internet.
> - You can pause memorisation, exclude apps and delete everything at any time.
>
> **What SeekMind does not read**
> Password fields, private browsing, protected screens and sensitive apps (banking, password managers…).
>
> SeekMind never performs actions on your behalf in other apps.
>
> `[ No thanks ]` `[ I agree ]`

## 5. Déclarations dans la Play Console

Réponses à préparer (ticket M11-03). Les intitulés exacts du formulaire sont à relire au moment de la saisie.

### 5.1 Déclaration d'accessibilité

| Question | Réponse préparée |
|---|---|
| Pourquoi l'application utilise-t-elle l'API ? | **Fonctionnalité de l'application** : lire le texte affiché pour constituer un index de recherche personnel stocké sur l'appareil, et proposer des actions sur le contenu affiché. |
| L'application collecte-t-elle ou partage-t-elle des données personnelles ou sensibles par ce moyen ? | Les données sont **consultées et stockées sur l'appareil uniquement** ; rien n'est transmis (aucune permission `INTERNET`). Décrire ce traitement local dans le champ libre. Si le formulaire assimile le stockage local à une collecte, déclarer les types concernés (messages, navigation web, activité dans les applications) avec la mention « traité sur l'appareil uniquement ». **Dans le doute, déclarer plus plutôt que moins.** |
| Vidéo | Lien vers la vidéo du §6. |

### 5.2 Sécurité des données

Selon les définitions de Google, une donnée traitée uniquement sur l'appareil et jamais transmise n'est
pas « collectée ». Réponse préparée : **aucune donnée collectée, aucune donnée partagée**. La page web de
récompense n'est pas l'application : elle a sa propre information et son propre recueil de consentement.

### 5.3 Autres déclarations

| Rubrique | Réponse préparée |
|---|---|
| Publicité | **Oui, contient des annonces** (par transparence : la formule gratuite repose sur des publicités, même affichées dans le navigateur). |
| Politique de confidentialité | URL du site (obligatoire). Contenu : [07 §7](07-securite-vie-privee.md#7-politique-de-confidentialité). |
| Public cible | 18 ans et plus (ou 16+ selon l'avis juridique) ; pas d'application pour enfants. |
| Classification du contenu | Questionnaire standard ; l'application n'héberge aucun contenu. |
| Achats | Aucun achat intégré en v1. Voir le risque R-03 pour les codes d'activation. |

## 6. Vidéo de démonstration

Exigences de Google : montrer l'ouverture de l'application, le parcours jusqu'à la divulgation avec le
**texte entier lisible**, le parcours de consentement jusqu'à l'activation du service, le **parcours de
refus** et la façon de rouvrir la divulgation, puis une fonctionnalité centrale utilisant l'accessibilité.

Scénario (une seule prise, sans coupe, écran d'un appareil réel, sous-titres ou voix) :

1. Écran d'accueil du téléphone → ouverture de SeekMind (première exécution).
2. Écrans de présentation → écran de divulgation ; **défilement lent** jusqu'en bas.
3. « Non merci » → l'application s'ouvre en mode limité ; montrer le bandeau « Activer la mémorisation ».
4. Tap sur le bandeau → la divulgation se rouvre → « J'accepte ».
5. Réglages d'accessibilité → activation de « SeekMind » → confirmation système → retour à l'application.
6. Ouverture d'une application tierce, lecture d'un écran contenant un mot distinctif.
7. Retour dans SeekMind → recherche de ce mot → résultat → « Ouvrir ».
8. Tuile de réglages rapides : mise en pause.

Durée visée : 90 à 120 secondes. Hébergement : lien YouTube non répertorié ou équivalent.

## 7. Fiche de l'application

- Décrire l'usage de l'accessibilité **dans la description**, en clair, dès les premières lignes.
- Ne pas employer « espion », « surveillance », « enregistre tout », ni promettre un retour exact à la
  source quand il ne l'est pas.
- Arguments à mettre en avant : pas d'autorisation Internet, base chiffrée, pause et suppression,
  exclusions automatiques.
- Les captures d'écran de la fiche ne montrent aucune donnée personnelle réelle.

## 8. Plan de repli

| Situation | Action |
|---|---|
| Rejet pour divulgation insuffisante | Corriger le texte ou le parcours, refaire la vidéo, resoumettre. |
| Rejet de principe sur l'usage de l'API | Recours argumenté (manifeste sans `INTERNET`, liste des garde-fous, vidéo). |
| Rejet confirmé | Distribution hors Play : APK signé sur le site, autres boutiques. Contraintes : l'**enregistrement du développeur auprès d'Android** devient obligatoire pour les applications installées sur appareils certifiés (appliqué depuis le 30 septembre 2026 dans quatre pays, généralisation annoncée pour 2027) ; les « paramètres restreints » d'Android 13+ ajoutent une étape manuelle à l'activation de l'accessibilité pour les applications installées par fichier. |

## 9. Restrictions de plateforme à connaître

| Version | Effet | Traitement |
|---|---|---|
| Android 13+ | « Paramètres restreints » pour les applications installées par fichier APK | Sans objet pour une installation Play ; documenté pour les testeurs ([04 §7](04-prerequis.md#7-matériel-de-test-humain)). |
| Android 14+ | Les vues marquées `accessibilityDataSensitive` sont masquées aux services non « outils » | Effet voulu : ces contenus ne sont pas lus. |
| Android 16+ | Mode **Protection avancée** | Voir ligne suivante. |
| Android 17 | Protection avancée activée : les services d'accessibilité non « outils » sont **révoqués et ne peuvent pas être activés** | Fonction facultative et peu répandue. Détecter l'état, expliquer pourquoi SeekMind ne peut pas fonctionner (ticket M10-08). Risque R-05. |

## 10. Liste de contrôle avant soumission

- [ ] `:app:verifyReleaseManifest` vert sur l'AAB soumis ; `INTERNET` absent.
- [ ] Divulgation conforme au §4, version enregistrée avec le consentement.
- [ ] Parcours de refus fonctionnel ; divulgation rouvrable.
- [ ] Service inactif tant que le consentement n'est pas donné (test de bout en bout vert).
- [ ] Vidéo conforme au §6, lien valide.
- [ ] Déclarations du §5 saisies ; politique de confidentialité en ligne.
- [ ] Description de la fiche conforme au §7.
- [ ] Page source de la politique relue le jour de la soumission.
