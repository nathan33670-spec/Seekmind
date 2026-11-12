# 07 — Sécurité et vie privée

| | |
|---|---|
| **Version** | 1.1 — 12 novembre 2026 (publicité AdMob, variante de repli) |
| **Documents liés** | [specs/confidentialite](specs/confidentialite.md) (règles de capture) · [specs/stockage](specs/stockage.md) · [ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md) · [ADR-0001](adr/0001-zero-permission-internet.md) |

## 1. Objectifs

1. Le contenu capturé ne quitte jamais l'appareil par le fait de l'application.
2. Le contenu le plus sensible n'entre jamais dans l'index.
3. Ce qui est dans l'index est illisible sans l'appareil déverrouillé.
4. L'utilisateur voit ce qui est mémorisé et peut l'effacer.
5. L'application ne peut pas servir d'outil de surveillance discret.
6. La publicité ne voit rien de ce qui est mémorisé, et n'existe que lorsque l'utilisateur la demande.

## 2. Actifs et adversaires

| Actif | Sensibilité |
|---|---|
| Texte capturé, titres, URL, vecteurs, historique des recherches | Très élevée : messages, navigation, vie privée |
| Clé de la base | Très élevée |
| État de monétisation (solde, licence) | Faible : valeur commerciale seulement |
| Clé privée de signature des licences (hors appareil) | Élevée pour l'éditeur |

| Adversaire | Capacité | Dans le périmètre |
|---|---|---|
| Attaquant distant | Réseau | Oui |
| **SDK publicitaire embarqué** (variante `ads`) | Code tiers exécuté dans le processus de l'application, avec accès au réseau | Oui — voir T15 |
| Application malveillante sur l'appareil | Intents, liens, superposition | Oui |
| Proche disposant du téléphone déverrouillé quelques minutes | Interface | Oui |
| Voleur d'un téléphone verrouillé | Stockage chiffré par le système | Oui |
| Éditeur de SeekMind | Mises à jour | Oui |
| Attaquant avec accès root ou outillage d'investigation sur un appareil déverrouillé | Tout | **Non** — hors d'atteinte d'une application ; documenté |

## 3. Menaces et parades

| # | Menace | Parade | Réf. |
|---|---|---|---|
| T1 | Exfiltration du contenu par le code de l'application ou une dépendance | Variante `offline` : aucune permission `INTERNET`. Variante `ads` : API réseau interdites hors du module publicitaire ; liste blanche de dépendances par variante ; silence réseau mesuré par un test | INV-01, INV-11, EF-MON-10 |
| T2 | Fuite par un canal détourné : URL ouverte dans le navigateur, paramètres d'une requête d'annonce | Les seules URL ouvertes par l'application sont fixes (politique de confidentialité) ou choisies par l'utilisateur (retour à la source) ; les requêtes d'annonce sont nues : ni mot-clé, ni URL de contenu, ni ciblage personnalisé (motifs interdits par le garde-fou) | specs/monetisation §3.3 |
| T3 | Fuite par les logs, les rapports de plantage, la sauvegarde système | Logs sans contenu ; aucun rapport distant ; sauvegarde et transfert d'appareil désactivés ; télémétrie de ML Kit neutralisée | INV-07, INV-08, INV-09 |
| T4 | Indexation de secrets (mots de passe, banque, navigation privée) | Porte de confidentialité à plusieurs couches ; masquage des cartes et IBAN | specs/confidentialite |
| T5 | Image de l'écran laissée sur le stockage | Traitement en mémoire, libération dans `finally`, garde-fou et test | INV-03 |
| T6 | Lecture de la base par une autre application | Stockage privé de l'application ; base chiffrée ; aucun `ContentProvider` exporté | §4 |
| T7 | Lecture de la base sur un téléphone volé et verrouillé | Chiffrement SQLCipher, clé enveloppée par le Keystore matériel, en plus du chiffrement du système | §4 |
| T8 | Proche curieux ouvrant l'application | Verrouillage par biométrie ou code de l'appareil, activé par défaut ; `FLAG_SECURE` ; aperçu masqué dans les applications récentes | EF-PRIV-10, 11 |
| T9 | Installation à l'insu du propriétaire pour l'espionner | Icône et nom non masquables, service visible dans les réglages d'accessibilité, aucun accès à distance, tuile de pause, consentement à l'écran. L'application ne propose aucun mode discret. | §6 |
| T10 | Lien `seekmind://` forgé par une application ou une page web | Jeton signé ES256 ; activation soumise à confirmation ; entrées bornées et validées | specs/monetisation §5 |
| T11 | Fraude aux crédits (modification locale, détournement du rappel de récompense) | État dans la base chiffrée ; plafonds quotidiens ; risque résiduel accepté : l'enjeu est commercial, pas de sécurité | R-02 |
| T13 | Dépendance compromise | Versions figées, liste blanche (M0-04), vérification des empreintes par Gradle (`verification-metadata.xml`, M10-05), mises à jour par PR dédiées | INV-11 |
| T14 | Perte de la clé (réinitialisation du Keystore) | Détection à l'ouverture, message clair, réinitialisation proposée ; aucune tentative de récupération | ENF-REL-02 |
| T15 | Le SDK publicitaire accède aux données de l'application : il partage son processus et son identité système | **Cloisonnement par construction** : le module publicitaire ne dépend ni de la base ni des autres modules, et son interface ne transporte aucune donnée. **Limitation dans le temps** : le SDK n'est initialisé qu'à la demande d'une publicité. **Mesure** : test de silence réseau. **Repli** : variante `offline`. Le risque résiduel — un SDK qui lirait de lui-même les fichiers de l'application — n'est pas éliminé par l'architecture : la base reste chiffrée par une clé que seul `core:database` manipule, et l'isolement du SDK dans un processus dédié est étudié en M10-02. | ADR-0009, R-15 |
| T16 | Pack de langue altéré | Livraison par Google Play ; empreintes vérifiées contre `model.lock.json` avant activation, y compris pour un import de fichier | specs/embeddings §5.2 |

