# Spec — Recherche (`engine:search`)

| | |
|---|---|
| **Exigences** | EF-SRCH-01 à 09, ENF-PERF-03, ENF-PERF-04 |
| **Dépend de** | `core:database`, `engine:embedding` |
| **Tickets** | M5-01 à M5-08 |
| **Voir aussi** | [ADR-0005](../adr/0005-recherche-vectorielle-en-memoire.md) |

## 1. Contrat

```kotlin
interface SearchEngine {
    /** Émet d'abord les résultats par mots-clés, puis les résultats hybrides. */
    fun search(request: SearchRequest): Flow<SearchResults>
}

data class SearchRequest(
    val rawQuery: String,
    val filters: SearchFilters = SearchFilters(),   // filtres choisis par les pastilles de l'interface
    val limit: Int = 50,
    val now: Long,
    val zone: ZoneId,
)

data class SearchFilters(val range: TimeRange? = null, val packages: Set<String> = emptySet())
data class TimeRange(val from: Long, val to: Long)                // bornes incluses, époque en ms

data class SearchResults(
    val stage: Stage,                    // LEXICAL ou HYBRID
    val parsed: ParsedQuery,
    val hits: List<SearchHit>,
    val relaxed: Relaxation?,            // TIME, ALL ou null
    val semanticAvailable: Boolean,
)

data class SearchHit(
    val captureId: Long,
    val score: Double,
    val packageName: String,
    val windowTitle: String?,
    val url: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val snippet: Snippet,                // texte + intervalles à surligner
    val textSource: Int,
    val matchedBy: Set<MatchKind>,       // LEXICAL, SEMANTIC
    val similarCount: Int,               // résultats quasi identiques repliés sous celui-ci
)
```

## 2. Analyse de la requête

`QueryParser.parse(raw, now, zone, apps): ParsedQuery`, Kotlin pur. Les mots vides et l'analyseur
temporel (`TemporalExtractor`, §3) sont donnés à la construction ; `apps` est un `AppExtractor` (§4),
construit à partir des applications installées, donc passé à chaque appel. Chacun renvoie l'intervalle
ou les paquets reconnus et le texte privé de l'expression, ou `null`. La surcharge
`parse(request: SearchRequest, apps)` applique en plus les filtres explicites.

```kotlin
data class ParsedQuery(
    val text: String,                // requête sans les expressions de temps et d'application
    val tokens: List<String>,        // mots utiles (sans mots vides)
    val codeTokens: List<String>,    // jetons en forme de code
    val range: TimeRange?,
    val rangeLabel: String?,         // « mardi soir », pour l'affichage
    val packages: Set<String>,
    val profile: QueryProfile,       // DEFAULT, CODE, NATURAL
)
```

Étapes :

1. Mise en forme : espaces réduites, guillemets et apostrophes typographiques normalisés.
2. Extraction des expressions temporelles (§3), la plus longue d'abord ; l'expression est retirée du texte.
3. Extraction de l'application (§4) ; l'expression est retirée du texte.
4. Découpage en mots (mêmes règles que FTS5 : lettres et chiffres Unicode, minuscules, sans diacritiques ;
   comme `remove_diacritics 2`, seules les lettres latines perdent leurs diacritiques). Un mot répété
   n'est gardé qu'une fois.
5. Retrait des mots vides (listes française et anglaise en ressources, environ 150 chacune, incluant
   les verbes de perception : vu, aperçu, lu, reçu, trouvé, seen, saw, read, got). Si tous les mots sont
   vides, ils sont tous conservés.
6. Jeton **en forme de code** : au moins 5 caractères, au moins un chiffre, composé de lettres,
   chiffres, tirets ou tirets bas (`SUMMER20`, `6A12345678901`, `1Z999AA10123456784`).
7. Profil : `CODE` s'il existe un jeton en forme de code ; `NATURAL` si la requête compte au moins
   5 mots utiles ; sinon `DEFAULT`.

