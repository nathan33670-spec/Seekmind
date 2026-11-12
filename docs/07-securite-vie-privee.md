# 07 — Sécurité et vie privée

| | |
|---|---|
| **Version** | 1.0 — 12 novembre 2026 |
| **Documents liés** | [specs/confidentialite](specs/confidentialite.md) (règles de capture) · [specs/stockage](specs/stockage.md) · [ADR-0001](adr/0001-zero-permission-internet.md) |

## 1. Objectifs

1. Le contenu capturé ne quitte jamais l'appareil par le fait de l'application.
2. Le contenu le plus sensible n'entre jamais dans l'index.
3. Ce qui est dans l'index est illisible sans l'appareil déverrouillé.
4. L'utilisateur voit ce qui est mémorisé et peut l'effacer.
5. L'application ne peut pas servir d'outil de surveillance discret.

## 2. Actifs et adversaires

| Actif | Sensibilité |
|---|---|
| Texte capturé, titres, URL, vecteurs, historique des recherches | Très élevée : messages, navigation, vie privée |
| Clé de la base | Très élevée |
| État de monétisation (solde, licence) | Faible : valeur commerciale seulement |
| Clés privées de signature (hors appareil) | Élevée pour l'éditeur |

| Adversaire | Capacité | Dans le périmètre |
|---|---|---|
| Attaquant distant | Réseau | Oui — neutralisé par l'absence d'accès réseau |
| Application malveillante sur l'appareil | Intents, liens, superposition | Oui |
| Proche disposant du téléphone déverrouillé quelques minutes | Interface | Oui |
| Voleur d'un téléphone verrouillé | Stockage chiffré par le système | Oui |
| Éditeur de SeekMind, régie publicitaire | Mises à jour, page web | Oui |
| Attaquant avec accès root ou outillage d'investigation sur un appareil déverrouillé | Tout | **Non** — hors d'atteinte d'une application ; documenté |

## 3. Menaces et parades

| # | Menace | Parade | Réf. |
|---|---|---|---|
| T1 | Exfiltration du contenu par l'application ou une dépendance | Pas de permission `INTERNET`, pas d'API réseau, liste blanche de dépendances, vérifiés à chaque build | INV-01, INV-11 |
| T2 | Fuite par un canal détourné : URL ouverte dans le navigateur | Les seules URL ouvertes vers le site de l'éditeur ne portent qu'un nonce aléatoire, la version et la langue ; construction centralisée dans `RewardFlow`, testée | specs/monetisation §4 |
| T3 | Fuite par les logs, les rapports de plantage, la sauvegarde système | Logs sans contenu ; aucun rapport distant ; sauvegarde et transfert d'appareil désactivés | INV-07, INV-08, INV-09 |
| T4 | Indexation de secrets (mots de passe, banque, navigation privée) | Porte de confidentialité à plusieurs couches ; masquage des cartes et IBAN | specs/confidentialite |
| T5 | Image de l'écran laissée sur le stockage | Traitement en mémoire, libération dans `finally`, garde-fou et test | INV-03 |
| T6 | Lecture de la base par une autre application | Stockage privé de l'application ; base chiffrée ; aucun `ContentProvider` exporté | §4 |
| T7 | Lecture de la base sur un téléphone volé et verrouillé | Chiffrement SQLCipher, clé enveloppée par le Keystore matériel, en plus du chiffrement du système | §4 |
| T8 | Proche curieux ouvrant l'application | Verrouillage par biométrie ou code de l'appareil, activé par défaut ; `FLAG_SECURE` ; aperçu masqué dans les applications récentes | EF-PRIV-10, 11 |
| T9 | Installation à l'insu du propriétaire pour l'espionner | Icône et nom non masquables, service visible dans les réglages d'accessibilité, aucun accès à distance, tuile de pause, consentement à l'écran. L'application ne propose aucun mode discret. | §6 |
| T10 | Lien `seekmind://` forgé par une application ou une page web | Jeton signé ES256 ; récompense liée à un nonce en attente ; activation soumise à confirmation ; entrées bornées et validées | specs/monetisation §3 |
| T11 | Fraude aux crédits (modification locale, appel direct du service de signature) | État dans la base chiffrée ; limitation de débit et anti-robot côté Worker ; risque résiduel accepté : l'enjeu est commercial, pas de sécurité | R-02 |
| T12 | Pastille flottante détournée ou gênante | Fenêtre non focalisable, petite, limitée en fréquence, jamais affichée sur un écran rejeté par la porte de confidentialité | specs/actions-intelligentes |
| T13 | Dépendance compromise | Versions figées, liste blanche (M0-04), vérification des empreintes par Gradle (`verification-metadata.xml`, M10-05), mises à jour par PR dédiées | INV-11 |
| T14 | Perte de la clé (réinitialisation du Keystore) | Détection à l'ouverture, message clair, réinitialisation proposée ; aucune tentative de récupération | ENF-REL-02 |

## 4. Chiffrement

