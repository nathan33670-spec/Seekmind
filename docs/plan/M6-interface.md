# M6 — Interface

**Objectif.** Une application utilisable de bout en bout : accueil et consentement, recherche, détail,
journal, réglages, verrouillage, en français et en anglais.

**Sortie du jalon.** Le parcours P1 puis P2 du [cahier des charges §3](../02-cahier-des-charges.md#3-parcours-de-référence)
se déroule sur un vrai téléphone. Première prise en main par le porteur du projet.

Toute chaîne ajoutée est fournie en français et en anglais dès son ticket ; M6-08 vérifie l'ensemble.

---

### M6-01 — Système de design et navigation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M0-01 | `core:designsystem`, `app` | [ui-ux §1, §2, §4](../specs/ui-ux.md) |

**À faire**

1. `SeekMindTheme` : couleur dynamique avec palette de repli, thèmes clair et sombre, typographie, formes.
2. Composants de la spec §4, chacun avec ses aperçus (clair, sombre, police à 200 %).
3. Graphe de navigation à routes typées, barre inférieure à trois destinations, écrans vides.
4. Bord à bord, marges système, geste de retour prédictif ; `FLAG_SECURE` sur `MainActivity`.

**Critères d'acceptation**

- [x] Tests Compose : navigation entre les trois destinations ; retour arrière.
- [x] `HighlightedText` surligne les intervalles fournis ; `ResultCard` affiche tous ses champs.
- [x] Contrôles automatiques d'accessibilité activés dans les tests Compose du module (à partir d'Android 14 :
      Compose ne les propose pas avant).

---

### M6-02 — Accueil, divulgation, familles, activation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-01, M2-01, M2-05 | `feature:onboarding` | [ui-ux §3.1](../specs/ui-ux.md) · [conformité §4](../05-conformite-play-store.md#4-écran-de-divulgation) · [confidentialite §3.4](../specs/confidentialite.md) · EF-ONB-01 à 05, 07 |

**À faire**

1. Trois pages de présentation.
2. Écran de divulgation : texte de la version 1 (FR et EN), constante `DISCLOSURE_VERSION`, bouton
   « J'accepte » actif une fois le bas du texte atteint, refus par « Non merci » ou retour arrière.
3. Enregistrement du consentement (version, date) à l'acceptation.
4. Écran des familles d'applications : quatre cases cochées par défaut, icônes de quelques applications
   installées par famille, rappel des exclusions d'office ; choix enregistré avant toute redirection.
5. Écran d'activation : étapes illustrées, ouverture des réglages d'accessibilité, détection au retour, confirmation.
6. Parcours de refus : mode limité et bandeau permanent qui rouvre la divulgation.
7. Reconsentement quand `DISCLOSURE_VERSION` dépasse la version enregistrée.
8. Texte de divulgation de la variante `ads` (celui de la variante `offline` arrive avec M8-06).
9. Emplacement pour la proposition du pack de langue en fin de parcours (branchée par M6-06).

**Critères d'acceptation**

- [ ] Aucune redirection vers les réglages système sans consentement **et** choix des familles enregistrés.
- [ ] Une famille décochée à l'accueil ne produit aucune capture (avec E2E-19).
- [ ] Aucune fenêtre de consentement publicitaire dans ce parcours.
- [ ] « J'accepte » est inactif tant que le texte n'a pas été entièrement affiché ; le retour arrière vaut refus.
- [ ] Après refus, le bandeau rouvre la divulgation.
- [ ] Incrément de version → la capture s'arrête jusqu'à la nouvelle acceptation (avec E2E-09).
- [ ] Les textes FR et EN sont, mot pour mot, ceux du document de conformité.

