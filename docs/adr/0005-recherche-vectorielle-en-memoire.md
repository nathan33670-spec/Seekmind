# ADR-0005 — Recherche vectorielle en mémoire, sans `sqlite-vec`

| | |
|---|---|
| **Statut** | Accepté |
| **Date** | 2026-11-12 |

## Contexte

Le brief recommandait `sqlite-vec` avec un index HNSW, pour une latence inférieure à 15 ms sur 100 000
écrans. Vérification faite :

- `sqlite-vec` effectue une recherche **exhaustive** ; il ne fournit pas d'index HNSW ;
- c'est une extension native qui n'est pas livrée avec SQLCipher : la combiner au chiffrement exige une
  compilation native sur mesure, à maintenir pour chaque ABI et chaque version ;
- il ne stocke pas de vecteurs en demi-précision (`float16`).

Par ailleurs le volume réel est modeste : de l'ordre de 100 000 à 300 000 vecteurs de dimension 384.

## Décision

Les vecteurs sont de simples colonnes BLOB dans la base chiffrée ; la recherche des plus proches voisins
est faite **par l'application**, en deux étapes :

1. **Présélection binaire en mémoire** : chaque vecteur a un code de 384 bits (signe de chaque
   composante). Un parcours linéaire par distance de Hamming retient 200 candidats.
2. **Rescore** : les vecteurs int8 des candidats sont lus en base et comparés au vecteur de la requête.

Tout est en Kotlin, derrière l'interface `VectorIndex`. Détail : [specs/recherche §6](../specs/recherche.md).

## Conséquences

- Aucune compilation native supplémentaire ; logique entièrement testable sur JVM.
- Coût à 100 000 vecteurs : environ 8 Mo de mémoire et quelques millisecondes de parcours ; le rescore
  lit 200 lignes. La cible « hors embedding de la requête ≤ 30 ms » est réaliste ; le temps total est
  dominé par l'embedding de la requête, d'où l'affichage immédiat des résultats par mots-clés.
- Le rappel de la présélection binaire à 384 dimensions est inférieur à celui d'une recherche exacte :
  il est mesuré par les tests (comparaison à la recherche exhaustive) et par l'évaluation de la
  recherche ; `binaryTopK` est le réglage.
- L'index est rechargé depuis la base à l'ouverture de la recherche (cible ≤ 500 ms pour 100 000).
- Au-delà de quelques centaines de milliers de vecteurs, il faudra un index approché : l'interface
  `VectorIndex` permet de le substituer sans toucher au reste.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| `sqlite-vec` | Voir le contexte. |
| Extension `vec1` de SQLite (index approché IVF + quantification de produit) | Récente (version 0.8), même difficulté d'intégration à SQLCipher. Candidate pour une version ultérieure. |
| Bibliothèque HNSW native (hnswlib, usearch) par JNI | Index hors de la base chiffrée, persistance et suppression à gérer, code natif à maintenir ; inutile à ce volume. |
| Parcours exhaustif en flottants | 150 Mo à parcourir pour 100 000 vecteurs : trop lent et trop gourmand en mémoire. |
