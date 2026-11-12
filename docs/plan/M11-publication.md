# M11 — Publication

**Objectif.** Un dossier Google Play complet et conforme, une version en test fermé, puis en production.

**Avant de commencer.** Liste « Prêt pour M11 » des [prérequis §8](../04-prerequis.md#8-listes-de-contrôle).
Plusieurs étapes de ce jalon sont réalisées par le porteur du projet dans la Play Console ; l'agent
prépare les contenus et les artefacts.

---

### M11-01 — Fiche de l'application

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-08 | `docs/store` | [conformité §7](../05-conformite-play-store.md#7-fiche-de-lapplication) · [vision](../01-vision-produit.md) |

**À faire**

1. `docs/store/` : titre, description courte, description longue en français et en anglais ; l'usage de
   l'accessibilité est expliqué dès les premières lignes.
2. Plan des captures d'écran (huit vues, données fictives) et de l'image de présentation.
3. Texte des notes de version.

**Critères d'acceptation**

- [ ] Aucun terme proscrit par la conformité §7 ; aucune promesse que le produit ne tient pas (retour à la source).
- [ ] Longueurs conformes aux limites de la Play Console.

---

### M11-02 — Site et politique de confidentialité

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | — | `reward-web` ou `site/` | [sécurité §7](../07-securite-vie-privee.md#7-politique-de-confidentialité) · [prérequis §2](../04-prerequis.md#2-comptes-et-services) |

**À faire**

1. Pages statiques : accueil, FAQ, assistance, politique de confidentialité (FR et EN), selon le plan de la sécurité §7.
2. FAQ : ce qui est lu et ce qui ne l'est pas ; limite de la détection des fenêtres sécurisées sur
   Android 11 à 13 ; réglage des navigateurs autorisant les captures en navigation privée ; publicité
   dans le navigateur ; suppression des données.

**Critères d'acceptation**

- [ ] La politique décrit exactement le comportement de l'application (relecture croisée avec la divulgation).
- [ ] La page de récompense y est présentée comme un site distinct, avec ses propres traitements.

**Validation manuelle** — relecture juridique ; mise en ligne par le porteur du projet.

---

### M11-03 — Déclarations Play Console

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M11-02 | `docs/store` | [conformité §5](../05-conformite-play-store.md#5-déclarations-dans-la-play-console) |

**À faire**

1. `docs/store/declarations.md` : réponses rédigées, question par question, pour la déclaration
   d'accessibilité, la sécurité des données, la publicité, le public cible et la classification.
2. Relire la page de politique de Google le jour de la rédaction et signaler tout écart avec le document de conformité.

**Critères d'acceptation**

- [ ] Chaque réponse est exacte au regard du code (permissions du manifeste, absence de transmission).
- [ ] Les écarts éventuels entre la politique en vigueur et `docs/05` sont corrigés dans `docs/05`.

**Validation manuelle** — saisie dans la Play Console par le porteur du projet.

---

### M11-04 — Vidéo de divulgation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-08 | `docs/store` | [conformité §6](../05-conformite-play-store.md#6-vidéo-de-démonstration) |

**À faire**

1. `docs/store/video.md` : scénario plan par plan, texte des sous-titres, liste de contrôle de tournage.
2. Préparer un état de l'application propice au tournage (données fictives, application tierce neutre).

**Critères d'acceptation**

- [ ] Le scénario couvre les cinq éléments exigés : ouverture, divulgation lisible en entier,
      consentement et activation, refus et réouverture, fonctionnalité centrale.

**Validation manuelle** — tournage et mise en ligne par le porteur du projet.

---

### M11-05 — Chaîne de publication

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10-04 | `.github/workflows`, `app` | [prérequis §3, §6](../04-prerequis.md) · [architecture §9](../03-architecture.md) |

**À faire**

1. Signature `release` lue depuis des secrets (jamais depuis le dépôt) ; numérotation des versions.
2. Workflow `release` : sur étiquette `v*`, construction de l'AAB signé, garde-fous, mesure de taille, publication en artefact.
3. `docs/store/publication.md` : procédure pas à pas — Play App Signing, piste interne, test fermé
   (douze testeurs pendant quatorze jours pour un compte personnel), demande d'accès à la production.
4. Remplacer les emplacements de `keys-release.json` par les clés publiques de production fournies par le porteur du projet.

**Critères d'acceptation**

- [ ] L'AAB produit par le workflow passe `:app:verifyReleaseManifest` et la vérification d'alignement.
- [ ] Aucun secret dans les journaux du workflow ni dans l'artefact.
- [ ] Le build `release` refuse les jetons signés par les clés de test.

---

### M11-06 — Recette et soumission

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10, M11-01 à M11-05 | — | [cahier des charges §7](../02-cahier-des-charges.md#7-recette-de-la-v1) · [conformité §10](../05-conformite-play-store.md#10-liste-de-contrôle-avant-soumission) |

**À faire**

1. Dérouler la recette de la v1 sur un appareil bas de gamme et un appareil moyen ; consigner les résultats dans `docs/mesures/recette-v1.md`.
2. Dérouler la liste de contrôle avant soumission.
3. Corriger les défauts bloquants ; lister les défauts acceptés.

**Critères d'acceptation**

- [ ] Les six points de la recette sont satisfaits ou leur écart est accepté par écrit.
- [ ] Les huit points de la liste de contrôle de conformité sont cochés.

**Validation manuelle** — l'ensemble du ticket ; soumission par le porteur du projet.
