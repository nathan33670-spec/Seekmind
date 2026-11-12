# Outillage des modèles d'embeddings

Fabrique, vérifie et emballe les deux modèles de SeekMind (`fr` et `multi`), et produit leurs jeux de
référence. Spécification : [`docs/specs/embeddings.md`](../../docs/specs/embeddings.md) §2, §3.2, §5.2.

## Installation

Python 3.13 ; versions figées, `torch` en version processeur seul :

```bash
python -m venv .venv && . .venv/bin/activate
python -m pip install -r tools/model/requirements.txt
```

Réseau : `huggingface.co` et son CDN (téléchargement des sources aux révisions figées), PyPI et
`download.pytorch.org` (installation).

## Commandes

| Commande | Effet |
|---|---|
| `python tools/model/build_model.py --model fr --install` | Télécharge, exporte en ONNX, quantifie, écrit `vocab.bin` et `MODEL_MANIFEST.json`, contrôle la qualité, compare à `model.lock.json`, puis copie le jeu dans les assets de `:engine:embedding` |
| `python tools/model/build_model.py --model multi` | Même chose pour le pack multilingue (sans installation : il est livré par un pack d'assets) |
| `python tools/model/verify.py --model <id> --lock` | Contrôles de qualité d'un jeu déjà construit |
| `python tools/model/pack_model.py --model <id>` | Archive `.smpack` pour `FileModelPackInstaller` |
| `python tools/model/gen_sentences.py` | Régénère les phrases sources de `data/` |
| `python tools/model/gen_tokenizer_golden.py --model <id>` | Régénère `testdata/tokenizer_golden_<id>.jsonl` |
| `python tools/model/gen_embedding_golden.py --model <id>` | Régénère `testdata/embedding_golden_<id>.json` |
| `python -m pytest tools/model` | Tests de l'outillage (sans réseau ni modèle) |

Les sorties vont dans `tools/model/out/<id>/`, ignoré par git, comme les assets installés et les archives.

## Fichier de verrouillage

`engine/embedding/model.lock.json` enregistre la taille et le SHA-256 de `model.onnx` et `vocab.bin` pour
chaque modèle, et ceux des jeux de référence de `testdata/`. Une construction qui n'y correspond pas
échoue, et `:engine:embedding:verifyModel` refuse un jeu installé non conforme.

`--update-lock` ne s'emploie que lorsqu'une source figée ou une version d'outil change, dans une PR
dédiée qui régénère aussi les jeux de référence. Les générateurs de `testdata/` mettent à jour leurs
empreintes d'eux-mêmes : ils sont les seuls à écrire ces fichiers.

## Workflow `model`

Manuel, hebdomadaire, et sur toute PR qui touche cet outillage : reconstruit les deux modèles sans
cache, vérifie qu'ils correspondent au fichier de verrouillage, régénère les jeux de référence (les
identifiants de jetons doivent être identiques ; les vecteurs, à un cosinus ≥ 0,9999 près, car les
noyaux int8 varient d'un processeur à l'autre) et alimente le cache que `ci` et `device-tests` restaurent.
