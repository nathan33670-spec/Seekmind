# M10 — Durcissement

**Objectif.** Tenir les cibles de performance et d'autonomie sur de vrais téléphones, fermer la revue de
sécurité, résister aux pannes.

**Sortie du jalon.** La recette du [cahier des charges §7](../02-cahier-des-charges.md#7-recette-de-la-v1)
passe sur le téléphone de test et sur les émulateurs.

Ce jalon repose largement sur des mesures faites par un humain sur appareil ; l'agent prépare le
protocole et l'outillage, puis corrige d'après les résultats.

---

### M10-01 — Campagne batterie et thermique

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3, M4, M8 | `docs/mesures`, modules concernés | [tests §6](../06-qualite-tests-ci.md#6-mesures-de-performance) · ENF-BAT-01, ENF-BAT-02, ENF-MEM-01, ENF-STO-01 |

**À faire**

1. Rédiger le protocole de 24 heures (scénario d'usage, commandes de relevé, grille de résultats).
2. Ajouter au diagnostic local ce qui manque pour l'interpréter : temps de calcul cumulé par poste
   (lecture de l'arbre, sonde, OCR, embeddings), nombre de réveils.
3. Variante `ads` : vérifier que le SDK publicitaire ne consomme rien (processeur, réseau, réveils) en
   dehors des demandes de publicité.
4. D'après les relevés, ajuster les réglages (`CaptureConfig`, `OcrConfig`, `IndexConfig`) et corriger les postes excessifs.
5. Faire, sur le même téléphone, les validations manuelles reportées ici par le porteur (décision du
   15 novembre 2026 : toutes, le plus tard possible), puis retirer la mention « reportée » du statut de
   chaque ticket. La liste à jour est celle des tickets dont le statut porte « validation manuelle
   reportée à M10-01 » ; au 15 novembre 2026 :
   - **M0-07** : 20 reconnaissances ML Kit en mode avion, installation neuve (prototype S2) ;
   - **M0-08** : lecture de l'arbre de trois applications courantes et sonde sur un onglet privé de
     Chrome (prototype S3) ;
   - **M0-09** : mesures du prototype d'embeddings (S4) ;
   - **M2-05** : marqueurs de navigation privée observés sur Chrome et Firefox ; table mise à jour ;
   - **M3-02** : onglet privé de Chrome et de Firefox (Android 14+) : aucune capture ;
   - **M5-08** : bancs de la recherche à 100 000 captures ;
   - **M6-02** : parcours d'accueil complet sur le téléphone, chronométré (cible : moins d'une minute) ;
     refus puis réouverture par le bandeau ;
   - **M6-07** : déverrouillage par empreinte et par code ; aperçu masqué dans les applications récentes,
     y compris pendant la confirmation de Play d'un pack de langue (`PlayCoreDialogWrapperActivity`) ;
   - **M8-03** : annonce de test affichée et récompense signalée (build `debug`) ; fenêtre de
     consentement en région Europe ; formulaire des choix de confidentialité accessible ;
   - **M7-02** : les sept règles par application (tableau de la PR #40) ; une règle qui échoue est
     retirée de `source_rules.json`.
   - **M2-08** : une heure d'usage normal, puis relevé dans Réglages → Diagnostic du nombre de
     captures, des rejets par motif et de la durée médiane de lecture de l'arbre.
   - **M10-05** : inspection du trafic pendant une demande de publicité (proxy sur le téléphone : aucun
     texte mémorisé, aucune requête de recherche, aucun nom d'application) ; stockage privé après une
     journée d'usage ; protocole des écrans sensibles ([tests §8](../06-qualite-tests-ci.md#8-validation-manuelle)) ;
     durée de la première capture d'une application après un démarrage (relecture des lignes
     récurrentes, M10-03).

   Une validation qui échoue rouvre le ticket (statut `En cours`) et bloque M10-01 jusqu'à sa correction.

**Critères d'acceptation**

- [ ] Rapport dans `docs/mesures/` pour le téléphone de test et l'émulateur à mémoire réduite : consommation attribuable, temps de calcul par poste, mémoire, croissance du stockage.
- [ ] ENF-BAT-01 (≤ 3 % par jour) tenu, ou écart documenté avec la mesure corrective retenue.
- [ ] Aucun travail écran éteint hors lots d'embeddings planifiés.

**Validation manuelle** — exécution du protocole par le porteur du projet (deux fois : mémorisation active, puis en pause).

---

### M10-02 — Mémoire et isolement des moteurs

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M10-01 | `engine:embedding`, `engine:ads` | [embeddings §8](../specs/embeddings.md) · [ADR-0009](../adr/0009-publicite-admob-et-variante-sans-reseau.md) · ENF-MEM-02 · ENF-REL-01 · risque R-15 |

**À faire**

1. Analyser les relevés mémoire de M10-01 : pic pendant les lots, arrêts du processus par le système.
2. **Si** le pic dépasse 400 Mo ou si le service est arrêté pendant les lots sur l'appareil bas de gamme :
   déplacer le moteur d'embeddings dans un processus dédié (service lié, interface AIDL transportant
   textes et vecteurs ; la base reste dans le processus principal), par un ADR.
3. Sinon : consigner la décision de ne pas isoler.
4. **SDK publicitaire** : étudier son exécution dans un processus dédié (activité d'annonce, composant
   d'initialisation et travaux d'arrière-plan du SDK), pour qu'il ne partage plus le processus du
   service d'accessibilité. Prototype, mesure de la stabilité, puis décision consignée par un ADR.

**Critères d'acceptation**

- [ ] Deux décisions argumentées et consignées (ADR ou rapport) : moteur d'embeddings, SDK publicitaire.
- [ ] Si isolement : l'arrêt du processus du moteur n'interrompt pas le service de capture (test).

---

### M10-03 — Filtrage du texte récurrent

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M5-07 | `engine:index`, `engine:search`, `core:model` | [capture §4.2](../specs/capture.md) · EF-SRCH-01 |

**À faire**

1. Par application, tenir la fréquence des lignes courtes sur les 50 dernières captures ; une ligne
   présente dans plus de 60 % d'entre elles (barre d'onglets, menus) est retirée du texte soumis au
   modèle d'embeddings. Elle reste dans le texte stocké et dans l'index plein texte.
2. Mesurer l'effet sur le jeu d'évaluation, enrichi de captures comportant ce bruit.
3. Analyseur temporel : un jour de la semaine précédé de « le » (« cours de yoga le lundi », « réunion le
   jeudi ») peut désigner une habitude, et non une date. Aujourd'hui, il devient un filtre de date, et la
   requête q78 du jeu d'évaluation est manquée (recherche §11.1). À trancher sur des requêtes réelles :
   ignorer cette forme, ou chercher avec et sans le filtre. *Décision du porteur (20 novembre 2026) :
   chercher avec et sans le filtre (recherche §3).*

**Critères d'acceptation**

- [x] Le Rappel@5 hybride ne baisse pas ; le rapport d'évaluation avant et après est joint (PR #73 :
      0,863 → 0,950 pour `fr`, 0,863 → 0,963 pour `multi`).
- [x] Le filtre est désactivable par configuration ; sans lui, le comportement est celui d'avant.

---

### M10-04 — Démarrage, R8, profils de référence

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6, M8 | `app`, `benchmark` | [architecture §9](../03-architecture.md) · [ui-ux §7](../specs/ui-ux.md) · ENF-PERF-06 |

**À faire**

1. Règles R8 pour ONNX Runtime, SQLCipher, ML Kit, Room, Hilt ; exécution de la suite instrumentée sur le build `release`.
2. Profil de référence (Macrobenchmark) pour le démarrage et la première recherche.
3. Mesure du démarrage à froid ; suppression de tout travail inutile avant le premier affichage.
4. Rendre de nouveau bloquant `IngestDeviceTest` (ENF-PERF-05), mesuré seulement pendant le
   développement : 15 ms au 95ᵉ centile, sur le build `release` et sur le téléphone de test.
5. Rendre de nouveau bloquant le 99ᵉ centile de `RoutingDeviceTest` (ENF-PERF-01), mesuré seulement
   pendant le développement : 2 ms par événement sur le thread principal, sur le build `release` et
   sur le téléphone de test.

**Critères d'acceptation**

- [ ] La suite instrumentée est verte sur le build `release` minifié.
- [ ] Démarrage à froid jusqu'au champ de recherche ≤ 1,5 s sur l'appareil moyen (mesure consignée).
- [ ] `scripts/check-elf-alignment.sh` vert sur l'APK `release`.
- [ ] `IngestDeviceTest` affirme de nouveau 15 ms au 95ᵉ centile (ENF-PERF-05), et la mesure sur le
      téléphone de test est consignée dans `docs/mesures/`.

---

### M10-05 — Revue de sécurité

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait — validation manuelle reportée à M10-01 | M8 | tous | [sécurité §8](../07-securite-vie-privee.md#8-liste-de-contrôle-de-sécurité-ticket-m10-05) · ENF-SEC-01 |

**À faire**

1. Dérouler la liste de contrôle de sécurité, point par point, et corriger chaque écart.
2. Activer la vérification des empreintes des dépendances par Gradle (`gradle/verification-metadata.xml`).
3. Tests de robustesse de `LinkActivity` (entrées aléatoires).
4. Recherche de contenu utilisateur dans le journal système pendant la suite de bout en bout (étape de CI).
5. Inspection du trafic réseau pendant une demande de publicité (proxy sur le téléphone de test) :
   aucun texte mémorisé, aucune requête de recherche, aucun nom d'application dans les requêtes.

**Critères d'acceptation**

- [x] Chaque point de la liste est coché avec sa preuve (test, commande, ou constat) dans la PR.
- [x] La CI échoue si le journal système contient l'un des mots distinctifs de l'application de test.

**Validation manuelle** — protocole des écrans sensibles ([tests §8](../06-qualite-tests-ci.md#8-validation-manuelle)).

---

### M10-06 — Robustesse

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M6, M8 | tous | ENF-REL-01, ENF-REL-02 |

**À faire**

1. E2E-18 (repris de M2-08), joué au niveau d'`adb` : tuer le processus, rouvrir un écran, le service
   reprend et capture. Arrêt du processus pendant une capture, pendant un lot d'embeddings, pendant une purge : reprise sans perte ni corruption.
2. Base corrompue, clé perdue, espace de stockage saturé : détection, message, réinitialisation proposée.
3. Mise à jour de l'application : migrations, changement de modèle, conservation des réglages et du solde.

**Critères d'acceptation**

- [ ] E2E-18 vert ; tests d'interruption de transaction (aucune capture partielle).
- [ ] Stockage saturé → la capture se suspend avec un compteur de diagnostic, sans plantage.
- [ ] Test de mise à jour : base de la version précédente ouverte par la version courante.

---

### M10-07 — Modèles allégés et packs par langue (optionnel)

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M5-07 | `tools/model`, `engine:embedding` | [ADR-0010](../adr/0010-modele-francais-et-packs-de-langue.md) · risque R-06 |

**À faire**

1. Si le rapport de M5-07 montre que le modèle `fr` perd trop sur les contenus en anglais : fabriquer un
   modèle « français + anglais » en réduisant le vocabulaire du modèle multilingue aux jetons utiles à
   ces deux langues, et l'évaluer comme modèle embarqué (taille, qualité, latence).
2. Évaluer des packs par langue (anglais, espagnol, allemand, italien) fabriqués de la même façon, plus
   légers que le pack multilingue.
3. En cas d'adoption : ADR, nouvelles entrées du catalogue, mise à jour de l'outillage.

**Critères d'acceptation**

- [ ] Tableau comparatif : taille, Rappel@5 et MRR par type de requête et par langue, latence.
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

**Validation manuelle** — parcours P1 sur le téléphone de test s'il est sous Android 16 ou 17 ; sinon sur émulateur.
