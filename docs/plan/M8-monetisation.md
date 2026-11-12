# M8 — Monétisation

**Objectif.** Formule gratuite à crédits (1 publicité = 5 recherches) et formule activée par code, sans
qu'aucun accès réseau n'entre dans l'application.

**Avant de commencer.** Décisions D-01 et D-02 confirmées ; liste « Prêt pour M8 » des
[prérequis §8](../04-prerequis.md#8-listes-de-contrôle). Les tickets M8-01, M8-02, M8-05 et M8-06 peuvent
démarrer avec les clés de test, sans attendre le domaine ni la régie.

**Sortie du jalon.** Scénario E2E-15 vert ; parcours P3 et P4 du cahier des charges réalisables.

---

### M8-01 — Droits et registre de crédits

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-05 | `engine:monetization` | [monetisation §2](../specs/monetisation.md) · EF-MON-01, 04, 07, 08 |

**À faire**

1. `CreditLedger` : crédit de bienvenue à la création de la base, mouvements, solde, plafonds.
2. `EntitlementGate` : `state`, `authorize`, `commit`, selon le tableau de la spec.
3. Remplacer l'implémentation « tout autorisé » posée par M6-03.

**Critères d'acceptation**

- [ ] Un test par ligne du tableau « Règles » : activé, requête répétée sous 10 minutes, solde suffisant, solde nul.
- [ ] `commit` avec zéro résultat ne débite pas.
- [ ] Plafond de solde et plafond quotidien de récompenses respectés (horloge simulée, changement de jour civil).
- [ ] Le solde survit au redémarrage du processus.

---

### M8-02 — Vérification des jetons

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-01 | `engine:monetization`, `tools/license`, `testdata/tokens` | [monetisation §3](../specs/monetisation.md) · [prérequis §3](../04-prerequis.md#3-clés-et-secrets) |

**À faire**

1. `tools/license/seekmind_keys.py` (génération de paires ES256) et `gen_test_vectors.py` ; clés de test
   committées dans les sources `debug` et `test` ; `testdata/tokens/` (valides et invalides).
2. `TokenVerifier` : contrôles de la spec §3.2, conversion de signature, trousseau par usage.
3. `keys-release.json` avec des emplacements pour les clés de production ; `keys-debug.json` réservé au build `debug`.

**Critères d'acceptation**

- [ ] Chaque vecteur invalide est refusé pour la bonne raison : signature altérée, charge altérée, `alg`
      autre qu'ES256 (dont `none`), `kid` inconnu, usage croisé, longueur excessive, base64 invalide.
- [ ] Le build `release` refuse un jeton signé par une clé de test (test dédié).
- [ ] Aucune clé privée de production dans le dépôt ; les clés de test sont nommées comme telles.

---

### M8-03 — Parcours de récompense dans l'application

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-01, M8-02 | `engine:monetization`, `app` | [monetisation §4.1, §4.2](../specs/monetisation.md) · [sécurité §6](../07-securite-vie-privee.md#6-surface-exposée) · EF-MON-02 |

**À faire**

1. `RewardFlow.start` : plafond quotidien, nonce, demande en attente, construction de l'URL, onglet personnalisé.
2. `LinkActivity` : réception de `seekmind://reward`, validation des entrées, respect du verrouillage ; déclaration dans le manifeste.
3. `RewardFlow.redeem` : vérification, rattachement au nonce, transaction, message de résultat.

**Critères d'acceptation**

- [ ] L'URL ouverte ne contient que `n`, `v` et `l` (test sur la fonction de construction).
- [ ] Refus : nonce inconnu, déjà utilisé, trop ancien, montant hors bornes, jeton d'usage « licence ».
- [ ] Un jeton valide crédite une seule fois ; le rejouer ne change rien.
- [ ] `LinkActivity` : entrées malformées ou surdimensionnées → message neutre, aucun effet, aucun plantage.
- [ ] `:app:verifyReleaseManifest` vert avec le nouveau composant exporté.

---

### M8-04 — Feuille de crédits et indicateur

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-01, M6-03 | `feature:paywall`, `feature:search`, `feature:settings` | [monetisation §6](../specs/monetisation.md) · [ui-ux §3.2, §3.5](../specs/ui-ux.md) · EF-MON-03 |

**À faire**

1. Indicateur de solde ou badge « Activé » sur l'écran de recherche.
2. Feuille « Continuer à chercher » : publicité, code, rappel de confidentialité, état « plafond atteint ».
3. Recherche au fil de la frappe en formule activée ; validation explicite en formule gratuite.
4. Rubrique Activation des réglages (solde, ou licence).

**Critères d'acceptation**

- [ ] Solde nul → la feuille s'ouvre à la validation d'une recherche ; la recherche part après une récompense.
- [ ] Mention « Cette recherche ne vous a pas été décomptée » quand il n'y a aucun résultat.
- [ ] Formule activée : aucun indicateur de crédits, aucune feuille.

---

### M8-05 — Outil de licences et activation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-02, M8-01 | `tools/license`, `engine:monetization`, `feature:settings`, `app` | [monetisation §5](../specs/monetisation.md) · [ADR-0007](../adr/0007-codes-d-activation-signes.md) · EF-MON-05, EF-MON-06 |

**À faire**

1. `tools/license/seekmind_license.py` : `issue`, `verify` ; sortie CSV (identifiant, code, lien) ; tests `pytest`.
2. `LicenseActivator` : vérification, expiration, liste de révocation embarquée, enregistrement.
3. Entrées : champ « Coller » dans les réglages ; lien `seekmind://activate` avec confirmation.
4. « Retirer l'activation ».
5. `tools/license/README.md` : procédure d'émission et de conservation de la clé privée.

**Critères d'acceptation**

- [ ] Un code émis par l'outil avec la clé de test est accepté par l'application `debug`.
- [ ] Messages distincts : illisible, signature invalide, expiré, révoqué ; aucun changement d'état en cas d'échec.
- [ ] L'activation survit au redémarrage ; « Retirer » ramène à la formule gratuite avec le solde antérieur.
- [ ] Le lien d'activation ne fait rien sans confirmation de l'utilisateur.

---

### M8-06 — Page de récompense et signature

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-02 | `reward-web` | [monetisation §4.3 à §4.5](../specs/monetisation.md) |

**À faire**

1. Projet Cloudflare Workers : arborescence de la spec, `wrangler.toml` avec environnements de recette et de production.
2. `POST /api/reward/grant` : contrôles d'entrée, anti-robot, limitation de débit, signature ES256.
3. Page `/reward` en français et en anglais : explication, consentement, adaptateur, bouton de retour avec repli.
4. Adaptateurs `mock` (recette) et `gam` (référence) ; page de relais `a.html`.
5. Tests Vitest ; signature vérifiée avec les vecteurs communs de `testdata/tokens/`.
6. `reward-web/README.md` : déploiement, secrets, configuration de la régie, bascule de fournisseur.

**Critères d'acceptation**

- [ ] Un jeton produit par le Worker de recette est accepté par `TokenVerifier` (test croisé avec la clé de test).
- [ ] Nonce mal formé, origine étrangère, débit dépassé → refus avec le bon code HTTP.
- [ ] Sans publicité disponible → crédit de courtoisie d'une recherche, limité à trois par jour et par adresse.
- [ ] La page ne charge aucune ressource avant le consentement, hormis ses propres fichiers.
- [ ] Aucune donnée conservée hors compteurs de débit à expiration automatique.

**Validation manuelle** — déploiement en recette par le porteur du projet ; parcours P3 complet sur un
vrai téléphone avec l'adaptateur `mock`, puis avec la régie réelle dès son approbation.

---

### M8-07 — Codes courts activés par le web (optionnel)

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-06 | `reward-web`, `engine:monetization` | [ADR-0007](../adr/0007-codes-d-activation-signes.md) |

À n'entreprendre que sur décision du porteur du projet. Rédiger d'abord un ADR : format du code court,
stockage côté Worker, limite d'activations par code, jeton de licence lié à un nonce de l'application.

**Critères d'acceptation** — définis par l'ADR.

---

### M8-08 — Tests de bout en bout de la monétisation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-03, M8-04, M8-05 | `app`, `fixtureapp` | [tests §3, §4](../06-qualite-tests-ci.md) · [monetisation §4.4](../specs/monetisation.md) |

**À faire**

1. `FakeBrowserActivity` dans l'application de test : répond aux URL de récompense, renvoie un jeton de test.
2. Scénario E2E-15 ; scénario d'activation par lien ; scénario « solde nul → feuille → récompense → recherche ».

**Critères d'acceptation**

- [ ] E2E-15 vert sur les trois niveaux d'API : +5, puis refus du même jeton.
- [ ] Capture, journal, réglages et suppression restent accessibles avec un solde nul (EF-MON-07).
