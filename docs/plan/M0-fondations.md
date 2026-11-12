# M0 — Fondations et levée des risques

**Objectif.** Un projet qui compile en deux variantes, une CI qui vérifie les invariants, et quatre
prototypes qui confirment — ou infirment — les paris techniques avant d'écrire le produit.

**Sortie du jalon.** Les rapports `docs/prototypes/S1` à `S4` existent, chacun avec ses mesures et sa
conclusion ; le porteur du projet les a lus ([plan §4](README.md#4-points-darrêt-pour-le-porteur-du-projet)).

Ordre conseillé : M0-01 → M0-02 → M0-03 → M0-08 → M0-06 et M0-07 ; M0-04, M0-05 et M0-10 en parallèle ; M0-09 en dernier.

---

### M0-01 — Squelette Gradle multi-modules et variantes

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | — | tous | [architecture §3, §8, §9, §10.1, §10.2, §12](../03-architecture.md) · [ADR-0009](../adr/0009-publicite-admob-et-variante-sans-reseau.md) |

**À faire**

1. Wrapper Gradle 9.9.1 ; `gradle/libs.versions.toml` avec les versions de l'architecture §8.
2. `build-logic/` : plugins de convention `seekmind.android.application`, `seekmind.android.library`,
   `seekmind.android.compose`, `seekmind.kotlin.library` (JVM pur), `seekmind.hilt`, `seekmind.android.test`.
   `minSdk` 30, `targetSdk` 36, `compileSdk` 37, JVM 17. AGP 9 : Kotlin intégré, ne pas appliquer `kotlin-android`.
3. `settings.gradle.kts` : tous les modules de l'architecture §3.1 sauf `:modelpack_multi` (créé par
   M4-06), vides mais compilables, chacun avec le bon plugin et le bon paquet (`fr.corsicajack.seekmind.<chemin>`).
4. `:app` : identifiant `fr.corsicajack.seekmind` ; `SeekMindApp` (Hilt) ; `MainActivity` Compose
   affichant le nom de l'application ; manifeste commun avec `allowBackup="false"`,
   `data_extraction_rules.xml` excluant tout et le bloc `<queries>`. Les services seront déclarés par leurs tickets.
5. Dimension de variante `network` : `ads` et `offline`. Dossiers `app/src/ads/` et `app/src/offline/`
   avec leur complément de manifeste (architecture §10.2). `:engine:ads` n'est lié qu'à la variante `ads`
   (`adsImplementation`) ; il est vide à ce stade.
6. Types de build `debug` (suffixe `.debug`), `release` (R8, réduction des ressources), `benchmark` ;
   filtres d'ABI ; `androidResources.noCompress += "onnx"`.
7. `gradle.properties` : cache de configuration, exécution parallèle, mémoire de la JVM.

**Critères d'acceptation**

- [x] `./gradlew assembleAdsDebug assembleOfflineDebug assembleAdsRelease assembleOfflineRelease` réussit
      sur une machine préparée par `scripts/setup-android-sdk.sh`.
- [x] `./gradlew projects` liste les modules attendus.
- [x] Le manifeste fusionné `offlineRelease` ne contient pas `INTERNET` ; les deux portent `allowBackup="false"`.
- [x] Aucun numéro de version hors de `libs.versions.toml` ; aucun code propre à une variante hors de `:app`.

---

### M0-02 — Analyse statique et formatage

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0-01 | `build-logic` | [AGENTS §3](../../AGENTS.md) · [architecture §10.4](../03-architecture.md) |

**À faire**

1. Spotless avec ktlint pour Kotlin et les scripts Gradle, aligné sur `.editorconfig`.
2. Android Lint : `abortOnError`, pas de fichier de référence ; `MissingTranslation` et `HardcodedText`
   en erreur ; configuration commune dans le plugin de convention.
3. Avertissements du compilateur Kotlin traités comme des erreurs dans les sources principales.

**Critères d'acceptation**

- [x] `./gradlew spotlessCheck lint` réussit sur le squelette.
- [x] Une chaîne en dur dans un composable ou une chaîne non traduite fait échouer `lint` (test avec un fichier temporaire, non committé, décrit dans la PR).

---

### M0-03 — Intégration continue `ci`

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-02 | `.github/workflows` | [prérequis §6](../04-prerequis.md#6-intégration-continue) · [tests §7](../06-qualite-tests-ci.md#7-intégration-continue) |

**À faire**

1. `.github/workflows/ci.yml` : JDK 21, cache Gradle, SDK par `scripts/setup-android-sdk.sh`, puis
   `spotlessCheck`, `lint`, tests JVM, et compilation des quatre variantes (`ads` et `offline`, `debug` et `release`).
2. Étape conditionnelle pour `tools/` (pytest), active dès que le dossier existe.
3. Publication des rapports en artefacts ; annulation des exécutions obsolètes d'une même branche.
4. Les tâches qui n'ont pas besoin du modèle passent `-PskipModel`.

**Critères d'acceptation**

- [x] Le workflow est vert sur la PR du ticket et dure moins de 12 minutes (4 min au premier passage).
- [ ] Une erreur de formatage introduite volontairement le fait échouer (preuve dans la PR, puis retirée).

**Validation manuelle** — le porteur du projet protège `main` (PR obligatoire, vérification `ci`
requise) et autorise la fusion automatique.

---

### M0-04 — Garde-fous automatiques

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0-01 | `build-logic`, `scripts`, `gradle/` | [architecture §3.2, §10.3, §10.4](../03-architecture.md) · [AGENTS §2](../../AGENTS.md) |

**À faire**

1. `:app:verifyAdsReleaseManifest` et `:app:verifyOfflineReleaseManifest` : lisent le manifeste fusionné
   de la variante ; vérifient les permissions contre `gradle/permission-allowlist-<variante>.txt`, les
   composants exportés contre `gradle/exported-allowlist.txt`, `allowBackup="false"`, et — si
   `accessibility_service_config.xml` existe — que ses seules capacités sont `canRetrieveWindowContent`
   et `canTakeScreenshot`, sans `isAccessibilityTool`.
2. `scripts/check-forbidden-apis.sh` : motifs lus dans `gradle/forbidden-apis.txt` (motif, périmètre de
   fichiers, message). Motifs initiaux : ceux de l'architecture §10.4 ; les API réseau sont interdites
   partout sauf dans `engine/ads`. Les sources de test sont exclues.
3. `:app:verifyDependencies` : dépendances d'exécution `release` de chaque variante ⊆ sa liste blanche.
4. Contrôle du graphe de modules dans le plugin de convention (architecture §3.2) : `engine:ads` ne
   dépend que de `core:common` et `core:model`, personne ne dépend de lui hormis `:app` en variante
   `ads`, et il est absent des dépendances de la variante `offline`.
5. `scripts/check-elf-alignment.sh` : alignement 16 Ko des bibliothèques natives de l'APK `release`.
6. Ajout de ces contrôles au workflow `ci`.
7. La logique de chaque contrôle est du code testé (tests JVM dans `build-logic`, ou tests du script
   sur des fichiers d'exemple).

**Critères d'acceptation**

- [x] Chaque garde-fou a un test « passe » et un test « échoue » : manifeste `offline` avec `INTERNET`,
      manifeste `ads` avec une permission hors liste, source hors de `engine/ads` important une API
      réseau, source appelant `performGlobalAction`, dépendance hors liste, dépendance `engine → feature`,
      `engine:ads` dépendant de `core:database`, bibliothèque mal alignée.
- [x] Les garde-fous sont verts sur le squelette et exécutés par `ci`.

---

### M0-05 — Environnement des agents

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0-01 | `scripts` | [prérequis §4, §5](../04-prerequis.md#5-environnement-des-agents-ia) |

**À faire**

1. Vérifier `scripts/setup-android-sdk.sh` contre le projet réel (plateforme et outils de build attendus
   par AGP) et l'ajuster si nécessaire.
2. `scripts/doctor.sh` : contrôle les versions de Java, du SDK et de Python, la présence du modèle
   embarqué, et affiche pour chaque manque la commande qui le corrige.
3. Mettre à jour les commandes d'`AGENTS.md` §4 si elles ont changé.

**Critères d'acceptation**

- [x] Dans un environnement vierge : `scripts/setup-android-sdk.sh && ./gradlew assembleAdsDebug -PskipModel` réussit.
- [x] `scripts/doctor.sh` rend un code de sortie non nul et un message utile pour chaque prérequis manquant.

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

### M0-07 — Prototype S2 : ML Kit sans télémétrie

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-04, M0-08 | `engine:ocr`, `app` | [ocr §4](../specs/ocr.md) · [architecture §10.2, §10.3](../03-architecture.md) · risque R-11 |

**À faire**

1. Intégrer `com.google.mlkit:text-recognition` ; un `OcrEngine` minimal.
2. Test instrumenté : 100 reconnaissances d'un texte dessiné sur un bitmap ; résultat correct à chaque fois.
3. Comparer l'option A (permission retirée, variante `offline` seulement) et l'option B (composants de
   transport retirés du manifeste, les deux variantes) : plantages, exceptions dans le journal système,
   tâches planifiées (`dumpsys jobscheduler`), **octets émis par l'application** dans la variante `ads`.
4. Dans la variante `offline`, essayer de retirer aussi `ACCESS_NETWORK_STATE` ; consigner ce qui casse.
5. Relever le poids ajouté à l'APK par ABI.
6. Rédiger `docs/prototypes/S2-mlkit-sans-telemetrie.md` : option retenue, sort de `ACCESS_NETWORK_STATE`,
   mesures. Mettre à jour les manifestes, les listes blanches de permissions et la spec OCR §4.1 en conséquence.

**Critères d'acceptation**

- [ ] `:app:verifyOfflineReleaseManifest` et `:app:verifyAdsReleaseManifest` verts avec ML Kit dans le build.
- [ ] Aucune erreur fatale dans le journal système pendant les 100 reconnaissances, sur les trois émulateurs.
- [ ] Variante `ads` : aucun octet émis par l'application pendant et après les reconnaissances.

**Validation manuelle** — sur le téléphone de test, installation neuve, mode avion : 20 reconnaissances
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
   applications, tests instrumentés de la variante `ads`.
6. Mesurer : durée de lecture de l'arbre, durée d'une capture d'écran avec et sans copie, intervalle
   minimal accepté par le système entre deux captures.
7. Rédiger `docs/prototypes/S3-accessibilite.md` ; corriger ADR-0008 et la spec OCR §3 si l'émulateur ou
   les mesures contredisent leurs hypothèses.

**Critères d'acceptation**

- [ ] `device-tests` vert sur les trois niveaux d'API, en moins de 25 minutes.
- [ ] Le rapport donne les mesures et confirme ou corrige la stratégie de sonde, **en particulier pour
      l'API 30**, qui ne sera validée sur aucun téléphone réel avant le test fermé (risque R-14).

**Validation manuelle** — sur le téléphone de test : lecture de l'arbre de trois applications courantes
(nombre de nœuds, durée) et sonde sur un onglet de navigation privée de Chrome.

---

### M0-09 — Prototype S4 : embeddings sur appareil

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-08, M0-10 | `engine:embedding` | [embeddings §4, §8](../specs/embeddings.md) · [ADR-0010](../adr/0010-modele-francais-et-packs-de-langue.md) |

**À faire**

1. Intégrer ONNX Runtime ; charger le modèle `fr` par projection en mémoire depuis l'asset, et par copie
   en repli ; charger le modèle `multi` par chemin de fichier.
2. Exécuter les modèles sur des identifiants de jetons pré-calculés (`testdata/tokenizer_golden_<id>.jsonl`) :
   le prototype ne dépend pas du tokenizer Kotlin.
3. Comparer les vecteurs à `testdata/embedding_golden_<id>.json`.
4. Mesurer, pour chaque modèle : chargement, latence pour 16, 128 et 256 jetons avec 1, 2 et 4 threads,
   mémoire du processus session chargée ; vérifier l'alignement 16 Ko de la bibliothèque native ; poids
   ajouté à l'APK.
5. **Premier regard sur la qualité du modèle `fr`** : sur vingt paires requête/passage rédigées pour
   l'occasion (dix en français, dix en anglais), comparer le classement obtenu avec `fr` et avec `multi`.
6. Rédiger `docs/prototypes/S4-embeddings.md` avec la comparaison aux cibles de la spec §8 et un avis
   sur le modèle `fr` (risque R-06).

**Critères d'acceptation**

- [ ] Cosinus ≥ 0,99 avec les vecteurs de référence, pour les deux modèles, sur émulateur.
- [ ] Le rapport tranche : chemin de chargement retenu, nombre de threads, cibles atteignables ou non,
      et si le modèle `fr` se comporte comme attendu en français.

**Validation manuelle** — mêmes mesures sur le téléphone de test.

---

### M0-10 — Outillage des modèles

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M0-01 | `tools/model`, `testdata`, `engine:embedding` | [embeddings §2, §3.2, §5.2](../specs/embeddings.md) · [tests §5](../06-qualite-tests-ci.md#5-jeux-de-référence) |

**À faire**

1. `tools/model/requirements.txt` (versions figées) et les scripts `build_model.py`, `export_vocab.py`,
   `gen_tokenizer_golden.py`, `gen_embedding_golden.py`, `verify.py`, tous paramétrés par `--model fr|multi`.
   Le modèle `fr` n'est pas distribué au format ONNX : `build_model.py` l'exporte avant de le quantifier.
2. `pack_model.py --model <id>` : produit l'archive `.smpack` attendue par `FileModelPackInstaller`.
3. Phrases sources versionnées : 1 000 phrases FR/EN pour le tokenizer (ponctuation, chiffres, émojis,
   URL, codes), 50 textes pour les vecteurs. Aucune donnée personnelle réelle.
4. `engine/embedding/model.lock.json` (empreintes attendues des deux jeux) et la tâche
   `:engine:embedding:verifyModel`, désactivable par `-PskipModel`.
5. Option `--install` : copie le jeu `fr` dans les assets du module (ignorés par git).
6. Workflow `model` (manuel et hebdomadaire) et cache des artefacts dans `ci` et `device-tests`.
7. Tests `pytest` : format de `vocab.bin`, empreintes, contrôles de qualité, archive `.smpack`.

**Critères d'acceptation**

- [ ] `python tools/model/build_model.py --model fr --install` et `--model multi` produisent des
      artefacts conformes à `model.lock.json` ; tailles relevées (cibles : environ 36 Mo et 118 Mo).
- [ ] Le contrôle de cosinus de la spec §2 passe pour les deux modèles (celui sur le jeu d'évaluation
      arrive avec M5-07) ; les fichiers de référence sont committés avec leurs empreintes.
- [ ] Aucun fichier de modèle, de vocabulaire binaire ni d'archive `.smpack` n'est suivi par git.