Les filtres explicites de `SearchRequest.filters` priment sur ceux extraits du texte : un intervalle
explicite remplace l'intervalle extrait (et son libellé), des paquets explicites remplacent les paquets
extraits. Les expressions reconnues sont retirées du texte dans tous les cas.

Un jeton en forme de code est cherché dans les mots du texte séparés par les espaces, ponctuation
d'encadrement retirée (`(PROMO10)` → `PROMO10`), et garde sa casse.

## 3. Expressions temporelles

Toutes les bornes sont calculées dans le fuseau `zone`, à partir de `now`. Semaine du lundi au dimanche.

| Français | Anglais | Intervalle |
|---|---|---|
| aujourd'hui | today | Jour courant |
| hier | yesterday | Jour précédent |
| avant-hier | the day before yesterday | Deux jours avant |
| tout à l'heure | earlier, a moment ago | 6 dernières heures |
| récemment, dernièrement | recently, lately | 14 derniers jours |
| lundi … dimanche | monday … sunday | Occurrence la plus récente, jour courant inclus |
| lundi dernier … | last monday … | Occurrence la plus récente, jour courant exclu |
| cette semaine | this week | Du lundi au moment présent |
| la semaine dernière | last week | Semaine précédente |
| ce week-end | this weekend | Dernier samedi-dimanche commencé |
| le week-end dernier | last weekend | Samedi-dimanche précédent |
| ce mois-ci | this month | Du 1er au moment présent |
| le mois dernier | last month | Mois civil précédent |
| il y a N jours | N days ago | Le jour correspondant |
| il y a N semaines | N weeks ago | Fenêtre de 7 jours centrée sur cette date |
| il y a N mois | N months ago | Fenêtre de 30 jours centrée sur cette date |
| en janvier … décembre | in january … december | Occurrence la plus récente du mois |
| le 12 mars, 12 mars 2026 | march 12, 12 march 2026 | Ce jour (année la plus récente si absente) |
| 12/03, 12/03/2026, 2026-03-12 | idem | Ce jour (ordre jour/mois en français, selon la locale en anglais) |

Moments de la journée, combinables avec un jour (« mardi soir », « hier matin », « this morning ») :

| Français | Anglais | Heures |
|---|---|---|
| matin | morning | 05:00 – 12:00 |
| midi | noon, lunchtime | 11:00 – 14:00 |
| après-midi | afternoon | 12:00 – 18:00 |
| soir | evening, tonight | 18:00 – 02:00 le lendemain |
| nuit | night | 22:00 – 06:00 le lendemain |

« ce matin », « ce soir », « cette nuit » s'appliquent au jour courant. N s'écrit en chiffres ou en
lettres de un à douze. Les mots de liaison adjacents (« vu », « le », « du », « on », « at ») partent
avec l'expression. Une capture correspond à l'intervalle si `[first_seen_at, last_seen_at]` le recoupe.

La table complète des cas, avec date de référence et intervalle attendu, est `testdata/temporal_cases.tsv`,
produite par `tools/testdata/gen_temporal_cases.py`, qui calcule les intervalles indépendamment de
l'analyseur Kotlin (`TemporalParser`).

Précisions (M5-02) :

- Les bornes sont incluses. Un jour va de 00:00 à 23:59:59,999 ; « cette semaine », « ce mois-ci »
  s'arrêtent au moment présent.
- « Tout à l'heure » et « récemment » sont des durées absolues avant le moment présent (6 h, 14 × 24 h),
  insensibles au changement d'heure ; les jours et les moments suivent l'heure locale, changement
  d'heure compris.
- « ce week-end » : le dernier samedi-dimanche commencé ; « le week-end dernier » : celui d'avant
  pendant un week-end, le même que « ce week-end » en semaine (le seul week-end passé récent).
- « cette nuit » dite avant midi désigne la nuit qui vient de finir (la veille, 22:00 – 06:00) ; après
  midi, la nuit qui vient. « last night » : la nuit de la veille. Un moment seul, sans jour ni
  « ce »/« this » (« le soir de la fête »), n'est pas extrait.
