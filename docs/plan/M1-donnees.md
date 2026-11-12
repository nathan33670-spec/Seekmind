# M1 — Socle de données

**Objectif.** Les modèles du domaine, les interfaces entre modules, et une base chiffrée complète :
schéma, plein texte, dépôts, purge, effacement.

**Sortie du jalon.** Tous les dépôts de [stockage §4](../specs/stockage.md) existent et sont testés sur
émulateur ; aucun autre module n'a besoin de connaître Room ou SQL.

---

### M1-01 — Modules `core:common` et `core:model`

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0 | `core:common`, `core:model` | [architecture §3.3, §7, §11](../03-architecture.md) · interfaces des specs |

**À faire**

1. `core:common` : `Clock` (heure murale et temps écoulé), `DispatcherProvider`, `Outcome`, `SmLog`
   (seul point d'accès aux logs ; en `release`, WARN et ERROR ; jamais de contenu utilisateur), `IntRect`.
2. `core:model` : modèles du domaine (`Capture`, `CaptureSummary`, `ScreenMeta`, `RawCapture`,
   `TreeExtraction`, `TextLine`, `DropReason`, `PipelineEvent`, `SourceRef`, `SearchRequest`,
   `SearchResults`, `SearchHit`, `Entitlement`, `AppFamily`, `ModelSpec`, `RewardReadiness`,
   `RewardOutcome`…) tels que définis dans les specs.
3. Interfaces du paquet `port` listées dans l'architecture §3.3, avec leur documentation — dont
   `RewardProvider`, qui ne doit comporter aucun paramètre de donnée utilisateur.
4. Objets `*Config` avec les valeurs par défaut des specs.

**Critères d'acceptation**

- [x] Les deux modules compilent sans dépendance vers Android au-delà du strict nécessaire (`core:common` est en JVM pur).
- [x] Chaque `*Config` a un test qui fige ses valeurs par défaut (un changement de valeur doit être délibéré).
- [x] `scripts/check-forbidden-apis.sh` signale tout usage de `android.util.Log` hors de `core:common`.

---

### M1-02 — Clé de la base

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M1-01 | `core:database` | [sécurité §4](../07-securite-vie-privee.md#4-chiffrement) · [stockage §2](../specs/stockage.md) |

**À faire**

1. `DbKeyStore.getOrCreateKey()` : clé de 32 octets, enveloppée en AES-256-GCM par une clé du Keystore
   (alias `seekmind.db.kek.v1`, StrongBox si disponible, repli sur le TEE), stockée dans `noBackupFilesDir/db.key`.
2. `DbKeyStore.destroy()` : supprime le fichier et l'alias.
3. Cas d'erreur typés : clé absente alors que la base existe, alias invalide, fichier corrompu.

**Critères d'acceptation**

- [x] Deux appels rendent la même clé ; après `destroy()`, une nouvelle clé est créée.
- [x] Un fichier de clé altéré produit l'erreur typée, pas une exception non gérée.
- [x] La clé en clair n'est jamais écrite ni journalisée (revue + garde-fou `db-key-exposure`).

---

### M1-03 — Base chiffrée et schéma v1

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-02 | `core:database` | [stockage §1 à §3, §7](../specs/stockage.md) |

**À faire**

1. Entités Room conformes au SQL normatif ; `FtsSchema` (table FTS5 et déclencheurs) exécuté dans `onCreate`.
2. Ouverture selon stockage §2 (clé brute, WAL, PRAGMA) ; `DatabaseState` (`Ready`, `Unavailable(reason)`).
3. Export du schéma dans `core/database/schemas/` ; infrastructure de migration et premier test `MigrationTestHelper`.
4. Reprendre en tests de régression ceux du prototype S1.

**Critères d'acceptation**

- [ ] Le schéma créé correspond au SQL de la spec (test comparant `sqlite_master`).
- [ ] Insertion, mise à jour de `last_seen_at` et suppression laissent l'index FTS5 cohérent
      (`INSERT INTO capture_fts(capture_fts) VALUES('integrity-check')` ne signale rien).
- [ ] Base illisible → `Unavailable`, sans plantage.

---

### M1-04 — Dépôts de captures, de recherche et de vecteurs

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-03 | `core:database` | [stockage §4, §5](../specs/stockage.md) |

**À faire**

1. `CaptureStore`, `SearchStore`, `VectorStore` : toutes les opérations de la spec, transactions comprises.
2. Requête lexicale de référence (classement, extrait, filtres), construite dynamiquement et paramétrée.
3. Incrément de `meta.index_generation` à chaque suppression de captures, dans la même transaction.

**Critères d'acceptation**

- [ ] `insert` écrit la capture et ses fragments en une transaction ; un échec n'en laisse aucun.
- [ ] `touch` ne modifie pas l'index plein texte.
- [ ] `lexical` : classement attendu sur un petit corpus, filtres de dates et de paquets, insensibilité aux accents.
- [ ] Suppression : fragments et vecteurs supprimés en cascade ; génération incrémentée.
- [ ] `pendingChunks` rend les plus récents d'abord ; `saveEmbeddings` passe les fragments à l'état « vectorisé ».
- [ ] `binaryCodesAfter` pagine dans l'ordre d'insertion des vecteurs.

---

### M1-05 — Dépôts annexes et réglages

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-03 | `core:database` | [stockage §3.1, §4](../specs/stockage.md) · [capture §7, §8](../specs/capture.md) |

**À faire**

1. `AppRuleStore`, `SecureProbeStore`, `MonetizationStore` (registre de crédits, licence),
   `SearchHistoryStore`, `MetricsStore` (incrément par jour et par clé).
2. `SettingsRepository` (DataStore Preferences) : consentement (version, date), familles d'applications
   activées, pause (`pausedUntil`), rétention, budget de stockage, verrouillage, modèle actif,
   historique. Exposé en `Flow` typés.

**Critères d'acceptation**

- [ ] Chaque dépôt a ses tests instrumentés (lecture, écriture, cas limites).
- [ ] `SettingsRepository` : valeurs par défaut conformes aux specs ; aucune donnée capturée n'y transite (INV-06).

---

### M1-06 — Maintenance et effacement

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-04, M1-05 | `core:database` | [stockage §6](../specs/stockage.md) · EF-PRIV-08, EF-PRIV-09 |

**À faire**

1. `MaintenanceWorker` (WorkManager, 24 h, batterie non faible) : âge, budget, tables annexes, compactage, optimisation FTS.
2. `WipeService.wipeEverything()` : ferme la base, supprime fichiers, clé et alias, réinitialise les réglages.
3. Planification du travail au démarrage de l'application.

**Critères d'acceptation**

- [ ] Avec une horloge simulée : les captures hors rétention disparaissent ; rétention illimitée → rien n'est supprimé.
- [ ] Dépassement du budget → suppression des plus anciennes jusqu'au retour sous le budget.
- [ ] Après `wipeEverything` : aucun fichier de base ni de clé ; la réouverture crée une base neuve.
- [ ] Aucune tâche WorkManager n'emploie de contrainte de réseau : la variante `offline` n'a pas
      `ACCESS_NETWORK_STATE` ([prototype S2](../prototypes/S2-mlkit-sans-telemetrie.md), constat 3).

---

### M1-07 — Outils de test et premiers bancs de mesure

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-04 | `core:testing`, `benchmark` | [tests §2, §6](../06-qualite-tests-ci.md) · ENF-PERF-03, ENF-PERF-05 |

**À faire**

1. `core:testing` : `FakeClock`, `TestDispatchers`, base chiffrée temporaire, fabriques de modèles,
   `FakeEmbeddingEngine` (vecteurs déterministes dérivés d'un hachage du texte).
2. Générateur de base synthétique : N captures aux textes réalistes (gabarits), N vecteurs pseudo-aléatoires normalisés.
3. Module `benchmark` (Microbenchmark) : insertion d'une capture ; requête lexicale sur 100 000 captures.

**Critères d'acceptation**

- [ ] Le générateur produit 100 000 captures en moins de 2 minutes sur émulateur.
- [ ] Les deux bancs publient leurs résultats en artefact de CI ; les valeurs sont consignées dans `docs/mesures/`.
