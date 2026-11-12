# M5 — Recherche hybride

**Objectif.** Répondre à une requête en langage naturel par un classement qui combine mots-clés et sens,
en comprenant les dates et les applications — et mesurer la qualité.

**Sortie du jalon.** `./gradlew :engine:search:evalSearch` vert au-dessus des seuils de EF-SRCH-01 à 03 ;
cibles ENF-PERF-03 et 04 mesurées.

---

### M5-01 — Analyse de requête

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-01 | `engine:search` | [recherche §2](../specs/recherche.md) |

**À faire**

1. `QueryParser` : mise en forme, découpage en mots, mots vides FR et EN (ressources), jetons en forme de code, profil.
2. Points d'extension pour les expressions temporelles (M5-02) et le filtre d'application (M5-03).

**Critères d'acceptation**

- [ ] Table de cas : mots vides retirés ; requête faite uniquement de mots vides conservée ; codes reconnus ; profils `DEFAULT`, `CODE`, `NATURAL`.
- [ ] Les filtres explicites de la requête priment sur ceux extraits du texte.

---

### M5-02 — Expressions temporelles

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-01 | `engine:search`, `testdata` | [recherche §3](../specs/recherche.md) · EF-SRCH-04 |

**À faire**

1. `testdata/temporal_cases.tsv` : au moins 120 cas FR et EN (expression, date de référence, fuseau, intervalle attendu).
2. Analyseur : chaque ligne des deux tableaux de la spec, combinaisons jour + moment, nombres en chiffres et en lettres.
3. Retrait de l'expression et de ses mots de liaison du texte de la requête ; libellé pour l'affichage.

**Critères d'acceptation**

- [ ] Tous les cas du fichier passent, avec horloge et fuseau fixés.
- [ ] Cas particuliers couverts : changement d'heure, passage d'année, « mardi » un mardi, « soir » à cheval sur deux jours.
- [ ] Une requête sans expression temporelle n'est pas modifiée.

---

### M5-03 — Catalogue et filtre d'applications

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-01 | `engine:search` | [recherche §4](../specs/recherche.md) · EF-SRCH-05 |

**À faire**

1. `AppCatalog` : applications lançables (libellé, forme de comparaison), alias (ressource JSON), mise à
   jour à l'installation ou à la désinstallation.
2. Extraction « préposition + application » ; plus longue correspondance d'abord.

**Critères d'acceptation**

- [ ] « sur Instagram », « dans WhatsApp », « on YouTube », alias (« insta ») → bon paquet, expression retirée du texte.
- [ ] Sans préposition, rien n'est extrait (« chrome » reste un mot de la requête).
- [ ] Application non installée → pas de filtre.

---

### M5-04 — Recherche par mots-clés

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-01, M1-04 | `engine:search` | [recherche §5](../specs/recherche.md) · [stockage §5](../specs/stockage.md) · EF-SRCH-02 |

**À faire**

1. Constructeur d'expression FTS5 : échappement, `OR`, préfixe du dernier jeton, traitement des codes.
2. `LexicalSearcher` : appel de `SearchStore.lexical`, conversion de l'extrait balisé en intervalles à surligner.

**Critères d'acceptation**

- [ ] Entrées hostiles (guillemets, parenthèses, `NEAR`, `*`, `:`) : aucune erreur de syntaxe FTS5, aucune injection.
- [ ] Recherche sans accents retrouvant un texte accentué, et l'inverse.
- [ ] Un code exact (`SUMMER20`) arrive en première position sur le corpus de test.

---

### M5-05 — Index vectoriel

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-04, M4-04 | `engine:search` | [recherche §6](../specs/recherche.md) · [ADR-0005](../adr/0005-recherche-vectorielle-en-memoire.md) |

**À faire**

1. `VectorIndex` : tableaux primitifs, chargement paginé, mise à jour incrémentale, invalidation par génération, libération.
2. Présélection par distance de Hamming avec filtres ; rescore int8 ; plancher ; regroupement par capture.

**Critères d'acceptation**

