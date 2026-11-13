# M2 — Capture et confidentialité

**Objectif.** Le service d'accessibilité lit les écrans stables, la porte de confidentialité écarte ce
qui ne doit pas être mémorisé, et le texte arrive en base.

**Sortie du jalon.** Sur l'application de test, les scénarios E2E-01, 02, 04, 07 à 13, 18 et 19 sont verts.
L'OCR et la sonde de fenêtre sécurisée arrivent au jalon M3.

---

### M2-01 — Service, routage des événements, état

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-05 | `engine:capture`, `app` | [capture §2, §5.3, §8](../specs/capture.md) · [conformité §3](../05-conformite-play-store.md#3-engagements-de-conception) |

**À faire**

1. `SeekMindAccessibilityService` et sa configuration XML (remplace le service du prototype S3) ;
   déclaration dans le manifeste ; libellé, résumé et description en FR et EN.
2. `EventRouter` : copie des primitives de l'événement, filtre des paquets toujours ignorés, suivi de la
   fenêtre courante, délégation au thread `sm-capture`.
3. Calcul de l'ensemble des paquets ignorés (SeekMind, interface système, lanceurs, claviers), recalculé
   à l'installation ou à la désinstallation d'une application.
4. `CaptureStatusRepository` : les quatre états de la spec §8.

**Critères d'acceptation**

- [ ] Aucun accès à l'arbre, à la base ou au disque sur le thread principal (test avec `StrictMode` en debug).
- [ ] Les événements des paquets ignorés ne réveillent pas le thread de capture.
- [ ] `CaptureStatus` reflète : service désactivé, consentement manquant, pause, actif.
- [ ] `:app:verifyAdsReleaseManifest` et `:app:verifyOfflineReleaseManifest` verts (capacités du service, composants exportés).

---

### M2-02 — Anti-rebond

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M1-01 | `engine:capture` | [capture §3](../specs/capture.md) · EF-CAP-01 |

**À faire**

1. `Debouncer` en Kotlin pur, horloge et minuterie injectées.
2. Fusion des déclenchements pendant qu'une capture est en cours.
3. Ralentissement adaptatif (§3.2).

**Critères d'acceptation**

- [x] Tests en temps virtuel : délai de calme ; attente maximale sous un flux continu d'événements ;
      intervalle minimal ; un seul déclenchement mémorisé ; doublement puis remise à zéro de l'attente maximale.

---

### M2-03 — Lecture de l'arbre

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M1-01 | `engine:capture` | [capture §4](../specs/capture.md) · ENF-PERF-02 |

**À faire**

1. Interface `UiNode` ; `A11yUiNode` (enveloppe d'`AccessibilityNodeInfo`, pré-chargement à partir de l'API 33, libération).
2. `TreeExtractor.extract` : règles de texte, budgets, statistiques (`textChars`, `textCoverage`, `viewIds`).
3. Constructeur d'arbres factices pour les tests.

**Critères d'acceptation**

- [x] Un test par règle du §4.2 : nœud invisible, mot de passe (arrêt immédiat), champ de saisie,
      texte, description (cliquable ou non, image), titre, ligne en double.
- [x] Un test par budget : nœuds, profondeur, durée souple (résultat partiel), durée dure (abandon), taille du texte.
- [x] Tous les nœuds visités sont libérés, y compris en cas d'abandon.

---

### M2-04 — Métadonnées d'écran et URL

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M2-03 | `engine:capture` | [capture §5](../specs/capture.md) · EF-CAP-02 |

**À faire**

1. `ScreenMeta` : nom d'activité validé et mis en cache, titre de fenêtre.
2. `BrowserProfiles` (ressource JSON) et `UrlExtractor` : lecture de la barre d'adresse, normalisation.
3. `UrlTracker` : réutilisation de la dernière URL quand la barre est masquée.
4. Ajouter `WebActivity` à l'application de test.

**Critères d'acceptation**

- [ ] Table de cas de normalisation : sans schéma, avec chemin, termes de recherche (rejet), schéma non web (rejet), longueur excessive.
- [ ] Barre d'adresse ayant le focus → pas d'URL.
- [ ] E2E-13 : l'URL de `WebActivity` est lue.

**Validation manuelle** — vérifier l'identifiant de barre d'adresse sur chaque navigateur de la table
installé sur le téléphone de test ; corriger la table ; consigner le tableau dans la PR.

---

### M2-05 — Porte de confidentialité et familles d'applications

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| En revue — validation manuelle en attente | M1-05, M2-03 | `engine:privacy` | [confidentialite](../specs/confidentialite.md) · EF-PRIV-01, 03 à 07, 12 |

**À faire**

1. `PrivacyGate` : `checkPackage` (règles PG-01 à PG-06 et PG-14), `checkTree` (PG-07, PG-08), `redact` (PG-11 à PG-13).
2. `sensitive_apps.json` : compléter la liste initiale jusqu'à 250 entrées au moins pour les sept pays
   du lancement, chaque identifiant vérifié ; motifs testés contre des faux positifs.
3. `AppFamilies` : classification d'un paquet en famille (spec §3.4), `app_families.json`, cache,
   lecture du réglage `enabledFamilies`.
4. `testdata/sensitive_cases.tsv` et ses tests.

**Critères d'acceptation**

- [x] Table de décision complète de `checkPackage` (liste × règle utilisateur × catégorie × famille).
- [x] Familles : priorité entre familles, application de SMS par défaut classée « messageries », famille
      désactivée → `APP_FAMILY_DISABLED`, règle « inclure » de l'utilisateur prioritaire.
- [x] Carte, IBAN et numéro de sécurité sociale valides masqués ; invalides et numéros de suivi intacts ; `redact` idempotente.
- [x] Aucun moyen de désactiver la porte : ni réglage, ni paramètre de configuration.

**Validation manuelle** — marqueurs de navigation privée observés sur Chrome et Firefox ; table mise à jour.

**Reste à faire** — la validation manuelle ci-dessus. `sensitive_apps.json` compte 308 identifiants pour
les sept pays, tous vérifiés par `tools/privacy/verify_sensitive_apps.py` (spec §3.2).

---

### M2-06 — Orchestration du pipeline

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M2-01 à M2-05, M4-02 | `engine:capture`, `app` | [capture §6](../specs/capture.md) · INV-04, INV-05 |

**À faire**

1. `CapturePipeline.onStable()` : étapes 1 à 9 de la spec. L'étape 6 (sonde et OCR) appelle les
   interfaces `SecureWindowProbe` et `OcrPolicy` ; en attendant M3, leurs implémentations rendent
   « non sondé » et « pas d'OCR ».
2. `PipelineEvent` et compteurs de `metric_daily` pour chaque issue.
3. Interception de toute exception ; contre-pression (un seul déclenchement en attente).
4. Liaisons Hilt dans `:app`.

**Critères d'acceptation**

- [ ] Test JVM du pipeline avec doubles : chaque étape produit le bon `DropReason` ; l'ordre est respecté
      (aucune lecture de l'arbre si le paquet est refusé).
- [ ] Une exception dans une étape donne `Dropped(ERROR)` et n'atteint pas le service.
- [ ] Sans consentement courant : aucune lecture (E2E-09).

---

### M2-07 — Pause et tuile de réglages rapides

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M2-01 | `engine:capture`, `app` | [capture §7](../specs/capture.md) · EF-CAP-04 |

**À faire**

1. Pause pour 1 h, jusqu'au lendemain 6 h, sans limite ; reprise ; expiration automatique.
2. `PauseTileService` : bascule, sous-titre d'état, appui long vers l'application ; déclaration dans le manifeste.

**Critères d'acceptation**

- [ ] En pause, aucun événement n'atteint le thread de capture (E2E-10).
- [ ] La pause programmée prend fin à l'heure dite (horloge simulée).
- [ ] La tuile reflète l'état, y compris après un changement fait dans l'application.

---

### M2-08 — Tests de bout en bout de la capture

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M2-06, M2-07 | `app`, `fixtureapp` | [tests §3, §4](../06-qualite-tests-ci.md) |

**À faire**

1. Compléter l'application de test : `PasswordActivity`, `EditableActivity`, `ScrollActivity`,
   `SensitiveDataActivity`, `MessagingLikeActivity`.
2. Écrire les scénarios E2E-01, 02, 04, 07 à 13, 18 et 19, synchronisés sur `CapturePipeline.events`.

**Critères d'acceptation**

- [ ] Les scénarios sont verts sur les émulateurs API 30, 34 et 36, sans attente à durée fixe.
- [ ] E2E-01 : le mot distinctif est retrouvé par `SearchStore.lexical` moins d'une seconde après `Stored`.

**Validation manuelle** — une heure d'usage normal sur le téléphone de test : relever dans Réglages →
Diagnostic le nombre de captures, les rejets par motif et la durée médiane de lecture de l'arbre.
