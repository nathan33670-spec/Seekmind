# 02 — Cahier des charges (v1)

| | |
|---|---|
| **Version** | 1.1 — 12 novembre 2026 (décisions du porteur du projet intégrées, voir §9) |
| **Statut** | Référence pour les jalons M0 à M11 |
| **Documents liés** | [Vision](01-vision-produit.md) · [Architecture](03-architecture.md) · [Specs](specs/) · [Plan](plan/README.md) |

Ce document dit **ce que** le produit doit faire et à quel niveau de qualité. Le **comment** est dans
`docs/specs/`. Chaque exigence porte un identifiant stable, cité par les tickets du plan.

Vocabulaire : **DOIT** = obligatoire en v1 ; **DEVRAIT** = attendu sauf impossibilité justifiée dans la PR.

## 1. Objet

Concevoir et publier sur Google Play une application Android qui mémorise localement le texte affiché à
l'écran et permet de le retrouver par une recherche hybride (mots-clés et sémantique), avec retour vers
la source, dans le respect strict de cinq invariants : le contenu mémorisé ne quitte jamais l'appareil,
passivité, aucune image persistée, consentement préalable, chiffrement local.

L'application existe en deux variantes du même code ([ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md)) :
`ads`, publiée sur Google Play, dont l'accès réseau est réservé à la publicité ; et `offline`, sans
aucune permission réseau, maintenue en continu comme solution de repli.

## 2. Périmètre

### 2.1 Inclus dans la v1

- Capture du texte par l'arbre d'accessibilité, complétée par un OCR en mémoire vive.
- Filtres de confidentialité et contrôle utilisateur : familles d'applications choisies à l'installation,
  pause, exclusions, suppression, rétention, verrouillage.
