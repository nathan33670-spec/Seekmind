# Mesures — recherche sur 100 000 captures, émulateurs (M5-08)

| | |
|---|---|
| **Date** | 15 novembre 2026 |
| **Ticket** | [M5-08](../plan/M5-recherche.md#m5-08--mesures-à-100-000-captures) |
| **Exécutions** | GitHub Actions, `device-tests` n° 43697245834 (commit `a840ad9`, §1) et n° 43700326553 (commit `a3b1fc8`, diagnostic du §2) |
| **Appareils** | Émulateurs `google_apis` x86_64, 2 cœurs, API 30, 34 et 36, rendu logiciel |
| **Conditions** | APK de test **debuggable**, non compilé à l'avance ; émulateurs partagés de la CI. Chiffres **indicatifs** : un téléphone en `release` est plus rapide, mais l'ordre de grandeur des écarts ci-dessous ne s'explique pas par là. |
| **Banc** | `HundredThousandSearchDeviceTest` (`engine:search`) : base chiffrée de 100 000 captures et 100 000 vecteurs (`SyntheticDatabase`, `core:testing`), 50 requêtes de deux mots tirés du vocabulaire du corpus, après 10 requêtes de préchauffage ; cache des résultats désactivé ; plancher sémantique désactivé (la branche sémantique rend sa liste entière : cas le plus coûteux pour la fusion) ; `FakeEmbeddingEngine` (embedding de la requête gratuit). |
| **Sources** | Artefacts `device-tests-api{30,34,36}`, étiquette `SeekMindBench`, lignes `m5_08_*` |

## 1. Résultats

| Mesure | API 30 (p50 / p95) | API 34 | API 36 | Cible |
|---|---|---|---|---|
| Génération de la base (100 000 captures, fragments et vecteurs) | 25,5 s | 48,1 s | 53,6 s | |
| Chargement de l'index vectoriel (100 000 codes binaires) | 6,3 s | 8,9 s | 10,9 s | |
| Recherche lexicale seule (`LexicalSearcher`, 100 résultats) | 896 / **2 454 ms** | 1 221 / **3 329 ms** | 1 496 / **4 060 ms** | |
| Recherche sémantique seule (présélection et rescore, 100 000 vecteurs) | 3,1 / 3,4 ms | 9,1 / 11,0 ms | 6,5 / 6,9 ms | |
| Chaîne hybride, première émission (mots-clés) — ENF-PERF-03 | 62 / **2 448 ms** | 1 221 / **3 308 ms** | 53 / **2 218 ms** | ≤ 100 ms (p95) |
| Chaîne hybride, émission fusionnée, hors embedding de la requête — ENF-PERF-04 | 90 / **2 476 ms** | 1 268 / **3 355 ms** | 106 / **2 272 ms** | ≤ 30 ms (p95) |

L'embedding de la requête, mesuré à part par les bancs du moteur ([embeddings §8](../specs/embeddings.md)),
coûte 9 à 18 ms en médiane sur ces émulateurs ; il ne change rien aux conclusions.

## 2. Diagnostic de la recherche lexicale (exécution 43700326553)

Mêmes 30 paires de mots, interrogées par `SearchStore.lexical` sous les quatre formes de l'expression
FTS5 ; nombre de captures touchées compté à part, sans chronométrage, sur 5 paires.

| Forme | Captures touchées (médiane / max) | API 30 (p50 / p95) | API 34 | API 36 |
|---|---|---|---|---|
| `a b` (ET) | 571 / 7 714 | 109 / 465 ms | 88 / 366 ms | 101 / 422 ms |
| `a b*` (ET, préfixe) | 571 / 7 714 | 109 / 458 ms | 90 / 370 ms | 100 / 421 ms |
| `a OR b` | 14 759 / 22 235 | 1 637 / 4 449 ms | 1 308 / 3 528 ms | 1 496 / 4 091 ms |
| `a OR b*` (forme actuelle) | 14 759 / 22 235 | 1 640 / 4 435 ms | 1 304 / 3 506 ms | 1 490 / 4 102 ms |

Cas typique, un mot fréquent et un nombre rare (chaîne hybride, première émission) : 877 / 1 718 ms,
714 / 4 312 ms et 33 / 821 ms au p50 / p95. Le mot fréquent, en OU, suffit à rendre la requête lente.

Durées requête par requête, ordre alterné :
- **Bimodalité** : une même requête prend soit environ 45 ms, soit 1,5 à 2,5 s.
- **Pas d'effet d'ordre** : les requêtes rapides ne sont pas les mêmes d'un émulateur à l'autre, et
  l'ordre (lexicale avant ou après l'hybride) ne les prédit pas.