- Un jour et un moment se combinent avec ou sans liaison : « hier soir », « mardi après-midi »,
  « hier dans la soirée », « lundi dernier au soir », « il y a 3 jours le matin », « tuesday in the
  afternoon », « yesterday at noon ».
- « en mars », « in march » demandent la préposition ; « en mars 2025 » vise cette année-là. Seul, un
  nom de mois est un mot de la requête (« mars attaque », « may I help »).
- Dates numériques : jour/mois, sauf si l'analyseur est construit avec `monthFirst` (anglais des
  États-Unis, d'après la langue du téléphone) ; année sur quatre chiffres ; une date impossible
  (31/02) n'est pas extraite.
- N vaut de 1 à 999 en chiffres, de un à douze en lettres (« une », « a », « an » comptent pour 1).
- Mots de liaison retirés avec l'expression quand ils la précèdent, deux au plus : « vu », « vue »,
  « vus », « vues », « le », « la », « du », « depuis », « on », « at », « from », « seen », « saw ».
  Le libellé (`rangeLabel`) est l'expression telle que tapée, sans ces mots.

## 4. Filtre d'application

`AppCatalog` fournit, pour chaque application lançable : paquet, libellé, forme de comparaison
(minuscules, sans diacritiques) et alias (ressource JSON : `insta` → Instagram, `yt` → YouTube,
`wa` → WhatsApp, `fb` → Facebook, `twitter`/`x` → X, `gmaps` → Google Maps…).

Motif reconnu : une préposition (`sur`, `dans`, `via`, `depuis`, `de l'appli`, `on`, `in`, `from`)
suivie d'un libellé ou d'un alias, la plus longue correspondance d'abord. Sans préposition, rien n'est
extrait (« chrome » peut être un mot de la requête) ; les pastilles de l'interface couvrent ce cas.

Précisions (M5-03) :

- `AndroidAppCatalog` lit les applications lançables (`MAIN` + `LAUNCHER`, déclarées dans
  `<queries>`) hors du thread principal, les garde en mémoire et les relit après une installation,
  une mise à jour ou une désinstallation (diffusions `PACKAGE_*`). `AppMatcher`, Kotlin pur, fait
  l'extraction.
- Prépositions : `sur`, `dans`, `via`, `depuis`, `de l'appli`, `de l'application`, `on`, `in`, `from`.
  La comparaison porte sur les mots du libellé (mêmes règles que FTS5 : minuscules, sans diacritiques) :
  « dans meteo » trouve « Météo », « sur Google Maps » l'emporte sur « sur Google ».
- Alias : `res/raw/search_app_aliases.json`, alias → paquets. Un alias ne vaut que pour les paquets
  installés : une application absente du téléphone ne donne aucun filtre. Deux applications de même
  libellé donnent un filtre sur les deux paquets.

## 5. Recherche par mots-clés

`LexicalSearcher` construit l'expression FTS5 :

- chaque jeton est mis entre guillemets doubles (guillemets internes doublés) : aucune syntaxe FTS5 ne
  peut être injectée par la requête ;
- les jetons sont reliés par `OR` : le classement BM25 favorise les captures qui en contiennent le plus ;
- le dernier jeton reçoit un `*` de préfixe s'il compte au moins 3 caractères ;
- un jeton en forme de code est ajouté tel quel **et** en préfixe.

Exemple : `resto italien pâtes truffe` → `"resto" OR "italien" OR "pates" OR "truffe"*`.

Requête SQL, pondération des colonnes et extrait : [stockage §5](stockage.md). Limite : 100 lignes.

Précisions (M5-04) : les jetons sont ceux de `ParsedQuery.tokens`, déjà en forme de comparaison ; un
terme répété n'est écrit qu'une fois ; une requête sans mot (ponctuation seule) ne lance aucune
recherche. L'extrait renvoyé par FTS5 est débarrassé de ses marqueurs `char(2)` / `char(3)`, qui
deviennent les intervalles à surligner de `Snippet` (bornes incluses) ; un marqueur laissé ouvert par
la coupe de l'extrait se ferme à sa fin.