| Élément | Choix |
|---|---|
| Moteur | SQLCipher 4 (AES-256, intégrité par page HMAC-SHA512), réglages par défaut |
| Clé de base | 32 octets tirés de `SecureRandom` au premier lancement, fournie en clé brute (`x'…'`, sans dérivation) |
| Protection de la clé | Enveloppée en AES-256-GCM par une clé non exportable du Keystore Android (alias `seekmind.db.kek.v1`), StrongBox si disponible, repli sur le TEE |
| Stockage de la clé enveloppée | `noBackupFilesDir/db.key` : version, IV, texte chiffré |
| Authentification utilisateur sur la clé | **Non.** Le service écrit dès que l'écran s'allume et le lot d'embeddings tourne téléphone verrouillé en charge : lier la clé au déverrouillage casserait ces deux usages. Le verrouillage de l'application est une barrière d'interface, pas un chiffrement supplémentaire. |
| Effacement | « Tout effacer » ferme la base, supprime les fichiers, la clé enveloppée et l'alias du Keystore |
| `PRAGMA secure_delete` | `FAST` ; purge suivie d'un `incremental_vacuum` |

## 5. Cycle de vie des données

| Donnée | Emplacement | Chiffrée | Durée de vie |
|---|---|---|---|
| Image de l'écran | Mémoire vive | — | Le temps de l'OCR (moins d'une seconde) |
| Texte capturé, métadonnées, vecteurs | Base SQLCipher | Oui | Rétention choisie (90 jours par défaut), budget de stockage |
| Historique des recherches | Base SQLCipher | Oui | 90 jours ou suppression manuelle ; désactivable |
| Solde, licence, demandes de récompense | Base SQLCipher | Oui | Vie de l'installation |
| Compteurs de diagnostic | Base SQLCipher | Oui | 30 jours |
| Réglages, consentement | DataStore | Non (aucun contenu) | Vie de l'installation |
| Clé enveloppée | `noBackupFilesDir` | Par le Keystore | Vie de l'installation |

La désinstallation supprime tout. Rien n'est sauvegardé ni transféré vers un nouvel appareil en v1.

## 6. Surface exposée

| Point d'entrée | Contrôle |
|---|---|
| `MainActivity` (lanceur) | Verrouillage de l'application ; aucun paramètre d'Intent interprété |
| `LinkActivity` (`seekmind://reward`, `seekmind://activate`) | Seuls ces deux hôtes ; paramètre `t` de 2 048 caractères au plus, alphabet base64url et points ; vérification cryptographique avant tout effet ; pas d'ouverture d'URL à partir du lien |
| Service d'accessibilité | Lié uniquement par le système (`BIND_ACCESSIBILITY_SERVICE`) |
| Tuile de réglages rapides | Liée uniquement par le système ; n'expose que pause et reprise |
| Presse-papiers | Copie à l'initiative de l'utilisateur, marquée sensible (`EXTRA_IS_SENSITIVE`) |
| Partage | Feuille de partage du système, à l'initiative de l'utilisateur |
| Lancement d'applications tierces | Intents construits par `engine:source` et `engine:actions` à partir de modèles fixes ; les URL capturées ne sont ouvertes que sur tap, avec `http` ou `https` pour seuls schémas admis |

Ce que l'application ne fait pas, par conception : exécuter du code téléchargé, charger une vue web,
recevoir des diffusions d'autres applications, exposer un fournisseur de contenu.

## 7. Politique de confidentialité

Plan du document à publier sur le site (rédaction : ticket M11-02, relecture juridique).

1. Qui édite l'application ; contact.
2. Ce que l'application lit, pourquoi, et ce qu'elle ne lit pas (reprend la divulgation).
3. Où sont les données : sur l'appareil, chiffrées ; aucune transmission ; aucun compte.
4. Ce que l'éditeur reçoit : rien de l'application. Aucune statistique, aucun rapport de plantage.
5. Vos contrôles : pause, exclusions, suppression, rétention, verrouillage, désinstallation.
6. **La page de récompense est un site web distinct** : elle affiche de la publicité, recueille le
   consentement requis, et ne reçoit de l'application qu'un identifiant aléatoire à usage unique.
7. Codes d'activation : vérifiés sur l'appareil ; aucune donnée transmise.
8. Mineurs ; modifications de la politique ; droit applicable.

## 8. Liste de contrôle de sécurité (ticket M10-05)

- [ ] Manifeste fusionné `release` : permissions du tableau 10.2 de l'architecture, rien de plus.
- [ ] Composants exportés : quatre, tous justifiés ; aucun fournisseur de contenu.
- [ ] `allowBackup="false"` et règles d'extraction vides, vérifiés sur l'APK.
- [ ] Aucun fichier hors base, clé et DataStore dans le répertoire privé après une journée d'usage.
- [ ] Recherche de contenu utilisateur dans `logcat` pendant un scénario complet : rien.
- [ ] Tests de `LinkActivity` avec entrées malformées, surdimensionnées, mal signées.
- [ ] URL de récompense : seuls le nonce, la version et la langue y figurent.
- [ ] `FLAG_SECURE` sur chaque activité ; aperçu des applications récentes masqué.
- [ ] Verrouillage : l'interface n'est jamais composée avant l'authentification.
- [ ] Empreintes des dépendances vérifiées ; aucune dépendance hors liste blanche.
- [ ] Clés de test refusées par le build `release` (test).
- [ ] Protocole des écrans sensibles ([06 §8](06-qualite-tests-ci.md#8-validation-manuelle)) rejoué.
