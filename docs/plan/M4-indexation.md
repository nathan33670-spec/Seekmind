# M4 — Indexation et embeddings

**Objectif.** Normaliser, dédupliquer et découper les captures, puis calculer leurs vecteurs en
arrière-plan sans gêner l'utilisateur.

**Sortie du jalon.** Scénarios E2E-08 et E2E-17 verts ; tokenizer à parité avec la référence ; lots
d'embeddings soumis au gouverneur.

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
| À faire | M0-10, M1-01 | `engine:embedding` | [embeddings §3](../specs/embeddings.md) · risque R-12 |

**À faire**

1. Lecteur de `vocab.bin` vers des structures compactes (budget mémoire : 16 Mo).
2. Normalisation, pré-découpage, segmentation de Viterbi, jetons spéciaux, troncature.
3. Test de parité sur `testdata/tokenizer_golden.jsonl`, avec rapport des phrases divergentes.

**Critères d'acceptation**

- [ ] Parité exacte sur au moins 99,5 % des 1 000 phrases ; les divergences sont listées et expliquées dans la PR.
- [ ] Cas limites : chaîne vide, espaces seules, émojis, caractères inconnus consécutifs (un seul jeton inconnu), texte plus long que la limite.
- [ ] Tokenisation d'un passage de 800 caractères en moins de 5 ms sur JVM ; mémoire du vocabulaire mesurée.

Si le seuil n'est pas atteint après analyse des divergences : s'arrêter, passer le ticket à `Bloqué` et
proposer un ADR (bibliothèque native de tokenisation).

---

### M4-04 — Moteur d'embeddings et codage des vecteurs

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-03, M0-09 | `engine:embedding` | [embeddings §4 à §6](../specs/embeddings.md) |

**À faire**

1. `OrtEmbeddingEngine` (chemin de chargement retenu par le prototype S4) : préfixes, lots, agrégation, normalisation.
2. `EmbeddingEngineProvider` : partage, fermeture après inactivité et sous pression mémoire, sérialisation des appels.
3. `VectorCodec` : int8 à échelle fixe et code binaire.
4. Constante `modelId`.

**Critères d'acceptation**

- [ ] Cosinus ≥ 0,99 avec chaque vecteur de `testdata/embedding_golden.json` (test instrumenté).
- [ ] Les préfixes `query: ` et `passage: ` sont appliqués par le moteur (test : deux vecteurs différents pour le même texte).
- [ ] `VectorCodec` : aller-retour, écrêtage, correspondance bit à bit du code binaire.
- [ ] La session se ferme après le délai d'inactivité ; un nouvel appel la recharge.
- [ ] Mesures de la spec §7 relevées sur émulateur et consignées.

---

### M4-05 — Lots d'embeddings

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-04, M4-02, M3-04 | `engine:index` | [indexation §7](../specs/indexation.md) · EF-IDX-02 · ENF-BAT-01 |

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

### M4-06 — Empaquetage du modèle

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M4-04 | `engine:embedding`, `app`, CI | [embeddings §2](../specs/embeddings.md) · [architecture §9](../03-architecture.md) · ENF-STO-02 |

**À faire**

1. Le build `release` échoue si le modèle manque ou ne correspond pas à `model.lock.json`.
2. Vérification de l'intégrité du modèle au premier lancement après installation ou mise à jour.
3. Mesure de la taille de téléchargement de l'AAB (`bundletool get-size total`) dans la CI.

**Critères d'acceptation**

- [ ] L'AAB `release` contient le modèle non compressé ; taille de téléchargement mesurée et ≤ 200 Mo.
- [ ] Modèle absent ou altéré : la recherche par mots-clés fonctionne, la recherche sémantique se
      déclare indisponible, sans plantage.