- **Ce n'est pas la chaîne hybride qui serait plus rapide** : les médianes de la première série
  (62 ms en hybride contre 896 ms en lexicale seule, API 30) reflétaient seulement la proportion de
  requêtes rapides dans chaque série.

## 3. Constats

1. **La recherche sémantique tient largement ses cibles** : 3 à 12 ms au p95 sur 100 000 vecteurs.
2. **Le OU est la cause, le préfixe ne coûte rien** :
   - le coût suit le nombre de captures touchées : 571 en médiane en ET (90 à 110 ms), 14 759 en OU
     (1,3 à 1,6 s) ;
   - le préfixe sur le dernier mot ne change ni ce nombre ni la durée.
3. **Explication probable de la bimodalité (hypothèse, non mesurée directement)** :
   - le classement BM25 lit, pour chaque capture touchée, la taille de ses colonnes dans la table
     `docsize` de FTS5 : environ 15 000 lectures aléatoires pour un OU ;
   - avec SQLCipher, chaque page lue est déchiffrée, et chaque connexion de lecture de Room a son
     propre cache de pages ;
   - une requête servie par une connexion dont le cache contient déjà ces pages prend de l'ordre de
     45 ms ; sinon, 1,5 s.
4. **Même en ET, le pire cas dépasse la cible** : 366 à 465 ms au p95 pour 7 714 captures touchées
   (deux mots qui figurent ensemble dans un gabarit du corpus). Sur des données réelles, deux mots
   courants pris ensemble touchent bien moins de captures qu'un seul.
5. **Le corpus synthétique aggrave le cas** : son vocabulaire de contenu compte 48 mots, et chaque
   capture en contient plusieurs. Le cas typique (un mot fréquent et un mot rare) montre que le
   problème ne tient pas qu'à ce vocabulaire : en OU, un seul mot courant suffit.
6. **Ce qu'ajoute la fusion** (émission fusionnée moins première émission, en médiane) : 28 à 54 ms,
   avec une liste sémantique complète et la lecture des captures et des fragments.
7. **Chargement de l'index** : 6 à 12 s pour 100 000 vecteurs (1,4 s pour 20 000 en M5-05). Il a lieu
   dans la branche sémantique, après l'affichage des mots-clés ([recherche §6.1](../specs/recherche.md)) :
   la première recherche d'une session n'a ses résultats par le sens qu'après ce délai.

## 4. Suites retenues

Décision du porteur du 15 novembre 2026 (« Suis tes recommandations ») :

- **Recherche lexicale en deux temps** (correctif séparé de M5-08) : ET d'abord ; OU seulement si le ET
  rend trop peu de résultats. D'après le §2, le premier affichage passe de 1,3 à 1,6 s à environ 100 ms
  en médiane pour les requêtes de plusieurs mots.
- **Mesures sur le téléphone de test** : reportées à M10-01, avec les autres vérifications sur téléphone.

Pistes restantes, non décidées : agrandir le cache de pages des connexions (`PRAGMA cache_size`) et
mesurer l'effet sur la bimodalité ; borner le classement aux captures récentes ; charger l'index
vectoriel à l'ouverture de l'écran de recherche ; vocabulaire de Zipf pour `SyntheticCorpus`.
