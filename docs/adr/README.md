# Décisions d'architecture (ADR)

Chaque décision structurante est consignée ici : contexte, décision, conséquences, alternatives écartées.
Un ADR accepté n'est pas réécrit : on le remplace par un nouvel ADR qui le référence.

| N° | Décision | Statut |
|---|---|---|
| [0001](0001-zero-permission-internet.md) | L'application n'a aucune permission réseau | Accepté — amendé par 0009 (vaut pour la variante `offline`) |
| [0002](0002-publicite-recompensee-hors-application.md) | La publicité récompensée s'affiche dans le navigateur | Écarté pour la v1 — solution de repli |
| [0003](0003-min-sdk-30.md) | Android 11 minimum | Accepté |
| [0004](0004-stockage-room-sqlcipher-fts5.md) | Room + SQLCipher + FTS5 | Accepté |
| [0005](0005-recherche-vectorielle-en-memoire.md) | Recherche vectorielle en mémoire, sans `sqlite-vec` | Accepté |
| [0006](0006-modele-d-embeddings.md) | Modèle `multilingual-e5-small` sur ONNX Runtime | Remplacé par 0010 pour le modèle embarqué |
| [0007](0007-codes-d-activation-signes.md) | Codes d'activation = jetons signés vérifiés hors ligne | Accepté |
| [0008](0008-detection-des-fenetres-securisees.md) | Détection des fenêtres sécurisées par sonde | Accepté |
| [0009](0009-publicite-admob-et-variante-sans-reseau.md) | Publicité par le SDK AdMob, variante sans réseau maintenue en continu | Accepté |
| [0010](0010-modele-francais-et-packs-de-langue.md) | Modèle français embarqué, packs de langue téléchargeables | Accepté |
| [0011](0011-evaluation-de-la-recherche-sur-emulateur.md) | Évaluation de la recherche sur émulateur, avec la vraie base et les vrais modèles | Accepté |

## Modèle

```markdown
# ADR-NNNN — Titre

| | |
|---|---|
| **Statut** | Proposé / Accepté / Remplacé par ADR-XXXX |
| **Date** | AAAA-MM-JJ |

## Contexte
## Décision
## Conséquences
## Alternatives écartées
```
