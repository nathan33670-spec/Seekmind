# M9 — Actions contextuelles — reporté en v1.1

> **Hors de la première publication** (décision D-05). Les tickets ci-dessous sont prêts mais ne doivent
> pas être entrepris avant la sortie de la v1. À leur démarrage : créer le module `engine:actions`,
> rétablir l'étape de notification du pipeline de capture, passer la divulgation en version 2.

**Objectif.** Proposer, au bon moment et sans jamais agir seul, d'envoyer une date, une adresse ou un
numéro de suivi vers l'application adéquate.

**Sortie du jalon.** Scénario E2E-16 vert.

---

### M9-01 — Détection d'entités

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | M1-01 | `engine:actions` | [actions §2](../specs/actions-intelligentes.md) · EF-ACT-01 |

**À faire**

1. `AbsoluteDateTimeExtractor` (FR et EN), Kotlin pur.
2. `TrackingDetector` : formes de la spec, chiffre de contrôle S10, détection générique par mot-clé.
3. Enveloppe du classifieur de texte du système pour les adresses, téléphones, e-mails et URL, avec
   repli sur `android.util.Patterns` ; borne de longueur.
4. `EntityDetector` combinant le tout.

**Critères d'acceptation**

- [ ] Dates : table de cas FR et EN (formats, année implicite, heure adjacente) ; les dates passées sont ignorées.
- [ ] Suivi : formes valides reconnues ; numéros de téléphone, de commande et de carte non reconnus.
- [ ] Classifieur absent → aucune adresse, sans erreur.

**Validation manuelle** — vérifier chaque forme de numéro de suivi et chaque lien de transporteur sur
des exemples réels ; retirer ce qui n'est pas confirmé.

---

### M9-02 — Résolution des actions

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | M9-01 | `engine:actions` | [actions §3](../specs/actions-intelligentes.md) · EF-ACT-03 |

**À faire**

1. `ActionResolver` : un Intent par type d'entité ; libellés FR et EN ; vérification qu'une activité répond.
2. Action « Copier » (presse-papiers marqué sensible).

**Critères d'acceptation**

- [ ] Chaque Intent a l'action, les données et les extras attendus (tests Robolectric).
- [ ] Aucune application ne répond → pas d'action proposée.
- [ ] Aucune permission d'agenda, de contacts ou de téléphone n'est demandée.

---

### M9-03 — Pastille

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | M9-02, M2-06 | `engine:actions`, `engine:capture` | [actions §1, §4](../specs/actions-intelligentes.md) · EF-ACT-02, 05, 06 · INV-02 |

**À faire**

1. `ActionsCoordinator` (implémente `CaptureListener`) : détection hors thread de capture, priorités et filtres de pertinence.
2. Limites de fréquence, mémoire des entités déjà proposées, suspension après fermetures répétées.
3. `PillOverlay` : fenêtre de type superposition d'accessibilité, non focalisable, vue classique,
   affichage de 6 secondes, retrait au changement de fenêtre, tap, fermeture.
4. Respect du réglage « Actions contextuelles ».

**Critères d'acceptation**

- [ ] Priorités : suivi, puis date pertinente, puis adresse ; une seule pastille par écran.
- [ ] Une date sans heure ni mot-clé d'événement ne déclenche rien.
- [ ] Limites : 60 secondes, 10 par jour, 24 heures pour une même entité, suspension après trois fermetures (horloge simulée).
- [ ] Réglage désactivé → aucune fenêtre ajoutée.
- [ ] Garde-fou INV-02 vert : aucune API d'action dans le module.

**Validation manuelle** — sur trois applications : la pastille n'empêche ni la saisie ni la navigation
gestuelle, et disparaît au changement d'écran.

---

### M9-04 — Actions dans le détail d'une capture

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | M9-02, M6-04 | `feature:search` | [actions §4.3](../specs/actions-intelligentes.md) · EF-ACT-04 |

**À faire**

1. À l'ouverture du détail, détection de toutes les entités du texte ; affichage en pastilles d'action.

**Critères d'acceptation**

- [ ] Les six types d'entités produisent leur pastille ; un tap lance l'Intent correspondant.
- [ ] La détection ne bloque pas l'affichage du texte.

---

### M9-05 — Tests de bout en bout des actions

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | M9-03 | `app`, `fixtureapp` | [tests §4](../06-qualite-tests-ci.md#4-tests-de-bout-en-bout) |

**À faire**

1. `EntitiesActivity` dans l'application de test.
2. Scénario E2E-16 ; scénario « réglage désactivé → pas de pastille ».

**Critères d'acceptation**

- [ ] E2E-16 vert sur les trois niveaux d'API : la pastille apparaît et un tap émet l'Intent d'agenda attendu.