## 6. Recherche vectorielle

### 6.1 Index en mémoire

`VectorIndex` tient, pour chaque vecteur, dans des tableaux primitifs parallèles :

| Tableau | Type | Par vecteur |
|---|---|---|
| Codes binaires | `LongArray` | 6 × 8 octets |
| Vecteurs int8 (rescore en mémoire) | `ByteArray` | 384 octets |
| Identifiant de vecteur, identifiant de capture | `LongArray` ×2 | 16 octets |
| Première et dernière vue | `LongArray` ×2 | 16 octets |
| Indice de paquet | `IntArray` | 4 octets |

Soit 468 octets par vecteur : 47 Mo pour 100 000 vecteurs, 140 Mo pour 300 000. Les vecteurs int8 sont
gardés en mémoire depuis le 13 novembre 2026 (décision du porteur) : lus en base à chaque recherche,
les 400 candidats coûtaient de 29 à 54 ms à 20 000 vecteurs (§6.3), au-delà du budget d'ENF-PERF-04.
L'index n'existe que pendant l'usage de l'écran de recherche (libéré 60 s après), pas dans le service
de capture (ENF-MEM-01).

- **Chargement** paresseux, au premier usage, hors thread principal, par pages de 5 000 lignes
  (`VectorStore.binaryCodesAfter`). Cible : ≤ 500 ms pour 100 000 vecteurs.
- **Mise à jour incrémentale** avant chaque recherche : lecture des vecteurs dont l'identifiant dépasse
  le dernier chargé.
- **Invalidation** : si `meta.index_generation` a changé (suppression de captures), rechargement complet.
- L'index est libéré quand l'interface quitte le premier plan depuis plus de 60 s.

### 6.2 Recherche

1. `q = EmbeddingEngine.embedQuery(parsed.text)` ; code binaire de `q`.
2. **Présélection** : parcours linéaire ; pour chaque vecteur passant les filtres (dates, paquets),
   distance de Hamming sur 6 mots (`Long.bitCount`) ; conservation des `binaryTopK` (400) plus proches
   dans un tas borné.
3. **Rescore** en mémoire, sur les vecteurs int8 de l'index (aucune lecture en base) ; similarité
   `(Σ q[i] × d[i]) / S`.
4. Rejet des candidats sous `semanticFloor`.
5. Regroupement par capture (meilleur fragment) ; conservation des `semanticTopK` (100) meilleures captures.

Le modèle produit des similarités resserrées (souvent entre 0,70 et 0,90, y compris pour des textes sans
rapport) : `semanticFloor` ne peut pas être fixé a priori. Valeur de départ 0,78, **calibrée au ticket
M5-07** sur le jeu d'évaluation (plus haute valeur qui ne dégrade pas le Rappel@5).

Si le moteur d'embeddings est indisponible ou qu'aucun vecteur n'existe encore, la recherche se limite
aux mots-clés et `semanticAvailable = false`.

### 6.3 Implémentation (M5-05)

Paquet `fr.corsicajack.seekmind.engine.search.vector` :

- `VectorIndex.search(query, filters)` prend le vecteur de la requête (calculé par la recherche hybride,
  M5-06) et rend des `SemanticHit(captureId, embeddingId, similarity)` : meilleur fragment de chaque
  capture, trié par similarité puis par dernière vue. `refresh()` met l'index à jour sans chercher (à
  l'ouverture de l'écran), `release()` le libère, `isEmpty()` sert à `semanticAvailable`.
- `BinaryCodes` tient les tableaux du §6.1, qui grandissent par doublement ; les noms de paquets sont
  remplacés par un indice dans une table. Les filtres sont appliqués pendant le parcours : dates
  (`last_seen_at ≥ from` et `first_seen_at ≤ to`, comme la requête lexicale) et paquets (masque sur la
  table). Le tas borné des candidats est fait de deux `IntArray` alloués une fois : le parcours
  n'alloue rien. À distance égale, le vecteur le plus récent est gardé.
