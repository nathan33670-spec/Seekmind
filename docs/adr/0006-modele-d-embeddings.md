# ADR-0006 — Modèle `multilingual-e5-small` sur ONNX Runtime

| | |
|---|---|
| **Statut** | Accepté |
| **Date** | 2026-11-12 |

## Contexte

Le brief proposait un modèle de 25 à 40 Mo produisant des vecteurs de 384 dimensions, en citant
`all-MiniLM-L6-v2` et EmbeddingGemma, exécuté par LiteRT ou MediaPipe. Or :

- `all-MiniLM-L6-v2` est entraîné sur l'**anglais** ; les contenus des utilisateurs visés sont
  majoritairement en français ;
- EmbeddingGemma pèse environ 180 Mo une fois quantifié, coûte dix fois plus de calcul, et ses
  fichiers LiteRT ont une longueur de séquence fixe : une requête de cinq mots paie le prix d'un
  passage complet ; sa licence impose des conditions d'usage à répercuter.

Tailles vérifiées sur le dépôt des modèles le 12 novembre 2026.

## Décision

- Modèle **`intfloat/multilingual-e5-small`** : multilingue (une centaine de langues dont le français),
  384 dimensions, licence MIT, révision figée.
- **Quantification dynamique int8** produite par `tools/model` : environ 118 Mo.
- Exécution par **ONNX Runtime pour Android**, sur processeur, longueurs de séquence dynamiques.
- **Tokenizer en Kotlin pur** (algorithme Unigram), validé contre la référence sur 1 000 phrases.

## Conséquences

- Recherche sémantique de bonne qualité en français comme en anglais, y compris requête dans une langue
  et contenu dans l'autre.
- L'application pèse environ 150 Mo au téléchargement, au lieu des 50 à 60 Mo qu'aurait permis un modèle
  anglais (risque R-06). La cible est de rester sous 200 Mo.
- Le vocabulaire de 250 000 jetons représente l'essentiel du poids du modèle : c'est le levier
  d'allègement (ticket M10-07).
- Le projet s'écarte de LiteRT recommandé par le brief : le modèle retenu est distribué au format ONNX,
  et ONNX Runtime accepte des entrées de longueur variable.
- Deux préfixes obligatoires (`query: `, `passage: `), encapsulés dans le moteur.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| `all-MiniLM-L6-v2` (23 Mo) | Anglais seulement. |
| EmbeddingGemma 300M sur LiteRT (~180 Mo) | Poids, coût de calcul, séquence fixe, licence. À réévaluer si les appareils visés évoluent. |
| `paraphrase-multilingual-MiniLM-L12-v2` (~118 Mo) | Même poids, conçu pour la similarité de phrases plutôt que pour la recherche requête → passage. |
| `french-me5-small` (vocabulaire réduit au français, ~36 Mo une fois quantifié) | Très prometteur pour la taille ; couverture de l'anglais et diffusion à vérifier. **Candidat du ticket M10-07.** |
| Pas d'embeddings (mots-clés seuls) | Ne répond pas à la promesse de recherche floue. |