- Indexation plein texte immédiate, indexation sémantique différée, déduplication.
- Recherche en langage naturel avec compréhension des dates et des applications.
- Modèle de recherche par le sens en français, embarqué ; pack multilingue téléchargeable à la demande.
- Retour à la source (URL des navigateurs, liens propres à certaines applications, ouverture de l'application).
- Journal chronologique des captures.
- Monétisation : crédits de recherche, publicité récompensée AdMob (variante `ads`), code d'activation offert.
- Variante `offline` sans réseau, compilée et testée à chaque changement.
- Interface en français et en anglais, thème clair et sombre.
- Site statique (politique de confidentialité, assistance, page de relais des liens d'activation) sur GitHub Pages.
- Dossier de publication Google Play.

### 2.2 Exclus de la v1

- **Actions contextuelles** (pastille flottante et actions depuis le détail) : reportées après la
  première publication, en v1.1. Leur spécification est prête ([specs/actions-intelligentes](specs/actions-intelligentes.md)).
- Récompense publicitaire par le navigateur : solution de repli documentée, non réalisée
  ([annexe](specs/annexe-recompense-navigateur.md)).
- Bannières, interstitiels et tout format publicitaire autre que l'annonce récompensée.
- Abonnement ou achat intégré Google Play, vente de codes, formule Pro, limite « 7 derniers jours » du brief initial.
- Recherche dans les PDF et pièces jointes ; export Notion/Obsidian ; sauvegarde chiffrée `.smind`.
- Capture des notifications, de l'audio, de la vidéo, des frappes clavier.
- Synchronisation entre appareils, compte utilisateur, version web ou iOS.
- Widgets, Wear OS, Android TV, optimisation tablette au-delà d'une mise en page adaptative de base.
- Résumés ou réponses générés par un grand modèle de langage.
- Packs de langue par langue (anglais seul, espagnol seul…) : le pack multilingue les couvre en v1.

## 3. Parcours de référence

**P1 — Première utilisation.** Installation → présentation → écran de divulgation → acceptation →
choix des familles d'applications à mémoriser → activation dans les réglages d'accessibilité → retour
dans l'application → proposition de verrouillage → écran de recherche avec le crédit de bienvenue.

**P2 — Retrouver une information.** L'utilisateur ouvre SeekMind (déverrouillage biométrique), tape
« resto italien pâtes truffe mardi soir », valide. Les résultats arrivent, le premier provient
d'Instagram mardi à 21 h 12. Un tap sur « Ouvrir Instagram » le ramène à l'application.

**P3 — Plus de crédits.** Compteur à zéro → feuille « Continuer à chercher » → « Regarder une publicité
(+5) » → à la première fois, fenêtre de consentement publicitaire → l'annonce s'affiche et se termine →
le compteur affiche 5.

**P4 — Activer.** L'utilisateur touche un lien d'activation reçu par message (ou colle son code dans
Réglages → Activation). L'application affiche « SeekMind activé — recherches illimitées, sans publicité ».

**P5 — Garder le contrôle.** Tuile de réglages rapides « Mémorisation » pour mettre en pause ; Réglages →
Applications pour désactiver une famille ou exclure une application ; Journal → suppression d'une
capture ou d'une période.

**P6 — Ajouter une langue.** Réglages → Langues de recherche → « Pack multilingue (118 Mo) » →
téléchargement par Google Play → « Utiliser ce pack » → l'index par le sens se reconstruit en
arrière-plan ; la recherche par mots-clés reste disponible pendant ce temps.

## 4. Exigences fonctionnelles

### 4.1 Accueil et consentement — `EF-ONB`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-ONB-01 | L'application DOIT présenter sa valeur en 3 écrans au plus avant toute demande d'autorisation. | Les écrans sont passables ; aucune redirection vers les réglages système avant EF-ONB-02. |
| EF-ONB-02 | Un écran de divulgation bloquant DOIT précéder toute redirection vers les réglages d'accessibilité. Il décrit les données lues, la finalité, la protection, et exige une action affirmative. | Texte conforme à [05 §4](05-conformite-play-store.md#4-écran-de-divulgation) ; boutons « J'accepte » et « Non merci » de même poids visuel ; le retour arrière vaut refus ; rien n'est pré-coché. |
| EF-ONB-03 | Après acceptation, l'application DOIT guider vers l'activation du service et détecter le résultat au retour. | Au retour avec le service actif, un écran de confirmation s'affiche sans action supplémentaire. |
| EF-ONB-04 | En cas de refus, l'application DOIT rester utilisable et offrir en permanence un moyen de rouvrir la divulgation. | Bandeau « Activer la mémorisation » visible sur l'écran d'accueil ; il rouvre l'écran EF-ONB-02. |
| EF-ONB-05 | Le consentement DOIT être horodaté et versionné. Une nouvelle version du texte suspend la capture jusqu'à nouvelle acceptation. | Test : incrément de la version → le service ignore les événements tant que l'utilisateur n'a pas accepté. |
| EF-ONB-06 | L'application DOIT proposer le verrouillage par biométrie ou code de l'appareil, activé par défaut si l'appareil dispose d'un verrouillage sécurisé. | Voir EF-PRIV-10. |
| EF-ONB-07 | Après la divulgation et **avant** l'activation du service, l'application DOIT faire choisir les familles d'applications à mémoriser. | Écran à quatre familles (EF-PRIV-12), toutes cochées par défaut ; le choix est enregistré avant la redirection vers les réglages système. |

### 4.2 Capture — `EF-CAP`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-CAP-01 | Le service DOIT lire la fenêtre active lorsque l'écran est stable : 1,5 s sans changement, ou 8 s au plus tard si l'écran change en continu. | Défilement rapide de 10 s : au plus 2 captures ; arrêt du défilement : 1 capture dans les 2 s. |
| EF-CAP-02 | Chaque capture DOIT enregistrer : texte visible, application, activité, titre de fenêtre, date, et l'URL pour les navigateurs pris en charge. | Vérifié sur l'application de test et sur Chrome. |
| EF-CAP-03 | Le service NE DOIT PAS capturer : SeekMind lui-même, l'écran d'accueil, l'interface système, le clavier, l'écran de verrouillage. | Aucune capture produite dans ces cinq situations. |
| EF-CAP-04 | L'utilisateur DOIT pouvoir suspendre et reprendre la capture depuis l'application et depuis une tuile de réglages rapides, pour 1 h, jusqu'au lendemain ou sans limite. | En pause, aucun événement n'est traité ; la reprise programmée est effective à ± 1 min. |
| EF-CAP-05 | L'application DOIT afficher l'état de la mémorisation (active, en pause, service désactivé, consentement manquant) et l'heure de la dernière capture. | Les quatre états sont distingués visuellement et textuellement. |
| EF-CAP-06 | Le service NE DOIT exécuter aucune action sur l'interface d'une autre application. | Garde-fou CI INV-02 vert. |
| EF-CAP-07 | Un écran quasi identique (similarité ≥ 85 %) à une capture récente de la même application NE DOIT PAS créer de doublon ; la date de dernière vue de la capture existante est mise à jour. | Même écran affiché 5 fois : 1 seule capture, `seen_count = 5`. |

### 4.3 OCR — `EF-OCR`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-OCR-01 | Lorsque l'arbre d'interface contient peu de texte alors qu'un contenu visuel occupe l'écran, le service DOIT reconnaître le texte de l'image de l'écran. | Écran « image avec texte » de l'application de test : le texte de l'image est retrouvé par la recherche. |
| EF-OCR-02 | L'image NE DOIT jamais être écrite sur le stockage ; elle est libérée dès la reconnaissance terminée. | Aucun fichier créé dans les répertoires de l'application pendant le test ; garde-fou INV-03 vert. |
| EF-OCR-03 | L'OCR DOIT être suspendu si l'état thermique atteint `MODERATE`, en mode économie d'énergie, ou sous 15 % de batterie hors charge ; il DOIT respecter un budget de fréquence. | Tests avec états simulés ; pas plus de 6 OCR par minute. |
| EF-OCR-04 | Le texte issu de l'OCR DOIT être fusionné sans doublon avec celui de l'arbre et son origine conservée. | Le résultat de recherche porte l'indication « Texte lu dans l'image ». |

### 4.4 Confidentialité et contrôle — `EF-PRIV`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-PRIV-01 | Un écran contenant un champ de mot de passe NE DOIT PAS être indexé. | Écran « mot de passe » de l'application de test : aucune capture. |
| EF-PRIV-02 | Une fenêtre sécurisée (`FLAG_SECURE`) NE DOIT PAS être indexée. | Écran « sécurisé » de l'application de test : aucune capture (Android 14+ : garanti ; Android 11–13 : voir [ADR-0008](adr/0008-detection-des-fenetres-securisees.md)). |
| EF-PRIV-03 | La navigation privée des navigateurs pris en charge NE DOIT PAS être indexée. | Onglet privé de Chrome : aucune capture. |
| EF-PRIV-04 | Les applications sensibles (banque, paiement, gestionnaires de mots de passe, authentification, santé, administration) et les jeux DOIVENT être exclus par défaut. La liste couvre la France, la Belgique, la Suisse, le Luxembourg, l'Espagne, l'Allemagne et l'Italie. | Liste intégrée livrée avec l'application ; test sur des noms de paquets représentatifs de chaque pays. |
| EF-PRIV-05 | L'utilisateur DOIT pouvoir exclure ou réintégrer chaque application. Les gestionnaires de mots de passe et applications d'authentification ne peuvent pas être réintégrés. | Réglages → Applications ; l'effet est immédiat. |
| EF-PRIV-06 | Le contenu des champs de saisie NE DOIT PAS être indexé. | Texte tapé dans l'écran « saisie » de l'application de test : absent de l'index. |
| EF-PRIV-07 | Les numéros de carte bancaire et les IBAN valides DOIVENT être masqués avant stockage. | Tests unitaires sur numéros valides et invalides ; les numéros de suivi ne sont pas masqués. |
| EF-PRIV-08 | L'utilisateur DOIT pouvoir supprimer une capture, les captures d'une application, celles d'une période, ou la totalité des données. | Après suppression, la donnée n'apparaît plus dans la recherche ni dans le journal ; « Tout effacer » détruit la base et sa clé. |
| EF-PRIV-09 | Les captures DOIVENT être purgées automatiquement au-delà de la durée de rétention (30, 90, 180, 365 jours ou illimitée ; 90 par défaut) et au-delà d'un budget de stockage (500 Mo par défaut). | Purge quotidienne vérifiée par test avec horloge simulée. |
| EF-PRIV-10 | L'ouverture de l'application DOIT exiger par défaut la biométrie ou le code de l'appareil, avec un délai de grâce de 5 min ; l'utilisateur peut le désactiver. | Réglage actif : l'interface reste masquée tant que l'authentification n'a pas réussi. |
| EF-PRIV-11 | Les écrans de SeekMind DOIVENT être protégés contre les captures d'écran et masqués dans les applications récentes. | `FLAG_SECURE` posé sur toutes les activités. |
| EF-PRIV-12 | La mémorisation DOIT respecter les familles d'applications choisies par l'utilisateur : navigateurs ; messageries et e-mails ; réseaux sociaux et vidéo ; autres applications. Le choix est modifiable dans les réglages. | Une famille désactivée ne produit aucune capture ; une règle par application prime sur la famille. |

### 4.5 Indexation — `EF-IDX`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-IDX-01 | Une capture DOIT être trouvable par mot-clé moins d'une seconde après son enregistrement. | Test de bout en bout. |
| EF-IDX-02 | Les vecteurs sémantiques DOIVENT être calculés en arrière-plan, par lots, sous 15 min lorsque la batterie n'est pas faible, et en priorité pendant la charge. | File d'attente vidée par le traitement périodique ; aucun calcul si la batterie est faible ou l'appareil chaud. |
| EF-IDX-03 | Capture, indexation et recherche DOIVENT fonctionner en mode avion. | Jeu de tests exécuté sans réseau. |

### 4.6 Recherche — `EF-SRCH`

Les seuils de qualité s'entendent **en français avec le modèle embarqué**, et dans toutes les langues du
jeu d'évaluation avec le pack multilingue.

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-SRCH-01 | L'utilisateur DOIT pouvoir chercher en texte libre ; les résultats sont classés par pertinence hybride. | Jeu d'évaluation : Rappel@5 ≥ 0,85 et MRR ≥ 0,70 (seuils initiaux, recalibrés au ticket M5-07). |
| EF-SRCH-02 | Les termes exacts (codes, numéros, noms propres) DOIVENT être retrouvés, quelle que soit la langue. | Requêtes « code » du jeu d'évaluation : Rappel@1 ≥ 0,90. |
| EF-SRCH-03 | Une formulation différente du texte d'origine DOIT permettre de le retrouver. | Requêtes « paraphrase » du jeu d'évaluation : Rappel@5 ≥ 0,75. |
| EF-SRCH-04 | Les expressions temporelles DOIVENT être comprises : aujourd'hui, hier, avant-hier, jours de la semaine, moments de la journée, « cette semaine », « la semaine dernière », « ce mois-ci », « le mois dernier », « il y a N jours/semaines/mois », dates explicites — et leurs équivalents anglais. | Tests unitaires sur la table de [specs/recherche §3](specs/recherche.md). |
| EF-SRCH-05 | Le nom d'une application dans la requête (« sur Instagram ») DOIT filtrer les résultats ; les mêmes filtres existent sous forme de pastilles. | Tests unitaires et d'interface. |
| EF-SRCH-06 | Chaque résultat DOIT afficher l'application, la date et l'heure, le titre ou l'hôte, un extrait avec les termes surlignés, et le bouton de retour à la source. | Revue d'interface. |
| EF-SRCH-07 | Sans résultat avec les filtres, l'application DOIT proposer les meilleurs résultats sans filtre en l'indiquant. | Message « Rien trouvé mardi soir — voici d'autres dates ». |
| EF-SRCH-08 | Les résultats par mots-clés DOIVENT s'afficher sans attendre le calcul sémantique, puis être affinés. | Premier affichage ≤ 100 ms (ENF-PERF-03). |
| EF-SRCH-09 | L'historique des recherches est local, supprimable et désactivable. | Réglages → Recherche. |

### 4.7 Langues de recherche — `EF-LANG`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-LANG-01 | L'application DOIT embarquer un modèle de recherche par le sens pour le français. La recherche par mots-clés fonctionne dans toutes les langues, sans téléchargement. | Seuils de EF-SRCH-01 à 03 tenus en français avec le modèle embarqué. |
| EF-LANG-02 | L'utilisateur DOIT pouvoir ajouter un pack de langue. En v1 : un pack multilingue, téléchargé par Google Play à sa demande. Rien n'est téléchargé sans son action. | Réglages → Langues de recherche ; proposition à la fin de l'accueil si le téléphone n'est pas en français. |
| EF-LANG-03 | Changer de modèle DOIT reconstruire l'index par le sens en arrière-plan, sans interrompre la recherche par mots-clés, en affichant l'avancement. | Test : après changement, les vecteurs sont recalculés ; la recherche lexicale répond pendant ce temps. |
| EF-LANG-04 | Un pack installé DOIT pouvoir être supprimé ; le modèle français redevient alors actif. | Réglages → Langues de recherche. |

### 4.8 Retour à la source — `EF-SRC`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-SRC-01 | Pour une page vue dans un navigateur pris en charge, le résultat DOIT rouvrir l'URL dans ce navigateur. | Test sur Chrome avec une URL connue. |
| EF-SRC-02 | Pour les autres applications, le résultat DOIT offrir le meilleur retour disponible : lien propre à l'application, recherche dans l'application, ou ouverture de l'application. | Chaîne de résolution décrite dans [specs/retour-source](specs/retour-source.md). |
| EF-SRC-03 | Le libellé du bouton DOIT refléter la fidélité du retour : « Ouvrir la page », « Rechercher dans … », « Ouvrir … ». | Jamais de promesse de retour exact quand il ne l'est pas. |
| EF-SRC-04 | Si l'application source est désinstallée, le bouton est désactivé avec une explication. | Test unitaire. |
| EF-SRC-05 | Les liens présents dans le texte capturé DEVRAIENT être proposés dans le détail de la capture. | Détail d'une capture contenant une URL. |

### 4.9 Journal — `EF-JRN`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-JRN-01 | Liste chronologique des captures regroupées par jour, filtrable par application. | Défilement fluide sur 100 000 captures (pagination). |
| EF-JRN-02 | Le détail d'une capture DOIT afficher le texte complet et proposer : ouvrir la source, copier, partager, supprimer. | Revue d'interface. |
| EF-JRN-03 | Le journal n'est jamais soumis aux crédits. | Accessible avec un solde nul. |

### 4.10 Actions contextuelles — `EF-ACT` — **reportées en v1.1**

Ces exigences ne font pas partie de la première publication (décision D-05). Elles sont conservées pour
la v1.1, où elles seront gratuites et illimitées.

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-ACT-01 | Détection sur l'appareil de : date/heure, adresse postale, téléphone, e-mail, URL, numéro de suivi de colis. | Tests unitaires par type d'entité. |
| EF-ACT-02 | Une pastille flottante propose **une** action sur l'écran courant ; elle disparaît après 6 s, ne prend jamais le focus et n'apparaît pas plus d'une fois par écran ni plus d'une fois par minute. | Test de bout en bout sur l'application de test. |
| EF-ACT-03 | Un tap ouvre l'application cible pré-remplie (agenda, cartes, téléphone, e-mail, navigateur, suivi). | Intents vérifiés par test. |
| EF-ACT-04 | Les mêmes actions sont disponibles depuis le détail d'une capture. | Revue d'interface. |
| EF-ACT-05 | La pastille est désactivable ; désactivée, aucune fenêtre n'est superposée. | Réglages → Actions. |
| EF-ACT-06 | Aucune action n'est exécutée sans tap de l'utilisateur. | INV-02. |

### 4.11 Monétisation — `EF-MON`

| ID | Exigence | Critère d'acceptation |
|---|---|---|
| EF-MON-01 | En formule gratuite, chaque recherche validée consomme 1 crédit. 10 crédits sont offerts à l'installation. | Le compteur diminue à l'affichage des résultats. |
| EF-MON-02 | Dans la variante `ads`, une annonce récompensée regardée jusqu'au bout crédite 5 recherches. C'est le **seul** format publicitaire, et il n'apparaît qu'à la demande de l'utilisateur. | Annonce de test : +5 ; annonce fermée avant la fin : aucun crédit. |
| EF-MON-03 | Le solde est visible sur l'écran de recherche ; à zéro, une feuille propose la publicité ou le code. | Revue d'interface. |
| EF-MON-04 | Une recherche sans résultat n'est pas débitée ; la même requête relancée sous 10 min est gratuite. | Tests unitaires du registre de crédits. |
| EF-MON-05 | Un code d'activation valide, collé ou reçu par lien, supprime la publicité et rend les recherches illimitées. La vérification est hors ligne. | Test avec un code signé par la clé de test. |
| EF-MON-06 | Un code invalide ou révoqué produit un message d'erreur explicite et ne modifie rien. | Tests unitaires. |
| EF-MON-07 | Capture, journal, suppression et réglages ne sont jamais soumis aux crédits. | Accessibles avec un solde nul. |
| EF-MON-08 | Au plus 20 récompenses par jour ; solde plafonné à 100. | Tests unitaires. |
| EF-MON-09 | Le consentement publicitaire (fenêtre de Google, personnalisation selon le choix de l'utilisateur) DOIT être recueilli avant la première annonce, **séparément** de la divulgation d'accessibilité, et rester modifiable. | Fenêtre affichée à la première demande de publicité en Europe ; entrée « Confidentialité des publicités » dans les réglages. |
| EF-MON-10 | L'application NE DOIT rien émettre sur le réseau tant que l'utilisateur n'a pas ouvert la feuille de crédits ou demandé une publicité, et NE DOIT transmettre au SDK publicitaire aucune donnée issue de la mémorisation. | Test instrumenté de silence réseau ; interface du fournisseur sans paramètre de données ; garde-fous. |
| EF-MON-11 | Si aucune annonce n'est disponible ou sans connexion, 1 recherche de courtoisie est offerte, 3 fois par jour au plus. | Tests unitaires. |
| EF-MON-12 | La variante `offline` DOIT se construire sans la permission `INTERNET` ni le module publicitaire, et offrir 5 recherches gratuites par jour à la place de la publicité. | `:app:verifyOfflineReleaseManifest` vert ; tests du registre. |

### 4.12 Réglages — `EF-SET`

L'écran Réglages DOIT regrouper : état et pause de la mémorisation ; applications (familles et
exclusions) ; rétention et stockage utilisé ; suppression des données ; verrouillage ; langues de
recherche ; recherche (historique) ; activation, solde et confidentialité des publicités ; diagnostic
local (compteurs, sans contenu) ; relecture de la divulgation ; politique de confidentialité ; licences
tierces ; version.

## 5. Exigences non fonctionnelles

Appareils de référence : **bas** — Android 11, 4 Go de RAM, processeur d'entrée de gamme ; **moyen** —
Pixel 7a ou équivalent ; **haut** — Pixel 9 ou équivalent. Sauf mention contraire, les cibles
s'entendent sur l'appareil moyen. Protocoles de mesure : [06 §6](06-qualite-tests-ci.md#6-mesures-de-performance).

| ID | Exigence | Cible |
|---|---|---|
| ENF-PERF-01 | Travail sur le thread principal par événement d'accessibilité | ≤ 2 ms (p99) |
| ENF-PERF-02 | Lecture de l'arbre (hors thread principal) | ≤ 150 ms (p95) ; abandon au-delà de 400 ms |
| ENF-PERF-03 | Premier affichage des résultats par mots-clés, base de 100 000 captures | ≤ 100 ms (p95) |
| ENF-PERF-04 | Recherche hybride complète, modèle chargé, 100 000 captures | ≤ 300 ms (p95) ; hors embedding de la requête ≤ 30 ms |
| ENF-PERF-05 | Insertion d'une capture (transaction complète) | ≤ 15 ms (p95) |
| ENF-PERF-06 | Démarrage à froid jusqu'au champ de recherche utilisable | ≤ 1,5 s |
| ENF-BAT-01 | Consommation attribuable sur le scénario de référence (4 h d'écran) | ≤ 3 % de batterie par jour |
| ENF-BAT-02 | Aucun travail écran éteint, hors lots d'embeddings planifiés | Vérifié par `dumpsys batterystats` |
| ENF-MEM-01 | Mémoire du processus, service seul | ≤ 80 Mo PSS |
| ENF-MEM-02 | Pic mémoire pendant le calcul des embeddings | ≤ 400 Mo PSS |
| ENF-STO-01 | Croissance du stockage en usage type | ≤ 1,5 Mo par jour |
| ENF-STO-02 | Taille de téléchargement de l'application (modèle français inclus) | ≤ 100 Mo |
| ENF-REL-01 | Le service reprend seul après l'arrêt du processus ; aucune perte au-delà de la capture en cours | Test de mort de processus |
| ENF-REL-02 | Base corrompue ou clé perdue : l'application le détecte, l'explique et propose la réinitialisation | Test |
| ENF-SEC-01 | Exigences de [07 — Sécurité](07-securite-vie-privee.md) | Liste de contrôle M10-05 |
| ENF-COMP-01 | Conformité aux règles Google Play et AdMob en vigueur ; `targetSdk` 36 ; bibliothèques natives compatibles pages de 16 Ko | [05](05-conformite-play-store.md) |
| ENF-A11Y-01 | Interface utilisable avec TalkBack, taille de police 200 %, contraste AA | Revue M6-08 |
| ENF-I18N-01 | Français et anglais complets ; langue du système | Lint « chaînes manquantes » bloquant |
| ENF-QUAL-01 | Couverture des tests JVM sur les modules `engine` | ≥ 80 % des lignes |
| ENF-OFF-01 | Tout fonctionne hors ligne, sauf l'affichage d'une publicité et le téléchargement d'un pack de langue | Tests en mode avion |

## 6. Contraintes

- **Plateforme :** Android 11 (API 30) minimum — [ADR-0003](adr/0003-min-sdk-30.md). Cible API 36.
- **Marché de lancement :** Europe francophone et pays voisins ; interface en français et en anglais.
- **Distribution :** Google Play, compte développeur personnel existant. Le dossier de conformité est un
  livrable du projet (M11).
- **Réseau :** variante `ads` — permission `INTERNET` réservée au module publicitaire, silence réseau
  hors demande de publicité ; variante `offline` — aucune permission `INTERNET`
  ([ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md), [ADR-0001](adr/0001-zero-permission-internet.md)).
- **Réalisation :** développement par agents IA, relecture de chaque PR par un second agent. Les
  spécifications doivent donc être explicites, les critères vérifiables automatiquement, et les risques
  techniques levés tôt par des prototypes (M0).
- **Moyens de test :** un seul téléphone réel, sous Android 14 ou plus récent (risque R-14).
- **Langue :** documentation en français ; code en anglais.

## 7. Recette de la v1

La v1 est recevable lorsque, sur l'appareil de test et sur les émulateurs API 30 et 34 :

1. le parcours P1 se déroule en moins d'une minute ;
2. vingt écrans de dix applications courantes sont parcourus, puis retrouvés par dix requêtes en
   français (cinq exactes, cinq floues) avec le bon résultat dans les trois premiers ;
3. aucun des écrans sensibles du protocole (mot de passe, banque, navigation privée, fenêtre sécurisée),
   ni aucune application d'une famille désactivée, n'apparaît dans le journal ;
4. les parcours P3 (annonce de test) et P4 fonctionnent ; le test de silence réseau est vert ;
5. le parcours P6 fonctionne sur la piste de test interne de Google Play ;
6. la variante `offline` passe les mêmes points 1 à 3 et sa vérification de manifeste ;
7. toutes les cibles ENF sont mesurées et tenues, ou leur écart est documenté et accepté ;
8. le dossier Google Play (M11) est complet et la vidéo de divulgation tournée.

## 8. Risques

| ID | Risque | Probabilité | Impact | Parade |
|---|---|---|---|---|
| R-01 | Refus de Google Play : API d'accessibilité **et** SDK publicitaire dans la même application | Moyenne à élevée | Bloquant | Divulgation conforme, déclaration exacte, cantonnement et silence réseau démontrables, vidéo ; **variante `offline` prête à soumettre** ([ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md)) ; distribution alternative ([05 §8](05-conformite-play-store.md#8-plan-de-repli)) |
| R-02 | Compte AdMob : délai d'approbation, limitation de diffusion, suspension pour non-respect des règles des annonces récompensées ou du consentement | Moyenne | Revenus | Format récompensé standard, fenêtre de consentement de Google, `app-ads.txt`, identifiants de test en développement ; crédit de courtoisie si aucune annonce |
| R-03 | Vente future de codes d'activation hors Google Play contraire à la politique de paiement | Faible en v1 (codes offerts) | Retrait | Aucune vente en v1 ; achat Google Play en v2 ([ADR-0007](adr/0007-codes-d-activation-signes.md)) |
| R-04 | Arrêt du service par les surcouches constructeur (économie de batterie agressive) | Élevée | Qualité | Détection et bandeau de dépannage, guide par constructeur |
| R-05 | Restrictions futures d'Android sur l'accessibilité pour les applications non « outils » (déjà le cas avec la Protection avancée d'Android 17) | Moyenne | Bloquant à terme | Détection et message ; veille ; capture isolée dans un module |
| R-06 | Modèle français peu diffusé ; qualité incertaine sur les contenus en anglais ou dans d'autres langues | Moyenne | Qualité | Mesure dès M0-09 et M5-07 ; pack multilingue ; repli sur un modèle au vocabulaire réduit par nos soins (M10-07) |
| R-07 | Consommation ou échauffement sur appareils bas de gamme | Moyenne | Qualité | Budgets, bride thermique, lots pendant la charge, mesures M10-01 |
| R-08 | Applications dont l'arbre d'accessibilité est vide ou pauvre (jeux, Flutter, vues web) | Élevée | Rappel | OCR de repli |
| R-09 | Indexation d'une donnée sensible malgré les filtres | Faible | Confiance | Défense en profondeur, données locales chiffrées, verrouillage, suppression facile |
| R-10 | Retour à la source imprécis hors navigateurs | Certaine | Attente | Libellés honnêtes (EF-SRC-03), résolveurs par application |
| R-11 | ML Kit : télémétrie intégrée à neutraliser, stabilité sans elle | Faible | Technique | Prototype M0-07 ; moteur OCR de repli sur le runtime ONNX |
| R-12 | Écart entre le tokenizer Kotlin et la référence | Moyenne | Qualité | Jeux de référence de 1 000 phrases par modèle, seuil de parité, repli sur une bibliothèque native |
| R-13 | Cadre juridique (RGPD et consentement publicitaire, politique de confidentialité, conditions d'utilisation des applications tierces) | Moyenne | Juridique | Fenêtre de consentement certifiée de Google, politique de confidentialité, avis d'un juriste avant publication |
| R-14 | Un seul téléphone de test, sous Android 14+ : le chemin Android 11 à 13 (détection heuristique des fenêtres sécurisées) et les surcouches constructeur ne sont validés que sur émulateur | Élevée | Qualité | Émulateur API 30 en CI ; recruter pour le test fermé des testeurs équipés d'Android 11 à 13 et de Samsung ou Xiaomi, avec un protocole dédié (M11-05) |
| R-15 | Le SDK publicitaire s'exécute dans le processus du service qui lit l'écran | Faible | Confiance | Cantonnement, interface sans données, silence réseau vérifié ; isolement dans un processus dédié étudié en M10-02 |
| R-16 | Téléchargement du pack de langue impossible à tester hors Google Play | Certaine | Qualité | Import de fichier pour les tests automatisés ; validation sur la piste de test interne |

## 9. Décisions

Décisions prises par le porteur du projet le 12 novembre 2026, et valeurs par défaut restées en l'état.

| ID | Sujet | Décision | Origine |
|---|---|---|---|
| **D-01** | Publicité | **SDK AdMob dans l'application**, annonces récompensées uniquement, personnalisation selon le consentement. La solution sans réseau est conservée sous la forme d'une **variante `offline` compilée et testée en continu** ; la récompense par le navigateur reste documentée en annexe. — [ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md) | Porteur |
| **D-02** | Codes d'activation | Jetons signés vérifiés hors ligne, **offerts** au lancement ; activation par lien ou collage. — [ADR-0007](adr/0007-codes-d-activation-signes.md) | Porteur |
| D-03 | Nom et identifiant | « SeekMind » ; identifiant d'application `fr.corsicajack.seekmind` ; schéma de liens `seekmind://` ; site sur GitHub Pages pour commencer. | Porteur |
| D-04 | Rétention | 90 jours par défaut, réglable jusqu'à illimitée ; pas de limite « 7 derniers jours ». | Porteur |
| D-05 | Actions contextuelles | **Après la première publication** (v1.1). | Porteur |
| D-06 | Crédits | 10 de bienvenue ; 1 par recherche validée ; +5 par annonce ; 20 annonces par jour au plus ; recherche sans résultat non débitée ; 1 de courtoisie sans annonce disponible. | Par défaut — modifiable (`MonetizationConfig`) |
| D-07 | Modèle de recherche par le sens | **Français embarqué** (~36 Mo) ; **packs de langue téléchargeables**, d'abord un pack multilingue. — [ADR-0010](adr/0010-modele-francais-et-packs-de-langue.md) | Porteur |
| D-08 | Android minimum | Android 11 (API 30). | Par défaut |
| D-09 | Verrouillage | Biométrie ou code de l'appareil, **activé par défaut**. | Porteur |
| D-10 | Déclaration Google Play | Application **non** « outil d'accessibilité » : divulgation, déclaration et vidéo obligatoires. — [05 §2](05-conformite-play-store.md#2-quel-parcours-de-déclaration) | Imposé par la politique de Google |
| D-11 | Licence du dépôt | Tous droits réservés (pas de licence open source). | Par défaut |
| D-12 | Marché de lancement | Europe francophone et pays voisins ; interface FR + EN ; liste d'applications sensibles étendue à sept pays. | Porteur |
| D-13 | Messageries et autres familles | **Choix des familles d'applications à l'installation.** Les quatre familles sont cochées par défaut ; l'utilisateur décoche ce qu'il ne veut pas. | Porteur ; état initial des cases par défaut — modifiable |
| D-14 | Fusion des pull requests | CI verte **et** revue par un second agent ; validation du porteur sur les tickets sensibles. — [plan §1.4](plan/README.md#14-revue-et-fusion) | Porteur |
| D-15 | Compte Google Play | Compte personnel existant ; test fermé de 12 testeurs pendant 14 jours si le compte est postérieur à novembre 2023. | Porteur |
| D-16 | Appareils de test | Un téléphone sous Android 14 ou plus récent. | Porteur |

## 10. Glossaire

| Terme | Définition |
|---|---|
| **Capture** | Un écran indexé : texte normalisé et métadonnées. Jamais une image. |
| **Arbre d'accessibilité** | Représentation structurée de l'interface exposée par Android aux services d'accessibilité. |
| **Fenêtre sécurisée** | Fenêtre portant `FLAG_SECURE` : son contenu est exclu des captures d'écran du système. |
| **Porte de confidentialité** | Composant (`PrivacyGate`) qui décide si une capture peut être stockée. |
| **Famille d'applications** | Regroupement proposé à l'installation : navigateurs, messageries et e-mails, réseaux sociaux et vidéo, autres. |
| **Variante** | Version de l'application produite par le même code : `ads` (avec publicité) ou `offline` (sans réseau). |
| **Fragment** (*chunk*) | Portion de texte d'une capture à laquelle correspond un vecteur. |
| **Embedding** | Vecteur numérique représentant le sens d'un texte. |
| **Pack de langue** | Modèle de recherche par le sens téléchargé à la demande, en complément du modèle français embarqué. |
| **FTS5** | Module de recherche plein texte de SQLite. |
| **RRF** | *Reciprocal Rank Fusion* : fusion de plusieurs classements. |
| **SimHash** | Empreinte de 64 bits telle que deux textes proches ont des empreintes proches. |
| **Crédit** | Unité donnant droit à une recherche en formule gratuite. |
| **Annonce récompensée** | Publicité que l'utilisateur choisit de regarder en échange de crédits. |
| **Jeton** | Message signé (JWS ES256) prouvant une activation. |
| **Divulgation** | Écran de consentement exigé par Google Play avant l'activation de l'accessibilité. |
