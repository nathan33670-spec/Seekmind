# M4 — Indexation, embeddings et packs de langue

**Objectif.** Normaliser, dédupliquer et découper les captures, calculer leurs vecteurs en
arrière-plan sans gêner l'utilisateur, et permettre de changer de modèle (pack de langue).

**Sortie du jalon.** Scénarios E2E-08, E2E-17 et E2E-21 verts ; tokenizer à parité avec la référence
pour les deux vocabulaires ; lots d'embeddings soumis au gouverneur.

M4-01 et M4-02 ne dépendent que de M1 et précèdent M2-06. M4-03 et M4-04 peuvent démarrer dès la fin de M0.

---

### M4-01 — Normalisation et SimHash

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-01 | `engine:index` | [indexation §2, §3](../specs/indexation.md) |

**À faire**

1. `TextNormalizer.normalize`.
2. `SimHash.of` et la distance de Hamming.
3. Jeu de paires pour vérifier le seuil : écrans identiques, une ligne modifiée, positions de défilement
   voisines, écrans sans rapport.

**Critères d'acceptation**

- [ ] Normalisation : table de cas ; idempotence ; les caractères U+0002 et U+0003 sont retirés.
- [ ] SimHash : déterminisme ; texte identique → distance 0 ; textes sans rapport → distance moyenne proche de 32.
- [ ] Le rapport de la PR donne la distribution des distances par catégorie de paires et confirme (ou corrige) le seuil de 9.

---

### M4-02 — Découpage et ingestion

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-01, M1-04 | `engine:index` | [indexation §1, §4 à §6](../specs/indexation.md) · EF-CAP-07, EF-IDX-01 |

**À faire**

1. `Chunker.split` : positions dans le texte, limites, plafond.
2. `IngestService` (implémente `CaptureSink`) : normalisation, SimHash, déduplication avec cache par
   paquet, insertion transactionnelle.