## 4. Chiffrement

| Élément | Choix |
|---|---|
| Moteur | SQLCipher 4 (AES-256, intégrité par page HMAC-SHA512), réglages par défaut |
| Clé de base | 32 octets tirés de `SecureRandom` au premier lancement, fournie en clé brute (`x'…'`, sans dérivation) |
| Protection de la clé | Enveloppée en AES-256-GCM par une clé non exportable du Keystore Android (alias `seekmind.db.kek.v1`), StrongBox si disponible, repli sur le TEE |
| Stockage de la clé enveloppée | `noBackupFilesDir/db.key` : version, IV, texte chiffré |
| Authentification utilisateur sur la clé | **Non.** Le service écrit dès que l'écran s'allume et le lot d'embeddings tourne téléphone verrouillé en charge : lier la clé au déverrouillage casserait ces deux usages. Le verrouillage de l'application est une barrière d'interface, pas un chiffrement supplémentaire. |
| Effacement | « Tout effacer » ferme la base, supprime les fichiers, la clé enveloppée et l'alias du Keystore |
| `PRAGMA secure_delete` | `FAST` ; purge suivie d'un compactage |

## 5. Cycle de vie des données

| Donnée | Emplacement | Chiffrée | Durée de vie |
|---|---|---|---|
| Image de l'écran | Mémoire vive | — | Le temps de l'OCR (moins d'une seconde) |
| Texte capturé, métadonnées, vecteurs | Base SQLCipher | Oui | Rétention choisie (90 jours par défaut), budget de stockage |
| Historique des recherches | Base SQLCipher | Oui | 90 jours ou suppression manuelle ; désactivable |
| Solde, licence | Base SQLCipher | Oui | Vie de l'installation |
| Compteurs de diagnostic | Base SQLCipher | Oui | 30 jours |
| Réglages, consentement, familles d'applications | DataStore | Non (aucun contenu) | Vie de l'installation |
| Clé enveloppée | `noBackupFilesDir` | Par le Keystore | Vie de l'installation |
| Modèles de recherche | Assets de l'application ; stockage privé pour un pack | Non (données publiques) | Jusqu'à suppression du pack |
| Données propres au SDK publicitaire (variante `ads`) : choix de consentement, cache | Répertoires du SDK dans le stockage privé | Géré par le SDK | Géré par le SDK |

**Ce qui sort de l'appareil** (variante `ads`, uniquement lors d'une demande de publicité) : les données
que le SDK publicitaire de Google transmet de lui-même — identifiant publicitaire, données techniques de
l'appareil et de l'application, adresse IP. Jamais une donnée mémorisée. Variante `offline` : rien.

La désinstallation supprime tout. Rien n'est sauvegardé ni transféré vers un nouvel appareil en v1.

## 6. Surface exposée

