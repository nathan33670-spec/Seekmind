# M0 — Fondations et levée des risques

**Objectif.** Un projet qui compile, une CI qui vérifie les invariants, et quatre prototypes qui
confirment — ou infirment — les paris techniques avant d'écrire le produit.

**Sortie du jalon.** Les rapports `docs/prototypes/S1` à `S4` existent, chacun avec ses mesures et sa
conclusion ; le porteur du projet les a lus ([plan §4](README.md#4-points-darrêt-pour-le-porteur-du-projet)).

Ordre conseillé : M0-01 → M0-02 → M0-03 → M0-08 → M0-06 et M0-07 ; M0-04, M0-05 et M0-10 en parallèle ; M0-09 en dernier.

---

### M0-01 — Squelette Gradle multi-modules

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | — | tous | [architecture §3, §8, §9, §10.1, §12](../03-architecture.md) |

**À faire**

1. Wrapper Gradle 9.9.1 ; `gradle/libs.versions.toml` avec les versions de l'architecture §8.
2. `build-logic/` : plugins de convention `seekmind.android.application`, `seekmind.android.library`,
   `seekmind.android.compose`, `seekmind.kotlin.library` (JVM pur), `seekmind.hilt`, `seekmind.android.test`.
   `minSdk` 30, `targetSdk` 36, `compileSdk` 37, JVM 17. AGP 9 : Kotlin intégré, ne pas appliquer `kotlin-android`.
3. `settings.gradle.kts` : tous les modules de l'architecture §3.1, vides mais compilables, chacun avec
   le bon plugin et le bon paquet (`app.seekmind.<chemin>`).
4. `:app` : `SeekMindApp` (Hilt), `MainActivity` Compose affichant le nom de l'application ; manifeste
   avec le retrait de `INTERNET`, `allowBackup="false"`, `data_extraction_rules.xml` excluant tout, le
   bloc `<queries>`. Les services seront déclarés par leurs tickets.
5. Types de build `debug` (suffixe `.debug`), `release` (R8, réduction des ressources), `benchmark` ;
   filtres d'ABI ; `androidResources.noCompress += "onnx"`.
6. `gradle.properties` : cache de configuration, exécution parallèle, mémoire de la JVM.

**Critères d'acceptation**

- [ ] `./gradlew assembleDebug assembleRelease` réussit sur une machine préparée par `scripts/setup-android-sdk.sh`.
- [ ] `./gradlew projects` liste exactement les modules de l'architecture §3.1.
- [ ] Le manifeste fusionné `release` ne contient pas `INTERNET` et porte `allowBackup="false"`.
- [ ] Aucun numéro de version hors de `libs.versions.toml`.

---

### M0-02 — Analyse statique et formatage

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01 | `build-logic` | [AGENTS §3](../../AGENTS.md) · [architecture §10.3](../03-architecture.md) |

**À faire**

1. Spotless avec ktlint pour Kotlin et les scripts Gradle, aligné sur `.editorconfig`.
2. Android Lint : `abortOnError`, pas de fichier de référence ; `MissingTranslation` et `HardcodedText`
   en erreur ; configuration commune dans le plugin de convention.
3. Avertissements du compilateur Kotlin traités comme des erreurs dans les sources principales.

**Critères d'acceptation**

- [ ] `./gradlew spotlessCheck lint` réussit sur le squelette.
- [ ] Une chaîne en dur dans un composable ou une chaîne non traduite fait échouer `lint` (test avec un fichier temporaire, non committé, décrit dans la PR).

---

### M0-03 — Intégration continue `ci`

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-02 | `.github/workflows` | [prérequis §6](../04-prerequis.md#6-intégration-continue) · [tests §7](../06-qualite-tests-ci.md#7-intégration-continue) |

**À faire**

1. `.github/workflows/ci.yml` : JDK 21, cache Gradle, SDK par `scripts/setup-android-sdk.sh`, puis
   `spotlessCheck`, `lint`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease`.
2. Étapes conditionnelles pour `tools/` (pytest) et `reward-web/` (tests Node), actives dès que ces dossiers existent.
3. Publication des rapports en artefacts ; annulation des exécutions obsolètes d'une même branche.
4. Les tâches qui n'ont pas besoin du modèle passent `-PskipModel`.

**Critères d'acceptation**

- [ ] Le workflow est vert sur la PR du ticket et dure moins de 10 minutes.
- [ ] Une erreur de formatage introduite volontairement le fait échouer (preuve dans la PR, puis retirée).

**Validation manuelle** — le porteur du projet protège `main` (PR obligatoire, vérification `ci` requise).

---

### M0-04 — Garde-fous automatiques

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01 | `build-logic`, `scripts`, `gradle/` | [architecture §10.2, §10.3](../03-architecture.md) · [AGENTS §2](../../AGENTS.md) |

**À faire**

1. `:app:verifyReleaseManifest` : lit le manifeste fusionné `release` ; vérifie les permissions contre
   `gradle/permission-allowlist.txt`, les composants exportés contre `gradle/exported-allowlist.txt`,
   `allowBackup="false"`, et — si `accessibility_service_config.xml` existe — que ses seules capacités
   sont `canRetrieveWindowContent` et `canTakeScreenshot`, sans `isAccessibilityTool`.
2. `scripts/check-forbidden-apis.sh` : motifs lus dans `gradle/forbidden-apis.txt` (motif, périmètre de
   fichiers, message). Motifs initiaux : ceux de l'architecture §10.3. Les sources de test sont exclues.
3. `:app:verifyDependencies` : dépendances d'exécution `release` ⊆ `gradle/dependency-allowlist.txt`.
4. Contrôle du graphe de modules dans le plugin de convention (architecture §3.2).
5. `scripts/check-elf-alignment.sh` : alignement 16 Ko des bibliothèques natives de l'APK `release`.
6. Ajout de ces contrôles au workflow `ci`.
7. La logique de chaque contrôle est du code testé (tests JVM dans `build-logic`, ou tests du script
   sur des fichiers d'exemple).

**Critères d'acceptation**

- [ ] Chaque garde-fou a un test « passe » et un test « échoue » : manifeste avec `INTERNET`, source
      appelant `performGlobalAction`, dépendance hors liste, dépendance `engine → feature`, bibliothèque mal alignée.
- [ ] Les garde-fous sont verts sur le squelette et exécutés par `ci`.

---

### M0-05 — Environnement des agents

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01 | `scripts` | [prérequis §4, §5](../04-prerequis.md#5-environnement-des-agents-ia) |

**À faire**

1. Vérifier `scripts/setup-android-sdk.sh` contre le projet réel (plateforme et outils de build attendus
   par AGP) et l'ajuster si nécessaire.
2. `scripts/doctor.sh` : contrôle les versions de Java, du SDK, de Python et de Node, la présence du
   modèle, et affiche pour chaque manque la commande qui le corrige.
3. Mettre à jour les commandes d'`AGENTS.md` §4 si elles ont changé.

**Critères d'acceptation**

- [ ] Dans un environnement vierge : `scripts/setup-android-sdk.sh && ./gradlew assembleDebug -PskipModel` réussit.
- [ ] `scripts/doctor.sh` rend un code de sortie non nul et un message utile pour chaque prérequis manquant.

---

### M0-06 — Prototype S1 : SQLCipher, Room, FTS5

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01, M0-08 | `core:database` | [stockage §1 à §3, §5](../specs/stockage.md) · [ADR-0004](../adr/0004-stockage-room-sqlcipher-fts5.md) |

**À faire**

1. Ouvrir une base Room sur SQLCipher avec une clé de test, créer la table `capture` et l'objet FTS5 avec ses déclencheurs.
2. Tests instrumentés : `PRAGMA compile_options` contient `ENABLE_FTS5` ; insertion puis `MATCH` ;
   `bm25` et `snippet` ; correspondance sans accents ; fichier illisible avec une mauvaise clé.
3. Mesurer sur émulateur : insertion de 10 000 lignes de 600 caractères (médiane et p95 par ligne),
   requête `MATCH` (médiane et p95).
4. Rédiger `docs/prototypes/S1-sqlcipher-fts5.md` : versions, mesures, écarts par rapport à la spec, conclusion.

**Critères d'acceptation**

- [ ] Tests verts sur les émulateurs API 30, 34 et 36.
- [ ] Le rapport conclut explicitement : pile confirmée, ou alternative proposée par un ADR.

---

### M0-07 — Prototype S2 : ML Kit sans réseau

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-04, M0-08 | `engine:ocr`, `app` | [ocr §4](../specs/ocr.md) · [architecture §10.2](../03-architecture.md) · risque R-11 |

**À faire**

1. Intégrer `com.google.mlkit:text-recognition` ; un `OcrEngine` minimal.
2. Test instrumenté : 100 reconnaissances d'un texte dessiné sur un bitmap ; résultat correct à chaque fois.
3. Comparer l'option A (permission retirée seule) et l'option B (composants de transport retirés du
   manifeste) : plantages, exceptions dans le journal système, tâches planifiées (`dumpsys jobscheduler`).
4. Essayer de retirer aussi `ACCESS_NETWORK_STATE` ; consigner ce qui casse.
5. Relever le poids ajouté à l'APK par ABI.
6. Rédiger `docs/prototypes/S2-mlkit-sans-reseau.md` : option retenue, sort de `ACCESS_NETWORK_STATE`,
   mesures. Mettre à jour le manifeste, `permission-allowlist.txt` et la spec OCR §4.1 en conséquence.

**Critères d'acceptation**

- [ ] `:app:verifyReleaseManifest` vert avec ML Kit dans le build.
- [ ] Aucune erreur fatale dans le journal système pendant les 100 reconnaissances, sur les trois émulateurs.

**Validation manuelle** — sur un téléphone réel, installation neuve, mode avion : 20 reconnaissances
réussies ; résultat consigné dans la PR.

---

### M0-08 — Prototype S3 : service d'accessibilité, application de test, CI `device-tests`

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-03 | `engine:capture`, `fixtureapp`, `.github/workflows` | [capture §2, §4](../specs/capture.md) · [ocr §2, §3](../specs/ocr.md) · [tests §3, §4](../06-qualite-tests-ci.md) · [ADR-0008](../adr/0008-detection-des-fenetres-securisees.md) |

**À faire**

1. `:fixtureapp` avec `PlainTextActivity`, `SecureActivity`, `ImageTextActivity`.
2. Service d'accessibilité minimal (configuration XML de la conformité §3) qui, à la demande d'un test,
   lit l'arbre de la fenêtre active et tente une capture d'écran. Aucune persistance.
3. Harnais de bout en bout : activation du service par commande shell, drapeau
   `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, règle de test réutilisable.
4. Tests : texte de `PlainTextActivity` lu ; capture d'écran obtenue (`takeScreenshot` en API 30,
   `takeScreenshotOfWindow` en API 34+) ; `SecureActivity` → erreur « fenêtre sécurisée » en API 34+,
   image noire en API 30.
5. `.github/workflows/device-tests.yml` : émulateurs API 30, 34, 36 (x86_64, KVM), installation des deux
   applications, `connectedDebugAndroidTest`.
6. Mesurer : durée de lecture de l'arbre, durée d'une capture d'écran avec et sans copie, intervalle
   minimal accepté par le système entre deux captures.
7. Rédiger `docs/prototypes/S3-accessibilite.md` ; corriger ADR-0008 et la spec OCR §3 si l'émulateur ou
   les mesures contredisent leurs hypothèses.

**Critères d'acceptation**

- [ ] `device-tests` vert sur les trois niveaux d'API, en moins de 25 minutes.
- [ ] Le rapport donne les mesures et confirme ou corrige la stratégie de sonde.

**Validation manuelle** — sur un téléphone réel : lecture de l'arbre de trois applications courantes
(nombre de nœuds, durée) et sonde sur un onglet de navigation privée de Chrome.

---

### M0-09 — Prototype S4 : embeddings sur appareil

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-08, M0-10 | `engine:embedding` | [embeddings §4, §7](../specs/embeddings.md) · [ADR-0006](../adr/0006-modele-d-embeddings.md) |

**À faire**

1. Intégrer ONNX Runtime ; charger `e5s-int8.onnx` par projection en mémoire depuis l'asset, et par copie en repli.
2. Exécuter le modèle sur des identifiants de jetons pré-calculés (`testdata/tokenizer_golden.jsonl`) :
   le prototype ne dépend pas du tokenizer Kotlin.
3. Comparer les vecteurs à `testdata/embedding_golden.json`.
4. Mesurer : chargement, latence pour 16, 128 et 256 jetons avec 1, 2 et 4 threads, mémoire du processus
   session chargée ; vérifier l'alignement 16 Ko de la bibliothèque native ; poids ajouté à l'APK.
5. Rédiger `docs/prototypes/S4-embeddings.md` avec la comparaison aux cibles de la spec §7.

**Critères d'acceptation**

- [ ] Cosinus ≥ 0,99 avec les vecteurs de référence sur émulateur.
- [ ] Le rapport tranche : chemin de chargement retenu, nombre de threads, cibles atteignables ou non.

**Validation manuelle** — mêmes mesures sur un téléphone moyen et, si possible, un téléphone bas de gamme.

---

### M0-10 — Outillage du modèle

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01 | `tools/model`, `testdata`, `engine:embedding` | [embeddings §2, §3.2](../specs/embeddings.md) · [tests §5](../06-qualite-tests-ci.md#5-jeux-de-référence) |

**À faire**

1. `tools/model/requirements.txt` (versions figées) et les scripts `build_model.py`, `export_vocab.py`,
   `gen_tokenizer_golden.py`, `gen_embedding_golden.py`, `verify.py`.
2. Phrases sources versionnées : 1 000 phrases FR/EN pour le tokenizer (ponctuation, chiffres, émojis,
   URL, codes), 50 textes pour les vecteurs. Aucune donnée personnelle réelle.
3. `engine/embedding/model.lock.json` (empreintes attendues) et la tâche `:engine:embedding:verifyModel`,
   désactivable par `-PskipModel`.
4. Option `--install` : copie les artefacts dans les assets du module (ignorés par git).
5. Workflow `model` (manuel et hebdomadaire) et cache des artefacts dans `ci` et `device-tests`.
6. Tests `pytest` : format de `vocab.bin`, empreintes, contrôles de qualité.

**Critères d'acceptation**

- [ ] `python tools/model/build_model.py --install` produit des artefacts conformes à `model.lock.json`.
- [ ] Le contrôle de cosinus de la spec §2 passe (celui sur le jeu d'évaluation arrive avec M5-07) ; les fichiers de référence sont committés avec leurs empreintes.
- [ ] Aucun fichier `.onnx` ni `.bin` de modèle n'est suivi par git.
