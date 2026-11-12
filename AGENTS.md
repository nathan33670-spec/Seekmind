# Guide des agents de développement

Consigne permanente pour tout agent IA (ou humain) qui modifie ce dépôt. Elle prime sur les habitudes
générales ; seules les instructions explicites du porteur du projet priment sur elle.

## 1. Avant d'écrire du code

1. Lire ce fichier en entier.
2. Lire le ticket à traiter dans `docs/plan/` et **toutes** les sections de spécification qu'il référence.
3. Vérifier que les tickets dont il dépend ont le statut `Fait`. Sinon, s'arrêter et le signaler.
4. Ne traiter **qu'un seul ticket** par branche et par pull request. Ne pas élargir le périmètre.
5. Si la spécification est ambiguë, contradictoire ou techniquement fausse : ne pas deviner en silence.
   Choisir l'option la plus conservatrice vis-à-vis des invariants (§2), l'implémenter, et la décrire dans
   la section « Écarts par rapport à la spécification » de la PR. Si la décision est structurante
   (nouvelle dépendance, changement de schéma, changement d'interface entre modules), ajouter un ADR
   dans `docs/adr/` et corriger la spec dans la même PR.

## 2. Invariants non négociables

Une PR qui viole un invariant est refusée, même si ses tests passent.

| ID | Règle | Vérification |
|---|---|---|
| INV-01 | **Zéro réseau.** Pas de permission `INTERNET` dans le manifeste fusionné ; aucune API réseau (`java.net`, OkHttp, Ktor, `WebView`, `ConnectivityManager`…). L'application n'ouvre le web qu'en lançant le navigateur de l'utilisateur. | `:app:verifyReleaseManifest`, `scripts/check-forbidden-apis.sh` |
| INV-02 | **Passivité.** Le service d'accessibilité n'exécute aucune action sur l'interface : pas de `performGlobalAction`, `performAction`, `dispatchGesture`, `GestureDescription`. Ses seules capacités déclarées sont `canRetrieveWindowContent` et `canTakeScreenshot`. | `scripts/check-forbidden-apis.sh`, contrôle du XML de configuration |
| INV-03 | **Aucune image persistée.** Une capture d'écran n'existe qu'en mémoire : `HardwareBuffer` fermé et `Bitmap` recyclé dans un `finally`. Jamais de `Bitmap.compress`, de fichier, de cache disque, de `MediaStore`. | Script de garde-fous + test de bout en bout (aucun fichier créé) |
| INV-04 | **Consentement.** Aucune lecture d'écran tant que la version courante de la divulgation n'a pas été acceptée. | Test de bout en bout |
| INV-05 | **Porte de confidentialité.** Toute capture passe par `PrivacyGate` avant le moindre stockage. Aucun contournement, aucun mode debug qui la désactive. | Tests unitaires + bout en bout |
| INV-06 | **Chiffrement.** Tout contenu capturé (texte, titres, URL, vecteurs) vit uniquement dans la base SQLCipher. Rien dans des fichiers en clair, `SharedPreferences`, DataStore ou le presse-papiers (sauf action explicite de l'utilisateur). | Revue + tests |
| INV-07 | **Logs sans contenu.** Aucun texte capturé, titre, URL, requête de recherche ou jeton dans les logs, y compris en debug. On journalise des compteurs, des durées, des longueurs. Passer par `SmLog` (`core:common`), jamais `android.util.Log` directement. | Script de garde-fous |
| INV-08 | **Pas de télémétrie.** Ni analytics, ni rapport de plantage distant, ni SDK publicitaire dans l'application. | Liste blanche de dépendances |
| INV-09 | **Pas de sauvegarde système.** `allowBackup="false"`, règles d'extraction qui excluent tout. | `:app:verifyReleaseManifest` |
| INV-10 | **Secrets hors dépôt.** Aucune clé privée, keystore, jeton ou mot de passe committé. Seules des clés de test explicitement marquées comme telles sont admises dans les sources de test. | Revue, `.gitignore` |
| INV-11 | **Dépendances sous contrôle.** Toute nouvelle bibliothèque exige une mise à jour de `gradle/dependency-allowlist.txt` et une justification dans la PR. | `:app:verifyDependencies` |
| INV-12 | **Surface exportée minimale.** Composants exportés autorisés : activité de lancement, activité de réception des liens `seekmind://`, service d'accessibilité et tuile de réglages rapides (protégés par permission système). Toute entrée externe (Intent, lien) est validée. | `:app:verifyReleaseManifest` |

## 3. Conventions

**Langues.** Documentation en français. Code, identifiants, commentaires et messages de commit en
anglais. Titre de PR en anglais (Conventional Commits), description de PR en français (modèle fourni).
Toute chaîne visible par l'utilisateur est une ressource, fournie en français **et** en anglais.

**Kotlin.** Style officiel Kotlin/Android, vérifié par ktlint. Coroutines et `Flow` ; pas de `GlobalScope`,
pas de `runBlocking` hors tests. Les dispatchers sont injectés (`core:common`). Pas de singleton mutable
global : injection par Hilt. Les constantes de réglage vivent dans les objets `*Config` décrits par les specs.

**Architecture.** Respecter le graphe de dépendances de `docs/03-architecture.md` §3 : `feature → engine → core`.
Un module `engine` n'importe jamais un module `feature`. L'accès à la base passe exclusivement par
`core:database`. Les interfaces entre modules sont celles des specs : les modifier exige de corriger la spec.

**Tests.** Toute logique pure a des tests JVM. Tout ce qui touche à SQLCipher, à ML Kit, au runtime ONNX
ou au service d'accessibilité a des tests instrumentés. Un correctif de bug commence par un test qui échoue.
Les jeux de données de référence (`testdata/`) ne sont modifiés que par leur script générateur.

**Git.** Branche `feat/<ticket>-<slug>` (ex. `feat/M2-03-tree-extractor`), `fix/…`, `docs/…`.
Commits [Conventional Commits](https://www.conventionalcommits.org/) : `feat(capture): add tree extractor (M2-03)`.
Pas de commit sur `main` en direct, pas de `--force` sur une branche partagée.

## 4. Commandes

Disponibles à partir du jalon M0 (les tickets M0 les créent) :

```bash
scripts/setup-android-sdk.sh            # installe le SDK Android (une fois par machine)
./gradlew assembleDebug                 # compile
./gradlew spotlessApply                 # formate
./gradlew spotlessCheck lint            # analyse statique
./gradlew testDebugUnitTest             # tests JVM
./gradlew :app:verifyReleaseManifest    # permissions et composants du manifeste fusionné
./gradlew :app:verifyDependencies       # liste blanche des dépendances
scripts/check-forbidden-apis.sh         # API interdites (réseau, automatisation, images, logs)
./gradlew connectedDebugAndroidTest     # tests instrumentés (émulateur : en CI uniquement)
```

Les environnements cloud des agents n'ont pas d'émulateur : les tests instrumentés s'exécutent dans le
workflow GitHub Actions `device-tests`. Un agent pousse sa branche, lit le résultat de la CI et corrige.

## 5. Definition of Done

Un ticket est `Fait` lorsque **tout** ce qui suit est vrai :

- [ ] Les critères d'acceptation du ticket sont couverts par des tests automatisés (ou, quand le ticket
      le précise, par une procédure manuelle documentée dont le résultat est consigné dans la PR).
- [ ] `spotlessCheck`, `lint`, `testDebugUnitTest`, les garde-fous et la CI `device-tests` sont verts.
- [ ] Aucun invariant du §2 n'est affaibli ; aucun garde-fou n'est désactivé ou contourné.
- [ ] Les chaînes sont en ressources FR + EN ; l'écran ajouté est utilisable avec TalkBack.
- [ ] La documentation touchée est à jour (spec, ADR, `docs/plan/` : statut du ticket passé à `Fait`).
- [ ] La PR décrit les écarts éventuels par rapport à la spec.

## 6. Interdits

- Ajouter une permission au manifeste, une dépendance, un composant exporté ou un processus sans que le
  ticket le demande.
- Désactiver, assouplir ou ignorer un test, une règle de lint ou un garde-fou pour « faire passer » la CI.
- Écrire des données de test réalistes contenant de vraies informations personnelles.
- Committer un modèle ML, un keystore ou un fichier `.env`.
- Mentionner un outil ou un modèle d'IA dans le code, les commentaires ou la documentation du produit.

## 7. Où trouver quoi

| Besoin | Fichier |
|---|---|
| Ce que le produit doit faire | `docs/02-cahier-des-charges.md` |
| Modules, dépendances, versions | `docs/03-architecture.md` |
| Comportement détaillé d'un sous-système | `docs/specs/<sous-système>.md` |
| Pourquoi une décision a été prise | `docs/adr/` |
| Quoi faire ensuite, dans quel ordre | `docs/plan/README.md` |
| Comment tester | `docs/06-qualite-tests-ci.md` |
| Règles Google Play | `docs/05-conformite-play-store.md` |