| Point d'entrée | Contrôle |
|---|---|
| `MainActivity` (lanceur) | Verrouillage de l'application ; aucun paramètre d'Intent interprété |
| `LinkActivity` (`seekmind://activate`) | Seul cet hôte ; paramètre `t` de 2 048 caractères au plus, alphabet base64url et points ; vérification cryptographique et confirmation avant tout effet ; pas d'ouverture d'URL à partir du lien |
| Service d'accessibilité | Lié uniquement par le système (`BIND_ACCESSIBILITY_SERVICE`) |
| Tuile de réglages rapides | Liée uniquement par le système ; n'expose que pause et reprise |
| Composants du SDK publicitaire (variante `ads`) | Non exportés ; lancés uniquement par `engine:ads` |
| Presse-papiers | Copie à l'initiative de l'utilisateur, marquée sensible (`EXTRA_IS_SENSITIVE`) |
| Partage | Feuille de partage du système, à l'initiative de l'utilisateur |
| Import d'un pack de langue | Fichier choisi par l'utilisateur ; empreintes vérifiées ; aucun code exécuté |
| Lancement d'applications tierces | Intents construits par `engine:source` à partir de modèles fixes ; les URL capturées ne sont ouvertes que sur tap, avec `http` ou `https` pour seuls schémas admis |

Ce que le code de SeekMind ne fait pas, par conception : exécuter du code téléchargé, charger une vue
web, recevoir des diffusions d'autres applications, exposer un fournisseur de contenu. (Le SDK
publicitaire utilise sa propre vue web pour afficher les annonces.)

## 7. Politique de confidentialité

Plan du document à publier sur le site (rédaction : ticket M11-02, relecture juridique).

1. Qui édite l'application ; contact.
2. Ce que l'application lit, pourquoi, et ce qu'elle ne lit pas (reprend la divulgation).
3. Où sont les données : sur l'appareil, chiffrées ; aucune transmission ; aucun compte.
4. Ce que l'éditeur reçoit : rien. Aucune statistique, aucun rapport de plantage.
5. **Publicité** : annonces récompensées fournies par Google (AdMob), affichées uniquement à votre
   demande ; données que le SDK de Google collecte à cette occasion (identifiant publicitaire, données
   techniques, adresse IP) ; fenêtre de consentement et moyen de modifier ses choix ; lien vers la
   politique de Google. Ce que SeekMind a mémorisé n'est jamais transmis au réseau publicitaire.
6. Packs de langue : téléchargés par Google Play à votre demande.
7. Vos contrôles : familles d'applications, pause, exclusions, suppression, rétention, verrouillage, désinstallation.
8. Codes d'activation : vérifiés sur l'appareil ; aucune donnée transmise.
9. Mineurs ; modifications de la politique ; droit applicable.

## 8. Liste de contrôle de sécurité (ticket M10-05)

- [ ] Manifeste fusionné `release` de chaque variante : permissions du tableau 10.3 de l'architecture, rien de plus.
- [ ] Variante `offline` : aucune permission `INTERNET`, module publicitaire absent du graphe de dépendances.
- [ ] Composants exportés : quatre, tous justifiés ; aucun fournisseur de contenu exporté.
- [ ] `allowBackup="false"` et règles d'extraction vides, vérifiés sur l'APK.
- [ ] Aucun fichier hors base, clé, DataStore, modèles et répertoires propres au SDK publicitaire dans
      le stockage privé après une journée d'usage ; les répertoires du SDK ne contiennent aucune donnée mémorisée.
- [ ] Recherche de contenu utilisateur dans `logcat` pendant un scénario complet : rien.
- [ ] Test de silence réseau vert ; inspection du trafic pendant une demande de publicité : aucun texte
      mémorisé, aucune requête de recherche, aucun nom d'application dans les requêtes.
- [ ] `engine:ads` : aucune dépendance vers la base ou un autre module ; aucun appel aux API de ciblage.
- [ ] Tests de `LinkActivity` avec entrées malformées, surdimensionnées, mal signées.
- [ ] `FLAG_SECURE` sur chaque activité de SeekMind ; aperçu des applications récentes masqué.
- [ ] Verrouillage : l'interface n'est jamais composée avant l'authentification.
- [ ] Empreintes des dépendances vérifiées ; aucune dépendance hors liste blanche.
- [ ] Clés de test refusées par le build `release` (test).
- [ ] Protocole des écrans sensibles ([06 §8](06-qualite-tests-ci.md#8-validation-manuelle)) rejoué.