3. Demande de planification du travail `embedding-charging` quand des fragments sont en attente
   (l'exécution arrive en M4-05).

**Critères d'acceptation**

- [ ] `Chunker` : fragments ≤ 800 caractères, lignes entières, ligne longue coupée proprement, au plus 4
      fragments, positions exactes (la concaténation des tranches redonne le texte couvert).
- [ ] Déduplication : chacune des quatre conditions est nécessaire (quatre tests négatifs, un positif).
- [ ] Doublon → `touch` et `Deduplicated` ; nouveau → une capture et ses fragments en attente.
- [ ] Banc de mesure : `ingest` ≤ 15 ms au 95ᵉ centile (ENF-PERF-05).

---

### M4-03 — Tokenizer

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0-10, M1-01 | `engine:embedding` | [embeddings §3](../specs/embeddings.md) · risque R-12 |

**À faire**

1. Lecteur de `vocab.bin` vers des structures compactes (budget mémoire : 16 Mo pour le plus grand
   vocabulaire). Le tokenizer ne suppose rien du modèle : il charge le vocabulaire qu'on lui donne.
2. Normalisation, pré-découpage, segmentation de Viterbi, jetons spéciaux, troncature.
3. Tests de parité sur `testdata/tokenizer_golden_fr.jsonl` et `tokenizer_golden_multi.jsonl`, avec
   rapport des phrases divergentes.

**Critères d'acceptation**

- [x] Parité exacte sur au moins 99,5 % des 1 000 phrases, **pour chacun des deux vocabulaires** ; les
      divergences sont listées et expliquées dans la PR.
- [x] Cas limites : chaîne vide, espaces seules, émojis, caractères inconnus consécutifs (un seul jeton inconnu), texte plus long que la limite.
- [x] Tokenisation d'un passage de 800 caractères en moins de 5 ms sur JVM ; mémoire du vocabulaire mesurée.

Si le seuil n'est pas atteint après analyse des divergences : s'arrêter, passer le ticket à `Bloqué` et
proposer un ADR (bibliothèque native de tokenisation).

---

### M4-04 — Moteur d'embeddings et codage des vecteurs

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-03, M0-09 | `engine:embedding` | [embeddings §4 à §6](../specs/embeddings.md) |

**À faire**

1. `OrtEmbeddingEngine` (chemin de chargement retenu par le prototype S4) : préfixes, lots, agrégation,
   normalisation ; chargement depuis un asset (modèle embarqué) ou depuis un chemin de fichier (pack).
2. `EmbeddingEngineProvider` : partage, fermeture après inactivité et sous pression mémoire, sérialisation des appels.
3. `VectorCodec` : int8 à échelle fixe et code binaire.
4. `modelId` fourni par la description du modèle chargé (`ModelSpec`).

**Critères d'acceptation**

- [ ] Cosinus ≥ 0,99 avec chaque vecteur de `testdata/embedding_golden_fr.json` (test instrumenté).
- [ ] Les préfixes `query: ` et `passage: ` sont appliqués par le moteur (test : deux vecteurs différents pour le même texte).
- [ ] `VectorCodec` : aller-retour, écrêtage, correspondance bit à bit du code binaire.
- [ ] La session se ferme après le délai d'inactivité ; un nouvel appel la recharge.
- [ ] Mesures de la spec §8 relevées sur émulateur et consignées.

---

### M4-05 — Lots d'embeddings

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-04, M4-02, M3-04 | `engine:index` | [indexation §7](../specs/indexation.md) · [embeddings §6](../specs/embeddings.md) · EF-IDX-02 · ENF-BAT-01 |

**À faire**

1. `EmbeddingWorker` : boucle par lots, échéance de 9 minutes, obéissance au gouverneur, pause entre lots hors charge.
2. Planification : travail périodique (batterie non faible) et travail unique en charge.
3. Gestion des échecs par fragment ; reprise ; compteurs de diagnostic.
4. Changement de modèle : comparaison de `modelId` et réinitialisation.
5. Calibrer l'échelle `int8Scale` sur les vecteurs des 1 000 phrases de référence (moins de 0,1 % de composantes écrêtées).

**Critères d'acceptation**

- [ ] Avec le pilote de test de WorkManager et `FakeEmbeddingEngine` : la file se vide ; les plus récents d'abord.
- [ ] Gouverneur en état « suspendu » → le travail s'arrête sans erreur et reprend à la période suivante.
- [ ] Trois échecs sur un fragment → état « en échec », le reste du lot continue.
- [ ] Changement de `modelId` → vecteurs vidés, fragments remis en attente, recherche lexicale intacte.
- [ ] E2E-17 vert avec le vrai modèle.

---

### M4-06 — Empaquetage des modèles

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-04 | `engine:embedding`, `modelpack_multi`, `app`, CI | [embeddings §2, §5.2](../specs/embeddings.md) · [architecture §9](../03-architecture.md) · ENF-STO-02 |

**À faire**

1. Modèle `fr` : asset non compressé de `engine:embedding`. Le build `release` échoue s'il manque ou ne
   correspond pas à `model.lock.json`.
2. Module `:modelpack_multi` : pack d'assets à livraison à la demande contenant le jeu `multi` ; ajouté
   à `settings.gradle.kts` et au lot d'application.
3. Vérification de l'intégrité du modèle embarqué au premier lancement après installation ou mise à jour.
4. Mesure de la taille de téléchargement du lot (`bundletool get-size total`) dans la CI.

**Critères d'acceptation**

- [ ] Le lot `release` contient le modèle `fr` non compressé et le pack `multi` séparé ; taille de
      téléchargement de base mesurée et ≤ 100 Mo.
- [ ] Modèle absent ou altéré : la recherche par mots-clés fonctionne, la recherche sémantique se
      déclare indisponible, sans plantage.

---

### M4-07 — Catalogue de modèles et packs de langue

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-05, M4-06 | `engine:embedding`, `fixtureapp` | [embeddings §5](../specs/embeddings.md) · [ADR-0010](../adr/0010-modele-francais-et-packs-de-langue.md) · EF-LANG-01 à 04 |

**À faire**

1. `ModelCatalog` : entrées `fr` et `multi`, modèle actif (réglage), états des packs.
2. `FileModelPackInstaller` : import d'une archive `.smpack` par le sélecteur de fichiers, vérification
   des empreintes, copie dans le stockage privé, suppression.
3. `PlayModelPackInstaller` : pack d'assets à la demande (`asset-delivery`), suivi d'état, confirmation
   système pour les données mobiles, emplacement des fichiers, suppression. Sélection de l'installateur
   selon la provenance de l'installation.
4. `activate(id)` : vérification, changement du modèle actif, fermeture de la session, remise à zéro des vecteurs.
5. Petit pack de test (`testdata/`) pour les tests automatisés : mêmes fichiers que `fr` sous un autre identifiant de modèle.

**Critères d'acceptation**

- [ ] Avec un installateur factice : états `NotInstalled → Downloading → Installed`, échec, suppression
      du pack actif (retour à `fr`).
- [ ] Archive valide importée ; empreinte incorrecte ou archive incomplète refusée, rien n'est copié.
- [ ] E2E-21 : après activation du pack de test, les vecteurs portent le nouvel identifiant de modèle ;
      la recherche par mots-clés répond pendant le recalcul.
- [ ] Aucun téléchargement n'est lancé sans action de l'utilisateur (test : aucun appel à l'installateur au démarrage).
- [ ] Les listes blanches de permissions des deux variantes admettent `FOREGROUND_SERVICE_DATA_SYNC` et rien d'autre de nouveau.

**Validation manuelle** — sur la piste de test interne de Google Play : téléchargement du pack
multilingue, activation, recherche dans une autre langue, suppression.
