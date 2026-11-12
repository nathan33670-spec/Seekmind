# 06 — Qualité, tests et intégration continue

| | |
|---|---|
| **Version** | 1.0 — 12 novembre 2026 |
| **Principe** | Un agent IA ne « voit » pas l'application : un critère d'acceptation n'existe que s'il est vérifié par un test automatisé ou par une procédure manuelle consignée |

## 1. Niveaux de test

| Niveau | Outil | Où | Couvre |
|---|---|---|---|
| **Unitaire JVM** | JUnit 4, Truth, Turbine, coroutines-test | `src/test` de chaque module | Toute la logique pure : anti-rebond, règles de confidentialité, masquage, normalisation, SimHash, découpage, tokenizer, analyse de requête, expressions temporelles, fusion RRF, registre de crédits, vérification de jetons, détection d'entités |
| **JVM avec Android simulé** | Robolectric | `src/test` | ViewModels, construction d'Intents, DataStore |
| **Instrumenté** | AndroidX Test | `src/androidTest`, émulateur en CI | SQLCipher + Room + FTS5, migrations, clé Keystore, ML Kit, ONNX Runtime, WorkManager |
| **Bout en bout** | UI Automator + application de test | `app/src/androidTest` | Le service réel face à des écrans connus (§4) |
| **Interface** | Compose UI Test | `feature/*/src/androidTest` | Écrans, états, accessibilité |
| **Performance** | Microbenchmark, Macrobenchmark | `benchmark/` | Cibles ENF mesurables sur émulateur ou appareil (§6) |
| **Outils** | pytest ; Vitest | `tools/`, `reward-web/` | Jetons, modèle, Worker |
| **Manuel** | Procédure écrite | PR | Ce qui exige un vrai téléphone : surcouches constructeur, batterie, applications tierces |

## 2. Conventions

