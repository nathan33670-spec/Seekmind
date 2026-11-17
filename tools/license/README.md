# Codes d'activation de SeekMind

Outils de l'éditeur pour les codes d'activation ([monetisation §5](../../docs/specs/monetisation.md),
[ADR-0007](../../docs/adr/0007-codes-d-activation-signes.md)). Ils s'exécutent sur une machine de
confiance, jamais en CI.

```bash
python -m pip install -r tools/license/requirements.txt
```

## 1. Clé de production (une seule fois)

```bash
python tools/license/seekmind_keys.py keygen --purpose license --kid l1 --out ~/seekmind-keys
```

- `~/seekmind-keys/l1.pem` est la **clé privée** (droits 0600). L'outil refuse un répertoire du dépôt et
  n'écrase jamais une clé existante.
- `~/seekmind-keys/l1.spki` est la clé publique : la coller dans le champ `spki` de `l1` dans
  `engine/monetization/src/main/resources/license/keys-release.json`, par une PR.

**Conservation de la clé privée**
- Elle ne quitte pas la machine de confiance et n'entre jamais dans le dépôt, un ticket, un message
  ni un service en ligne (INV-10).
- Il en faut deux copies hors ligne, chiffrées, par exemple deux clés USB chiffrées rangées en des
  lieux distincts.
- La perdre empêche d'émettre de nouveaux codes ; les codes déjà émis restent valides.
- Si elle est divulguée : générer une clé `l2`, retirer `l1` de `keys-release.json`, publier une mise à
  jour. Les codes signés par `l1` cessent alors de fonctionner : il faut en réémettre.

## 2. Émettre des codes

```bash
python tools/license/seekmind_license.py issue --key ~/seekmind-keys/l1.pem --kid l1 \
    --plan lifetime --count 50 --site <hôte du site> --out ~/seekmind-codes/2026-11.csv
```

- Le CSV contient l'identifiant de licence, le code et le lien `https://<site>/a#<code>`. C'est ce lien
  qu'on remet à l'utilisateur.
- Le fichier est créé en 0600, jamais écrasé, et refusé dans le dépôt : chaque code active SeekMind.
- `--valid-days N` ajoute une date d'expiration. Par défaut, il n'y en a pas.
- `--prefix` change le préfixe des identifiants (`SM` par défaut, par exemple `SM-3F9A0C12D4`).
- Les identifiants sont aléatoires et ne portent aucune donnée de l'utilisateur.
- Tenir un registre « identifiant → destinataire » hors du dépôt : c'est ce registre qui permet de
  révoquer un code divulgué.

## 3. Vérifier un code

```bash
python tools/license/seekmind_license.py verify --pub ~/seekmind-keys/l1.spki '<code>'
```

L'outil affiche les données du code, ou `refused: …` avec le code de sortie 1.

## 4. Révoquer un code

Ajouter son identifiant à `engine/monetization/src/main/resources/license/revoked_licenses.json`
(`{"revoked": ["SM-…"]}`), puis publier une mise à jour. Un code révoqué est refusé à l'activation.
Une activation déjà faite reste en place, puisque l'application ne contacte aucun serveur.

## 5. Clé de test et vecteurs

La clé `l-test` ne protège rien : sa partie privée est committée (`test_keys/`), et seul le build
`debug` lui fait confiance. `gen_test_vectors.py` régénère `testdata/tokens/`, qu'on ne modifie
jamais à la main.

```bash
python tools/license/gen_test_vectors.py
python -m pytest tools/license
```