- Codes et rescore : `VectorCodec` de `engine:embedding`.

Limite connue : les dates de première et dernière vue sont celles du chargement. Une capture revue
(`touch`) n'a pas de nouveau vecteur et garde son ancienne dernière vue jusqu'au prochain rechargement
complet. Un filtre de dates peut donc l'exclure de la branche sémantique ; la branche lexicale, elle, la
trouve.

Mesures (JVM, 20 000 vecteurs normalisés, 100 requêtes, rappel des 10 plus proches voisins exacts parmi
les résultats, présélection puis rescore int8) :

| Jeu de vecteurs | Rappel@10, 400 candidats | 200 candidats |
|---|---|---|
| 2 000 groupes de 10 vecteurs, requêtes tirées près d'un centre | 0,997 | 0,995 |
| Mêmes groupes autour d'une direction commune (similarité moyenne 0,64, comme les vecteurs e5) | **0,924** | 0,878 |
| Vecteurs isotropes (cas le plus défavorable pour un code de signes) | 0,806 | 0,677 |

Le code de signes perd de l'information quand tous les vecteurs partagent une direction : avec 200
candidats, le cas proche des vecteurs e5 restait sous la cible de 0,90. **`binaryTopK` vaut donc 400**
(décision du porteur, 13 novembre 2026), au prix de 200 lectures int8 de plus par recherche. Le ticket
M5-07 le recalibre sur les vrais vecteurs.

Coût sur la base chiffrée (`VectorIndexDeviceTest`, 20 000 vecteurs, médiane de 15 recherches sans
filtre, émulateurs x86_64 de la CI, APK de test debuggable, exécution `device-tests` 43518022426) :

| | API 30 | API 34 | API 36 |
|---|---|---|---|
| Recherche, 400 candidats | 54 ms | 29 ms | 30 ms |
| Recherche, 200 candidats | 22 ms | 17 ms | 14 ms |
| Chargement de l'index (20 000 vecteurs) | 2,0 à 2,7 s | 1,2 à 1,4 s | 1,2 à 1,9 s |

Ces mesures portent sur la version qui lisait les int8 en base à chaque recherche : avec 400 candidats,
la branche sémantique atteignait déjà, à 20 000 vecteurs, le budget de 30 ms d'ENF-PERF-04 (recherche
hors embedding de la requête), le temps venant surtout de cette lecture. Les int8 sont depuis gardés en
mémoire (§6.1) ; les nouvelles mesures du même test sont consignées ci-dessous. **À suivre (M5-08)** : le
chargement reste loin de la cible de l'ADR-0005 (≤ 500 ms pour 100 000 vecteurs) ; la jointure avec
`capture` (dates, paquet) en est la piste principale. Mesures sur émulateur debuggable, à confirmer sur
le téléphone. Le parcours de 20 000 vecteurs n'alloue rien (240 octets en tout pour
10 parcours, mesurés par `ThreadMXBean`).

Mesures après le passage des int8 en mémoire (même test, même protocole, exécution `device-tests`
43527237491) :

| | API 30 | API 34 | API 36 |
|---|---|---|---|
| Recherche, 400 candidats | 2,7 ms | 7,4 ms | 4,1 ms |
| Recherche, 200 candidats | 2,1 ms | 4,5 ms | 2,8 ms |
| Chargement de l'index (20 000 vecteurs) | 1,4 s | 1,6 à 1,7 s | 2,0 s |

Le rescore en mémoire divise le temps de la branche sémantique par 4 à 20 : avec 400 candidats, elle
tient largement dans les 30 ms d'ENF-PERF-04. Le chargement, qui lit maintenant aussi les int8, reste le
point à traiter en M5-08.

## 7. Fusion

*Reciprocal Rank Fusion* sur les deux listes ordonnées :