- Nom des tests : phrase en anglais entre accents graves — `` `drops the screen when a password field is visible` ``.
- **Doubles plutôt que mocks.** `core:testing` fournit `FakeClock`, `TestDispatchers`, une base en
  mémoire, `FakeEmbeddingEngine` (vecteurs déterministes dérivés d'un hachage) et des fabriques de modèles.
  MockK est réservé aux classes Android finales.
- **Temps virtuel.** Aucun `Thread.sleep` ni `delay` réel dans les tests JVM : `TestScope` et `FakeClock`.
- **Abstraction de l'arbre.** `TreeExtractor` travaille sur l'interface `UiNode` (spec capture §4) ; les
  tests JVM construisent des arbres en Kotlin, sans `AccessibilityNodeInfo`.
- **Fichiers de référence.** Les données de `testdata/` sont produites par des scripts versionnés et
  jamais éditées à la main ; chaque fichier a une empreinte dans `testdata/SHA256SUMS`.
- **Pas de données personnelles réelles** dans les jeux de test.
- Un test instable est corrigé ou supprimé, jamais relancé jusqu'à ce qu'il passe.

## 3. Application de test

Module `:fixtureapp` (identifiant `app.seekmind.fixture`), installé à côté de l'application en CI.
Chaque activité affiche un contenu fixe, connu des tests.

| Activité | Contenu | Sert à vérifier |
|---|---|---|
| `PlainTextActivity` | Trois paragraphes en français et en anglais avec des mots distinctifs | Capture, recherche par mots-clés |
| `ScrollActivity` | Liste de 200 éléments numérotés | Anti-rebond, déduplication |
| `PasswordActivity` | Texte + champ de mot de passe | EF-PRIV-01 |
| `SecureActivity` | Texte dans une fenêtre `FLAG_SECURE` | EF-PRIV-02 |
| `EditableActivity` | Champ de saisie pré-rempli + texte statique | EF-PRIV-06 |
| `ImageTextActivity` | Image contenant un texte dessiné, sans description | OCR |
| `SensitiveDataActivity` | Numéro de carte et IBAN de test valides, numéro de suivi | Masquage EF-PRIV-07 |
| `WebActivity` | Vue web chargeant du HTML local, avec une barre d'adresse factice identifiée `url_bar` | Extraction d'URL |
| `EntitiesActivity` | Date, adresse, téléphone, numéro de suivi | Actions contextuelles |
| `DeepLinkActivity` | Répond à `https://fixture.seekmind.test/item/{id}` et affiche l'identifiant | Retour à la source |
| `FakeBrowserActivity` | Répond aux URL de récompense et renvoie un jeton signé par la clé de test | Parcours de récompense sans réseau |

## 4. Tests de bout en bout

### 4.1 Mise en place

```kotlin
// Le service réel doit rester actif pendant que UI Automator pilote l'appareil.
Configurator.getInstance().uiAutomationFlags =
    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
```

- Activation du service par commande shell dans une règle de test :
  `settings put secure enabled_accessibility_services <appId>/app.seekmind.engine.capture.SeekMindAccessibilityService`
  puis `settings put secure accessibility_enabled 1`.
- Le test s'exécute dans le processus de l'application : il enregistre le consentement via
  `SettingsRepository`, remplace `CaptureConfig` par une configuration rapide (calme 300 ms, attente
  maximale 1 500 ms) et lit la base par le graphe Hilt.
- Synchronisation : le pipeline expose `CapturePipeline.events: SharedFlow<PipelineEvent>`
  (`Stored(captureId)`, `Dropped(reason)`, `Deduplicated(captureId)`), sans contenu. Les tests attendent
  l'événement, jamais une durée.

### 4.2 Scénarios

| ID | Scénario | Résultat attendu |
|---|---|---|
| E2E-01 | Ouvrir `PlainTextActivity` | `Stored` ; le mot distinctif est trouvé par la recherche lexicale |
| E2E-02 | Ouvrir `PasswordActivity` | `Dropped(PASSWORD_FIELD)` ; aucune ligne en base |
| E2E-03 | Ouvrir `SecureActivity` | `Dropped(SECURE_WINDOW)` |
| E2E-04 | Ouvrir `EditableActivity` | `Stored` ; le texte statique est présent, le texte du champ absent |
| E2E-05 | Ouvrir `ImageTextActivity` | `Stored` avec l'origine OCR ; le texte de l'image est trouvé |
| E2E-06 | Après E2E-05 | Aucun fichier créé hors des fichiers de base de données (comparaison des répertoires avant/après) |
| E2E-07 | Faire défiler `ScrollActivity` pendant 5 s | Au plus 4 captures (configuration rapide) |
| E2E-08 | Ouvrir cinq fois `PlainTextActivity` | Une capture, `seen_count = 5` |
| E2E-09 | Consentement absent | Aucun événement du pipeline |
| E2E-10 | Mémorisation en pause | Aucun événement du pipeline |
| E2E-11 | Application de test exclue dans les réglages | `Dropped(APP_EXCLUDED)` |
| E2E-12 | Ouvrir `SensitiveDataActivity` | Carte et IBAN remplacés par le marqueur de masquage ; numéro de suivi intact |
| E2E-13 | Ouvrir `WebActivity` | L'URL est enregistrée dans la capture |
| E2E-14 | Retour à la source vers `DeepLinkActivity` | L'activité s'ouvre sur le bon identifiant |
| E2E-15 | Récompense via `FakeBrowserActivity` | Solde +5 ; le même jeton rejoué est refusé |
| E2E-16 | Ouvrir `EntitiesActivity`, pastille active | La pastille apparaît ; un tap émet l'Intent d'agenda attendu |
| E2E-17 | Lancer le lot d'embeddings (pilote de test WorkManager) | Fragments vectorisés ; une paraphrase retrouve `PlainTextActivity` |
| E2E-18 | Tuer le processus puis rouvrir un écran | Le service reprend et capture |

Matrice : API 30, 34 et 36. E2E-03 s'appuie sur le code d'erreur système à partir de l'API 34 et sur
l'heuristique d'image noire en API 30 (ADR-0008).

## 5. Jeux de référence

| Fichier (`testdata/`) | Produit par | Utilisé par |
|---|---|---|
| `tokenizer_golden.jsonl` — 1 000 phrases FR/EN et leurs identifiants de jetons | `tools/model/gen_tokenizer_golden.py` | Tests du tokenizer Kotlin : parité exacte ≥ 99,5 % |
| `embedding_golden.json` — 50 textes et leurs vecteurs de référence (modèle quantifié, exécuté en Python) | `tools/model/gen_embedding_golden.py` | Tests instrumentés du moteur : cosinus ≥ 0,995 |
| `eval/corpus.jsonl` — environ 300 captures synthétiques réalistes (applications, dates, textes) | `tools/eval/gen_corpus.py` (gabarits déterministes) | Évaluation de la recherche |
| `eval/queries.jsonl` — environ 60 requêtes annotées (type : exact, code, paraphrase, temporel, application) | Rédigées, versionnées | Rappel@k, MRR |
| `tokens/` — jetons de récompense et de licence valides, altérés, expirés, mal typés | `tools/license/gen_test_vectors.py` | Tests du vérificateur Kotlin et du Worker |
| `temporal_cases.tsv` — expression, date de référence, intervalle attendu | Rédigé, versionné | Tests de l'analyseur temporel |
| `sensitive_cases.tsv` — texte, résultat de masquage attendu | Rédigé, versionné | Tests du masquage |

L'évaluation de la recherche tourne sur JVM en CI avec le vrai modèle (ONNX Runtime existe pour JVM de
bureau) : `./gradlew :engine:search:evalSearch` écrit `build/reports/search-eval.json` et échoue sous
les seuils de EF-SRCH-01 à 03.

