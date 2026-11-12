# M11 — Publication

**Objectif.** Un dossier Google Play complet et conforme, une version en test fermé, puis en production.
La variante publiée est `ads` ; la variante `offline` est construite sur le même commit, prête à la remplacer.

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

- [ ] Aucun terme proscrit par la conformité §7 — en particulier ni « sans accès à Internet » ni
      « zéro cloud » pour la variante `ads` ; aucune promesse que le produit ne tient pas (retour à la source).
- [ ] Un second jeu de textes est prêt pour la variante `offline`.
- [ ] Longueurs conformes aux limites de la Play Console.

---

### M11-02 — Site et politique de confidentialité

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | — | `site/`, `.github/workflows` | [sécurité §7](../07-securite-vie-privee.md#7-politique-de-confidentialité) · [prérequis §2.1](../04-prerequis.md#21-site-sur-github-pages) |

**À faire**

1. Pages statiques dans `site/` : accueil, FAQ, assistance, politique de confidentialité (FR et EN),
   selon le plan de la sécurité §7. La page de relais des liens d'activation existe déjà (M8-05).
2. FAQ : ce qui est lu et ce qui ne l'est pas ; familles d'applications ; limite de la détection des
   fenêtres sécurisées sur Android 11 à 13 ; réglage des navigateurs autorisant les captures en
   navigation privée ; publicité (à la demande, jamais ciblée sur ce qui a été lu) ; packs de langue ;
   suppression des données.
3. Workflow `pages` : publication de `site/` sur GitHub Pages.
4. Modèle de fichier `app-ads.txt` et marche à suivre pour le placer à la racine du site du compte
   (prérequis §2.1).

**Critères d'acceptation**

- [ ] La politique décrit exactement le comportement de l'application (relecture croisée avec la divulgation).
- [ ] La section « Publicité » décrit les données collectées par le SDK de Google et le moyen de modifier ses choix.

**Validation manuelle** — relecture juridique ; activation de GitHub Pages et mise en place de `app-ads.txt` par le porteur du projet.

---

### M11-03 — Déclarations Play Console

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M11-02 | `docs/store` | [conformité §5](../05-conformite-play-store.md#5-déclarations-dans-la-play-console) |

**À faire**

1. `docs/store/declarations.md` : réponses rédigées, question par question, pour la déclaration
   d'accessibilité, la sécurité des données (dont les données du SDK publicitaire, selon le guide de
   Google en vigueur), la publicité, l'identifiant publicitaire, le public cible et la classification.
   Un second jeu de réponses pour la variante `offline`.
2. Relire la page de politique de Google le jour de la rédaction et signaler tout écart avec le document de conformité.

**Critères d'acceptation**

- [ ] Chaque réponse est exacte au regard du code (permissions du manifeste de la variante, absence de
      transmission des données mémorisées, finalité « publicité » non cochée pour l'accessibilité).
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

### M11-05 — Chaîne de publication et test fermé

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10-04 | `.github/workflows`, `app` | [prérequis §3, §6](../04-prerequis.md) · [architecture §9](../03-architecture.md) |

**À faire**

1. Signature `release` lue depuis des secrets (jamais depuis le dépôt) ; numérotation des versions.
2. Workflow `release` : sur étiquette `v*`, construction des lots signés des variantes `ads` et
   `offline`, garde-fous, mesure de taille, publication en artefacts.
3. Identifiants AdMob de production lus dans les propriétés de build ; le build `release` échoue s'ils
   sont absents ou s'ils valent les identifiants de test.
4. `docs/store/publication.md` : procédure pas à pas — Play App Signing, piste interne (validation du
   pack de langue), test fermé, demande d'accès à la production.
5. `docs/store/test-ferme.md` : recrutement et protocole du test fermé. Au moins douze testeurs pendant
   quatorze jours si le compte y est soumis ; **parmi eux des appareils sous Android 11 à 13 et des
   Samsung ou Xiaomi** (risque R-14), avec pour ces testeurs le protocole des écrans sensibles et un
   relevé de la tenue de la mémorisation sur 48 heures.
6. Remplacer l'emplacement de `keys-release.json` par la clé publique de licence fournie par le porteur du projet.

**Critères d'acceptation**

- [ ] Chaque lot produit par le workflow passe la vérification de manifeste de sa variante et la vérification d'alignement.
- [ ] Aucun secret dans les journaux du workflow ni dans l'artefact.
- [ ] Le build `release` refuse les jetons signés par les clés de test.

---

### M11-06 — Recette et soumission

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10, M11-01 à M11-05 | — | [cahier des charges §7](../02-cahier-des-charges.md#7-recette-de-la-v1) · [conformité §10](../05-conformite-play-store.md#10-liste-de-contrôle-avant-soumission) |

**À faire**

1. Dérouler la recette de la v1 sur le téléphone de test et les émulateurs ; consigner les résultats dans `docs/mesures/recette-v1.md`.
2. Dérouler la liste de contrôle avant soumission.
3. Corriger les défauts bloquants ; lister les défauts acceptés.

**Critères d'acceptation**

- [ ] Tous les points de la recette sont satisfaits ou leur écart est accepté par écrit.
- [ ] Tous les points de la liste de contrôle de conformité sont cochés.
- [ ] Les retours des testeurs sous Android 11 à 13, Samsung et Xiaomi sont consignés et traités.

**Validation manuelle** — l'ensemble du ticket ; soumission par le porteur du projet.
