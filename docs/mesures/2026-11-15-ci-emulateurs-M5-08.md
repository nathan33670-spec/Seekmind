# Mesures — recherche sur 100 000 captures, émulateurs (M5-08)

| | |
|---|---|
| **Date** | 15 novembre 2026 |
| **Ticket** | [M5-08](../plan/M5-recherche.md#m5-08--mesures-à-100-000-captures) |
| **Exécution** | GitHub Actions, `device-tests` n° 43697245834 (commit `a840ad9`) |
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

## 2. Constats

1. **La recherche sémantique tient largement ses cibles** : 3 à 11 ms au p95 sur 100 000 vecteurs.
2. **ENF-PERF-03 et 04 ne sont pas tenues, à cause de la recherche lexicale** : 2,2 à 4,1 s au p95, alors
   que le banc M1-07 mesurait 13 à 30 ms pour une requête de deux mots sur la même base
   ([mesures M1-07](2026-11-12-ci-emulateurs-M1-07.md)). Différence : M1-07 interrogeait deux termes en
   **ET** (`"a" "b"`) ; la recherche réelle les joint en **OU** et fait du dernier un **préfixe**
   (`"a" OR "b"*`, [recherche §5](../specs/recherche.md)). FTS5 doit alors calculer le BM25 de **toutes**
   les captures qui contiennent l'un des deux termes avant d'en garder 100 ; `snippet()` ne porte que
   sur ces 100.
3. **Le corpus synthétique aggrave le cas** : son vocabulaire de contenu compte 48 mots, et chaque capture
   en contient plusieurs. Un mot y figure dans une part importante des captures ; deux mots en OU, avec
   un préfixe, touchent une fraction notable des 100 000. Un vrai corpus a un vocabulaire bien plus
   large, mais les mots courants d'un utilisateur (« commande », « réunion », le nom d'un proche)
   peuvent eux aussi figurer dans des milliers de captures : le risque est réel, son ampleur reste à
   mesurer sur des données réelles.
4. **Dispersion** : les médianes varient d'un facteur 20 entre émulateurs et entre la mesure lexicale
   seule et la chaîne hybride (lancée juste après, base plus chaude), alors que le p95 reste au-dessus de
   2 s partout. Le coût dépend surtout du nombre de correspondances de la requête tirée.
5. **Ce qu'ajoute la fusion** (émission fusionnée moins première émission, en médiane) : 28 à 53 ms,
   avec une liste sémantique complète et la lecture des captures et des fragments. Avec le plancher
   sémantique réel (0,78), la liste est plus courte.
6. **Chargement de l'index** : 6 à 11 s pour 100 000 vecteurs (1,4 s pour 20 000 en M5-05). Il a lieu
   dans la branche sémantique, après l'affichage des mots-clés ([recherche §6.1](../specs/recherche.md)) :
   la première recherche d'une session n'a ses résultats par le sens qu'après ce délai.

## 3. Pistes (hors du périmètre de M5-08, à décider par le porteur)

- **Recherche lexicale en deux temps** : ET d'abord, OU seulement si le ET rend moins de N résultats.
  Le premier affichage reste alors au coût mesuré en M1-07 pour les requêtes à plusieurs mots.
- **Borner le classement** : limiter le BM25 aux captures les plus récentes (par exemple les 20 000
  dernières, par `rowid`), puis élargir si les résultats sont insuffisants.
- **Préfixe** : le réserver aux requêtes d'un seul mot, ou relever la longueur minimale à 4 ; ou
  l'index de préfixes FTS5 (`prefix='3'`), écarté aujourd'hui par [stockage §3.2](../specs/stockage.md).
- **Index vectoriel** : le charger à l'ouverture de l'écran de recherche plutôt qu'à la première
  requête ; ou le garder en mémoire plus longtemps.
- **Mesurer d'abord sur le téléphone** (validation manuelle de M5-08) et sur un corpus au vocabulaire
  réaliste : `SyntheticCorpus` pourrait tirer ses mots d'une distribution de Zipf sur un vocabulaire de
  plusieurs milliers de mots.