**Validation manuelle** — parcours complet sur un vrai téléphone, chronométré (cible : moins d'une minute).

---

### M6-03 — Écran de recherche

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M6-01, M5-06 | `feature:search` | [ui-ux §3.2](../specs/ui-ux.md) · EF-SRCH-05 à 09 |

**À faire**

1. `SearchViewModel` : états du tableau de la spec ; annulation de la recherche précédente ; émissions
   lexicale puis hybride ; historique.
2. Champ de recherche, pastilles de filtres (période, application), pastilles des filtres compris dans la requête.
3. Liste de `ResultCard` ; états vide, en cours, assoupli, sans résultat, index sémantique incomplet, base indisponible.
4. Interface `EntitlementGate` appelée avant et après la recherche ; en attendant M8, une implémentation
   « tout autorisé » est liée par Hilt.

**Critères d'acceptation**

- [x] Un test de ViewModel par état du tableau.
- [x] Les résultats par mots-clés s'affichent avant les résultats hybrides ; une nouvelle requête annule l'ancienne.
- [x] Supprimer une pastille de filtre relance la recherche sans ce filtre.
- [x] L'historique respecte le réglage « désactivé ».

---

### M6-04 — Détail d'une capture

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M6-03 | `feature:search` | [ui-ux §3.3](../specs/ui-ux.md) · EF-JRN-02 |

**À faire**

1. Écran de détail : métadonnées, texte complet sélectionnable et surligné, mention d'origine OCR.
2. Actions : copier (presse-papiers marqué sensible), partager, supprimer avec confirmation.
3. Emplacement pour le bouton de retour à la source (M7-03).

**Critères d'acceptation**

- [x] Suppression → la capture disparaît de la recherche et du journal.
- [x] La copie pose le drapeau « contenu sensible ».

---

### M6-05 — Journal

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M6-01, M1-04 | `feature:journal` | [ui-ux §3.4](../specs/ui-ux.md) · EF-JRN-01, EF-JRN-03 |

**À faire**

1. Liste par jour avec en-têtes collants ; chargement par pages de 50 (curseur) ; filtre par application.
2. Suppression par balayage avec annulation ; suppression d'une journée.

**Critères d'acceptation**

- [x] Défilement sur une base synthétique de 100 000 captures sans chargement complet en mémoire.
- [x] Annuler une suppression restaure la ligne ; ne pas annuler supprime la capture en base.
- [x] Le journal est accessible avec un solde de crédits nul.

---

### M6-06 — Réglages

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-01, M2-07, M1-06, M4-07 | `feature:settings`, `feature:onboarding` | [ui-ux §3.5](../specs/ui-ux.md) · [embeddings §5](../specs/embeddings.md) · EF-SET · EF-PRIV-05, 08, 09, 12 · EF-LANG-02 à 04 |

**À faire**

1. Rubriques Mémorisation, Applications, Données, Sécurité, Langues de recherche, Recherche, Diagnostic, À propos.
   La rubrique Activation arrive avec M8-04 et M8-05.
2. Applications : les quatre familles avec interrupteur ; liste des applications lançables, famille,
   état effectif et son origine, recherche, liste stricte verrouillée, confirmation pour la liste souple.
3. Données : rétention, budget, statistiques, suppressions par période et par application, « Tout effacer » avec saisie de confirmation.
4. Diagnostic : compteurs du jour et des 7 derniers jours ; partage d'un texte sans contenu.
5. Retrait du consentement : arrête la capture et propose d'effacer les données.
6. Langues de recherche : modèle actif, packs (état, progression), utiliser, supprimer, importer un
   fichier de pack, avancement de la reconstruction de l'index ; confirmation avant de changer de modèle.
7. Proposition du pack multilingue à la fin de l'accueil quand aucune langue du téléphone n'est le français.

**Critères d'acceptation**

- [ ] Exclure une application ou désactiver une famille prend effet à la capture suivante (avec E2E-11 et E2E-19).
- [ ] Changer de modèle demande une confirmation et affiche l'avancement de la reconstruction.
- [ ] Changer la rétention est pris en compte par la maintenance suivante.
- [ ] « Tout effacer » appelle `WipeService` et ramène à la première utilisation.
- [ ] Le texte de diagnostic partagé ne contient aucun contenu capturé, titre, URL ni requête.

---

### M6-07 — Verrouillage

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-01 | `app`, `feature:settings` | [ui-ux §3.6](../specs/ui-ux.md) · EF-PRIV-10, EF-PRIV-11 · EF-ONB-06 |

**À faire**

1. `AppLock` : `BiometricPrompt` (biométrie faible ou code de l'appareil), délai de grâce de 5 minutes, état observable.
2. Tant que l'application est verrouillée, seul l'écran neutre est composé.
3. Proposition d'activation à la fin de l'accueil ; réglage ; cas d'un appareil sans verrouillage sécurisé.
4. `FLAG_SECURE` sur toutes les activités.

**Critères d'acceptation**

- [ ] Test Compose : verrouillé → aucun nœud de contenu dans l'arbre de l'interface.
- [ ] Retour au premier plan avant 5 minutes → pas de nouvelle authentification ; après → authentification.
- [ ] Toutes les activités déclarées portent `FLAG_SECURE` (test parcourant le manifeste).

**Validation manuelle** — déverrouillage par empreinte et par code sur un vrai téléphone ; aperçu masqué dans les applications récentes.

---

### M6-08 — Langues et accessibilité

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-02 à M6-07 | `feature:*`, `core:designsystem` | [ui-ux §5, §6](../specs/ui-ux.md) · ENF-A11Y-01 · ENF-I18N-01 |

**À faire**

1. Relecture complète des chaînes FR et EN : vocabulaire fixe, pluriels, dates localisées.
2. Libellés TalkBack, ordre de lecture, actions personnalisées des cartes, cibles tactiles, contraste.
3. Vérification à 200 % de taille de police et en thème sombre sur chaque écran.
4. Mise en page sur grand écran (largeur de contenu limitée).

**Critères d'acceptation**

- [ ] `lint` sans chaîne manquante ni texte en dur.
- [ ] Contrôles automatiques d'accessibilité verts sur tous les écrans testés.

**Validation manuelle** — parcours P1, P2 et P5 avec TalkBack, puis à 200 % de taille de police ;
liste des défauts corrigés dans la PR.

---

### M6-09 — État du service et dépannage

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6-03, M2-01 | `feature:search`, `feature:settings` | [ui-ux §3.2](../specs/ui-ux.md) · EF-CAP-05 · risque R-04 |

**À faire**

1. Bandeau d'état sur l'écran de recherche pour chaque état non actif, avec son action.
2. Indication « Dernière mémorisation il y a … ».
3. Page d'aide « Le service s'arrête tout seul » : explication et étapes par constructeur (Samsung,
   Xiaomi, Oppo, Huawei, OnePlus) pour exclure l'application de l'optimisation de batterie — texte
   uniquement, aucune permission demandée.

**Critères d'acceptation**

- [ ] Chaque état de `CaptureStatus` produit le bon bandeau et la bonne action.
- [ ] Service désactivé dans les réglages système → bandeau « Réactiver » au retour dans l'application.
