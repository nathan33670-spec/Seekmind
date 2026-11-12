# M10 — Durcissement

**Objectif.** Tenir les cibles de performance et d'autonomie sur de vrais téléphones, fermer la revue de
sécurité, résister aux pannes.

**Sortie du jalon.** La recette du [cahier des charges §7](../02-cahier-des-charges.md#7-recette-de-la-v1)
passe sur un appareil bas de gamme et un appareil moyen.

Ce jalon repose largement sur des mesures faites par un humain sur appareil ; l'agent prépare le
protocole et l'outillage, puis corrige d'après les résultats.

---

### M10-01 — Campagne batterie et thermique

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3, M4, M9 | `docs/mesures`, modules concernés | [tests §6](../06-qualite-tests-ci.md#6-mesures-de-performance) · ENF-BAT-01, ENF-BAT-02, ENF-MEM-01, ENF-STO-01 |

**À faire**

1. Rédiger le protocole de 24 heures (scénario d'usage, commandes de relevé, grille de résultats).
2. Ajouter au diagnostic local ce qui manque pour l'interpréter : temps de calcul cumulé par poste
   (lecture de l'arbre, sonde, OCR, embeddings), nombre de réveils.
3. D'après les relevés, ajuster les réglages (`CaptureConfig`, `OcrConfig`, `IndexConfig`) et corriger les postes excessifs.

**Critères d'acceptation**

- [ ] Rapport dans `docs/mesures/` pour deux appareils : consommation attribuable, temps de calcul par poste, mémoire, croissance du stockage.
- [ ] ENF-BAT-01 (≤ 3 % par jour) tenu, ou écart documenté avec la mesure corrective retenue.
- [ ] Aucun travail écran éteint hors lots d'embeddings planifiés.

**Validation manuelle** — exécution du protocole par le porteur du projet (deux fois : mémorisation active, puis en pause).

---

### M10-02 — Mémoire et isolement du moteur

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10-01 | `engine:embedding` | [embeddings §7](../specs/embeddings.md) · ENF-MEM-02 · ENF-REL-01 |

**À faire**

1. Analyser les relevés mémoire de M10-01 : pic pendant les lots, arrêts du processus par le système.
2. **Si** le pic dépasse 400 Mo ou si le service est arrêté pendant les lots sur l'appareil bas de gamme :
   déplacer le moteur d'embeddings dans un processus dédié (service lié, interface AIDL transportant
   textes et vecteurs ; la base reste dans le processus principal), par un ADR.
3. Sinon : consigner la décision de ne pas isoler.

**Critères d'acceptation**

- [ ] Décision argumentée par les mesures, consignée (ADR ou rapport).
- [ ] Si isolement : l'arrêt du processus du moteur n'interrompt pas le service de capture (test).

---

### M10-03 — Filtrage du texte récurrent

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-07 | `engine:index` | [capture §4.2](../specs/capture.md) · EF-SRCH-01 |

**À faire**

1. Par application, tenir la fréquence des lignes courtes sur les 50 dernières captures ; une ligne
   présente dans plus de 60 % d'entre elles (barre d'onglets, menus) est retirée du texte soumis au
   modèle d'embeddings. Elle reste dans le texte stocké et dans l'index plein texte.
2. Mesurer l'effet sur le jeu d'évaluation, enrichi de captures comportant ce bruit.

**Critères d'acceptation**

- [ ] Le Rappel@5 hybride ne baisse pas ; le rapport d'évaluation avant et après est joint.
- [ ] Le filtre est désactivable par configuration ; sans lui, le comportement est celui d'avant.

---

### M10-04 — Démarrage, R8, profils de référence

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6, M8 | `app`, `benchmark` | [architecture §9](../03-architecture.md) · [ui-ux §7](../specs/ui-ux.md) · ENF-PERF-06 |

**À faire**

1. Règles R8 pour ONNX Runtime, SQLCipher, ML Kit, Room, Hilt ; exécution de la suite instrumentée sur le build `release`.
2. Profil de référence (Macrobenchmark) pour le démarrage et la première recherche.
3. Mesure du démarrage à froid ; suppression de tout travail inutile avant le premier affichage.

**Critères d'acceptation**

- [ ] La suite instrumentée est verte sur le build `release` minifié.
- [ ] Démarrage à froid jusqu'au champ de recherche ≤ 1,5 s sur l'appareil moyen (mesure consignée).
- [ ] `scripts/check-elf-alignment.sh` vert sur l'APK `release`.

---

### M10-05 — Revue de sécurité

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8, M9 | tous | [sécurité §8](../07-securite-vie-privee.md#8-liste-de-contrôle-de-sécurité-ticket-m10-05) · ENF-SEC-01 |

**À faire**

1. Dérouler la liste de contrôle de sécurité, point par point, et corriger chaque écart.
2. Activer la vérification des empreintes des dépendances par Gradle (`gradle/verification-metadata.xml`).
3. Tests de robustesse de `LinkActivity` (entrées aléatoires).
4. Recherche de contenu utilisateur dans le journal système pendant la suite de bout en bout (étape de CI).

**Critères d'acceptation**

- [ ] Chaque point de la liste est coché avec sa preuve (test, commande, ou constat) dans la PR.
- [ ] La CI échoue si le journal système contient l'un des mots distinctifs de l'application de test.

**Validation manuelle** — protocole des écrans sensibles ([tests §8](../06-qualite-tests-ci.md#8-validation-manuelle)).

---

### M10-06 — Robustesse

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6, M8 | tous | ENF-REL-01, ENF-REL-02 |

**À faire**

1. Arrêt du processus pendant une capture, pendant un lot d'embeddings, pendant une purge : reprise sans perte ni corruption.
2. Base corrompue, clé perdue, espace de stockage saturé : détection, message, réinitialisation proposée.
3. Mise à jour de l'application : migrations, changement de modèle, conservation des réglages et du solde.

**Critères d'acceptation**

- [ ] E2E-18 vert ; tests d'interruption de transaction (aucune capture partielle).
- [ ] Stockage saturé → la capture se suspend avec un compteur de diagnostic, sans plantage.
- [ ] Test de mise à jour : base de la version précédente ouverte par la version courante.

---

### M10-07 — Modèle allégé (optionnel)

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-07 | `tools/model`, `engine:embedding` | [ADR-0006](../adr/0006-modele-d-embeddings.md) · ENF-STO-02 · risque R-06 |

**À faire**

1. Évaluer sur le jeu d'évaluation, à qualité et latence comparées : `french-me5-small` quantifié ; et
   `multilingual-e5-small` dont le vocabulaire est réduit aux jetons utiles au français et à l'anglais.
2. Si un candidat tient les seuils de EF-SRCH-01 à 03 (y compris les requêtes en anglais et bilingues) :
   ADR de remplacement, nouveau `modelId`, mise à jour de l'outillage.

**Critères d'acceptation**

- [ ] Tableau comparatif : taille, Rappel@5 et MRR par type de requête, latence.
- [ ] Décision consignée ; en cas d'adoption, taille de téléchargement mesurée.

---

### M10-08 — Spécificités des versions récentes d'Android

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6 | `app`, `feature:onboarding` | [conformité §9](../05-conformite-play-store.md#9-restrictions-de-plateforme-à-connaître) · risque R-05 |

**À faire**

1. Mode Protection avancée (Android 16+) : détecter l'état ; si le service ne peut pas être activé,
   l'expliquer dans l'écran d'activation. Ajouter la permission d'interrogation de cet état à la liste
   blanche si elle est nécessaire.
2. Vérifier le comportement sur Android 16 et 17 : bord à bord, retour prédictif, grands écrans.
3. Relire les changements de comportement d'Android 17 pour préparer le passage de `targetSdk` à 37.

**Critères d'acceptation**

- [ ] Protection avancée active → message explicatif, pas de boucle vers les réglages.
- [ ] Note `docs/mesures/android-17.md` : points à traiter avant de cibler l'API 37.

**Validation manuelle** — parcours P1 sur un appareil Android 16 ou 17.