## 6. Mesures de performance

| Cible | Protocole |
|---|---|
| ENF-PERF-01 | Trace de méthode sur `onAccessibilityEvent` pendant le défilement de `ScrollActivity` ; test instrumenté qui mesure le temps passé sur le thread principal. |
| ENF-PERF-02 | Durée de `TreeExtractor.extract` enregistrée dans `metric_daily` ; rapport sur dix applications réelles (manuel). |
| ENF-PERF-03, 04 | Microbenchmark sur une base synthétique de 100 000 captures et 100 000 vecteurs générée par `core:testing` (textes tirés des gabarits du corpus, vecteurs pseudo-aléatoires normalisés). |
| ENF-PERF-05 | Microbenchmark de `IngestService.ingest`. |
| ENF-PERF-06 | Macrobenchmark de démarrage à froid, avec profil de référence. |
| ENF-BAT-01, 02 | **Manuel**, appareil réel : `dumpsys batterystats --reset`, 24 h d'usage type dont 4 h d'écran, puis `dumpsys batterystats --charged <appId>` ; rapport de la consommation estimée et du temps CPU. Répété avec la mémorisation en pause pour obtenir la différence. |
| ENF-MEM-01, 02 | `dumpsys meminfo <appId>` au repos après 10 min de capture, puis pendant un lot d'embeddings. |
| ENF-STO-01 | Taille de la base après le scénario de 24 h. |
| ENF-STO-02 | `bundletool get-size total` sur l'AAB. |

Les résultats sont consignés dans `docs/mesures/` (un fichier par campagne : appareil, version, chiffres).

## 7. Intégration continue

Workflows et durées : [04 §6](04-prerequis.md#6-intégration-continue). Règles :

- `ci` et `device-tests` sont **requis** pour fusionner dans `main`.
- Aucun secret n'est nécessaire aux workflows de développement.
- Les rapports (tests, lint, évaluation de la recherche) sont publiés en artefacts.
- Le workflow `device-tests` lance l'émulateur avec l'accélération KVM, installe l'application de test,
  active le service, puis exécute `connectedDebugAndroidTest`.
- Un échec de garde-fou (§10.3 de l'architecture) n'est jamais contourné par une exception dans le script :
  on corrige le code, ou on modifie la règle par un ADR.

## 8. Validation manuelle

Certains tickets portent la mention « Validation manuelle ». La PR contient alors :

```text
Validation manuelle
Appareil : <modèle>, Android <version>
Build : <hash du commit>
Étapes : <liste numérotée>
Résultat : <observé>, <captures d'écran sans donnée personnelle si utile>
```

Protocole de recette des écrans sensibles (à rejouer avant chaque publication) : ouvrir tour à tour une
application bancaire, un gestionnaire de mots de passe, un onglet de navigation privée dans Chrome et
dans Firefox, l'écran de connexion d'un site, puis vérifier dans le journal et dans Réglages →
Diagnostic qu'aucune capture n'a été créée et que les rejets sont comptés.