```text
rrf(d) = w_lex / (k + rang_lex(d)) + w_sem / (k + rang_sem(d))        k = 60
score(d) = rrf(d) × (1 + 0,15 × exp(−âge_en_jours(d) / 30))
```

Un document absent d'une liste n'en reçoit aucun terme.

| Profil | `w_lex` | `w_sem` |
|---|---|---|
| `DEFAULT` | 1,0 | 1,0 |
| `CODE` | 2,0 | 0,5 |
| `NATURAL` | 0,8 | 1,2 |

Puis :

1. Tri par score décroissant ; à égalité, la capture la plus récente d'abord.
2. **Repli des quasi-doublons** : deux résultats du même paquet dont les SimHash diffèrent d'au plus
   6 bits sont fusionnés ; le plus récent est conservé, `similarCount` incrémenté.
3. Troncature à `limit`.
4. **Extrait** : celui de FTS5 si la capture a correspondu par mots-clés ; sinon les 200 premiers
   caractères du meilleur fragment sémantique.

## 8. Assouplissement

Si des filtres sont actifs et que la recherche hybride ne rend rien :

1. relancer sans le filtre de dates (filtre d'application conservé) → `relaxed = TIME` ;
2. si toujours rien, relancer sans aucun filtre → `relaxed = ALL`.

L'interface l'annonce (EF-SRCH-07). Une recherche assouplie qui trouve des résultats est facturée
normalement.

## 9. Déroulement et émissions

```text
parsed ← QueryParser.parse(...)
lancer en parallèle :
    A) lexical ← LexicalSearcher.search(parsed)        → émettre SearchResults(LEXICAL, …)
    B) sémantique ← VectorIndex.search(parsed)
fusion(A, B) → assouplissement éventuel               → émettre SearchResults(HYBRID, …)
```

- L'annulation du `Flow` (nouvelle requête, écran quitté) interrompt les deux branches.

**Implémentation (M5-06)**, paquet `fr.corsicajack.seekmind.engine.search.hybrid` : `HybridSearcher`
(implémente `SearchEngine`), `Fusion` (§7, pure), `SearchCache`, et `SemanticSource`, la branche
sémantique (`VectorSemanticSource` : moteur d'embeddings et `VectorIndex`) :

- Le chargement de l'index et le calcul du vecteur de la requête se font **dans** la branche
  sémantique, en parallèle de la recherche par mots-clés : la première émission n'attend jamais le
  chargement de l'index (ENF-PERF-03).
- Sans moteur d'embeddings, ou si la requête n'a plus de texte une fois les expressions retirées :
  **une seule émission**, `stage = LEXICAL`, `semanticAvailable = false` ; l'assouplissement s'y applique.
- Sans vecteur encore calculé, ou si la branche sémantique échoue — calcul du vecteur de la requête ou
  recherche dans l'index, assouplissement compris (l'erreur est journalisée sans contenu) : l'émission
  `HYBRID` reprend les résultats par mots-clés avec `semanticAvailable = false`, et n'est pas mise en cache.
- La première émission n'est jamais assouplie ; l'assouplissement réutilise le vecteur de la requête
  (calculé une fois). Si seuls des paquets sont filtrés, l'étape `TIME` est sautée.
- Une requête trouvée dans `SearchCache` donne directement son résultat `HYBRID`, en une émission.
  Seuls les résultats complets (`semanticAvailable = true`) sont mis en cache. Chaque entrée retient
  `meta.index_generation` : après une suppression (journal, « Tout effacer »), l'entrée est ignorée et la
  recherche refaite, si bien qu'une capture supprimée ne revient jamais du cache. Clé : texte normalisé
  (mots vides compris, que la branche sémantique lit), mots utiles, jetons de code, intervalle, paquets
  triés et `limit`.
- Repli des quasi-doublons : à rang égal de la paire, c'est la place la mieux classée qui est gardée,
  avec les données de la capture la plus récente ; `matchedBy` réunit les deux, et l'extrait surligné
  (mots-clés) est préféré à l'extrait sémantique.
- Extrait sémantique : le fragment du meilleur vecteur (`SearchStore.fragments`), 200 caractères.
- `SearchCache` conserve les 5 derniers résultats hybrides pendant 10 min, indexés par
  `queryKey` (texte normalisé + filtres). La même clé sert à la gratuité des requêtes répétées
  ([monetisation §2](monetisation.md)).

## 10. Configuration

```kotlin
data class SearchConfig(
    val lexicalLimit: Int = 100,
    val binaryTopK: Int = 400,
    val semanticTopK: Int = 100,
    val semanticFloor: Float = 0.78f,
    val rrfK: Int = 60,
    val recencyBoost: Double = 0.15,
    val recencyHalfLifeDays: Double = 30.0,
    val collapseMaxHamming: Int = 6,
    val indexPageSize: Int = 5_000,
    val cacheSize: Int = 5,
    val cacheTtlMs: Long = 600_000,
    // Poids de la fusion par profil (§7).
    val lexicalWeightDefault: Double = 1.0,
    val semanticWeightDefault: Double = 1.0,
    val lexicalWeightCode: Double = 2.0,
    val semanticWeightCode: Double = 0.5,
    val lexicalWeightNatural: Double = 0.8,
    val semanticWeightNatural: Double = 1.2,
)
```

## 11. Évaluation

`SearchEvalDeviceTest` (`engine:search`, test instrumenté du workflow `device-tests`,
[ADR-0011](../adr/0011-evaluation-de-la-recherche-sur-emulateur.md)) insère `testdata/eval/corpus.jsonl`
dans la vraie base chiffrée et le vectorise avec les vrais modèles. Il exécute ensuite
`testdata/eval/queries.jsonl` et calcule Rappel@1, Rappel@5 et MRR, globalement, par type de requête et
par langue, pour trois configurations : mots-clés seuls, sens seul, hybride.

L'évaluation est faite **pour chaque modèle du catalogue**. Le test échoue si le modèle `fr` est sous les
seuils d'EF-SRCH-01 à 03 sur les requêtes en français, ou si le modèle `multi` l'est sur l'ensemble des
requêtes. Il échoue aussi si l'hybride fait moins bien, en Rappel@5 global, que l'une des deux méthodes
seules. Les résultats du modèle `fr` sur les requêtes en anglais et bilingues sont publiés à titre
d'information (risque R-06).

Rapport : les lignes `m5_07_*` de logcat (`SeekMindBench`, dans l'artefact `device-tests`) et
`search-eval.json` dans la sortie additionnelle des tests. Un balayage de calibration (`semanticFloor`,
`binaryTopK`, poids des profils) y est joint. Tout changement de `SearchConfig`, du tokenizer ou du
modèle joint le rapport à la PR.

Le jeu est produit par `tools/eval/gen_corpus.py`, de façon déterministe :
- 298 captures, dont 58 ancres écrites à la main et 240 captures de remplissage ;
- 80 requêtes, qui désignent leurs captures attendues par une clé stable ;
- des dates relatives à un mardi soir fixe.

Le coût de la quantification seule est mesuré par `tools/model/eval_set.py`, appelé par `verify.py` :
Rappel@5 par le sens, modèle original contre modèle quantifié, sur le même jeu.

## 12. Tests attendus

- **JVM** : `QueryParser` (mots vides, codes, profils) ; expressions temporelles sur
  `temporal_cases.tsv` avec horloge et fuseau fixés, y compris changements d'heure et passage d'année ;
  filtre d'application ; constructeur d'expression FTS5 (échappement, préfixe, entrées hostiles) ;
  `VectorIndex` (exactitude de la présélection face à une recherche exhaustive sur vecteurs aléatoires,
  filtres, chargement incrémental, invalidation) ; fusion (tables de rangs, profils, repli, tri).
- **Instrumenté** : chaîne complète sur une petite base chiffrée avec `FakeEmbeddingEngine`.
- **Performance** : microbenchmark à 100 000 captures (ENF-PERF-03, 04).
- **Évaluation** : §11.