- [ ] Sur 20 000 vecteurs aléatoires normalisés : le rappel des 10 plus proches voisins exacts parmi les
      résultats (présélection de 200 puis rescore) est mesuré et consigné ; cible ≥ 0,90.
- [ ] Les filtres de dates et de paquets sont appliqués pendant le parcours.
- [ ] Un vecteur ajouté apparaît à la recherche suivante ; une suppression déclenche le rechargement.
- [ ] Aucune allocation par vecteur pendant le parcours (tableaux primitifs uniquement).

---

### M5-06 — Recherche hybride

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-02 à M5-05 | `engine:search` | [recherche §1, §7 à §9](../specs/recherche.md) · EF-SRCH-01, 03, 07, 08 |

**À faire**

1. `HybridSearcher` (implémente `SearchEngine`) : branches parallèles, émission lexicale puis hybride, annulation.
2. Fusion RRF, pondérations par profil, prime de récence, repli des quasi-doublons, extraits.
3. Assouplissement des filtres ; mode dégradé sans moteur sémantique.
4. `SearchCache`.

**Critères d'acceptation**

- [ ] Fusion : tables de rangs calculées à la main ; un document présent dans une seule liste est classé ; égalités départagées par la date.
- [ ] Deux émissions dans l'ordre `LEXICAL` puis `HYBRID` ; l'annulation interrompt les deux branches.
- [ ] Assouplissement : `TIME` puis `ALL`, avec l'indication correspondante.
- [ ] Moteur indisponible → une seule émission, `semanticAvailable = false`.
- [ ] Test instrumenté de la chaîne complète sur une base chiffrée avec `FakeEmbeddingEngine`.

---

### M5-07 — Évaluation et calibration

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-06 | `engine:search`, `tools/eval`, `testdata/eval` | [recherche §11](../specs/recherche.md) · [tests §5](../06-qualite-tests-ci.md#5-jeux-de-référence) · EF-SRCH-01 à 03 |

**À faire**

1. `tools/eval/gen_corpus.py` : environ 300 captures synthétiques réalistes en français et en anglais,
   réparties sur une quinzaine d'applications et 60 jours, à partir de gabarits déterministes.
2. `testdata/eval/queries.jsonl` : environ 60 requêtes annotées, réparties entre exact, code,
   paraphrase, temporel, application, et bilingue (requête dans une langue, contenu dans l'autre).
3. Tâche `:engine:search:evalSearch` (JVM, vrai modèle) : Rappel@1, Rappel@5, MRR, par type et pour les
   trois configurations ; rapport JSON ; échec sous les seuils.
4. Calibrer `semanticFloor`, les pondérations par profil et `binaryTopK` ; consigner les essais.
5. Ajouter à `tools/model/build_model.py` le contrôle de non-régression du modèle quantifié sur ce jeu.

**Critères d'acceptation**

- [ ] Hybride : Rappel@5 ≥ 0,85 et MRR ≥ 0,70 ; codes : Rappel@1 ≥ 0,90 ; paraphrases : Rappel@5 ≥ 0,75.
- [ ] L'hybride fait au moins aussi bien que chacune des deux méthodes seules sur le Rappel@5 global.
- [ ] Si un seuil est hors d'atteinte, le rapport explique pourquoi et propose le seuil révisé — décision du porteur du projet.

---

### M5-08 — Mesures à 100 000 captures

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-06, M1-07 | `benchmark` | [tests §6](../06-qualite-tests-ci.md#6-mesures-de-performance) · ENF-PERF-03, ENF-PERF-04 |

**À faire**

1. Bancs de mesure sur la base synthétique de 100 000 captures et 100 000 vecteurs : recherche lexicale,
   chargement de l'index, présélection, rescore, fusion, chaîne hybride.
2. Consigner les résultats dans `docs/mesures/`.

**Critères d'acceptation**

- [ ] Résultats publiés pour l'émulateur de CI ; cibles ENF-PERF-03 et 04 comparées.

**Validation manuelle** — mêmes bancs sur un téléphone moyen et un téléphone bas de gamme ; tableau dans la PR.
