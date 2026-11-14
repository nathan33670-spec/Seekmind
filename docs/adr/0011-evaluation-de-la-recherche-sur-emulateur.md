# ADR-0011 — Évaluation de la recherche sur émulateur

| | |
|---|---|
| **Statut** | Accepté — décision du porteur du projet |
| **Date** | 2026-11-14 |
| **Concerne** | [recherche §11](../specs/recherche.md), [tests §5](../06-qualite-tests-ci.md#5-jeux-de-référence), ticket M5-07 |

## Contexte

La spec prévoyait d'évaluer la recherche sur la JVM : `:engine:search:evalSearch`, une base en mémoire et
ONNX Runtime pour ordinateur. Or la recherche par mots-clés passe par `core:database`, c'est-à-dire Room,
SQLCipher et FTS5, qui ne tournent que sous Android. Le moteur d'embeddings utilise de son côté ONNX
Runtime pour Android.

Évaluer sur la JVM obligerait à ajouter deux bibliothèques de test (ONNX Runtime pour ordinateur, un
pilote SQLite avec FTS5) et à reproduire hors d'Android le schéma FTS, ses requêtes et son tokenizer.
L'évaluation mesurerait alors une copie de la recherche, dont le comportement pourrait s'écarter du vrai.

## Décision

L'évaluation est un test instrumenté, `SearchEvalDeviceTest` (`engine:search`), exécuté par le workflow
`device-tests` sur les émulateurs de la CI :

- le corpus de `testdata/eval/corpus.jsonl` est inséré dans la vraie base chiffrée (`TemporaryDatabase`)
  et vectorisé par le vrai moteur, avec le modèle `fr` des assets et le modèle `multi` poussé par le workflow ;
- chaque requête de `testdata/eval/queries.jsonl` passe par le vrai `HybridSearcher`, en trois
  configurations : mots-clés seuls (source sémantique absente), sens seul (poids lexicaux nuls, seuls
  les résultats trouvés par le sens comptent), hybride ;
- les mesures (Rappel@1, Rappel@5, MRR, par type et par langue, pour chaque modèle) et le balayage de
  calibration vont dans logcat (`SeekMindBench`) et dans `search-eval.json`, dans la sortie
  additionnelle des tests ;
- le test échoue sous les seuils d'EF-SRCH-01 à 03 : sur les requêtes en français pour `fr`, sur toutes
  les requêtes pour `multi`.

Le coût de la quantification seule est mesuré à part, sur la JVM, par `tools/model/eval_set.py` : il
compare le modèle original et le modèle quantifié, en classant le corpus par le sens, sans la base.

## Conséquences

- Aucune nouvelle dépendance. L'évaluation mesure exactement ce que l'utilisateur obtiendra : la même
  base, le même moteur, la même fusion.
- L'évaluation n'existe qu'en CI (les environnements des agents n'ont pas d'émulateur) et ajoute
  quelques minutes à chaque exécution du workflow `device-tests`.
- Le découpage en fragments est simplifié : chaque capture du corpus (212 caractères au plus) tient en
  un fragment, ce que ferait aussi le découpeur réel (`chunkMaxChars` = 800).
- Tout changement de `SearchConfig`, du tokenizer ou du modèle joint à sa PR les lignes `m5_07_*` du
  rapport.
