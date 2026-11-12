# 04 — Prérequis

| | |
|---|---|
| **Version** | 1.1 — 12 novembre 2026 (décisions du porteur intégrées) |
| **Objet** | Tout ce qui doit exister *en dehors du code* pour que les agents puissent développer, tester et publier |

Les éléments marqués **[Humain]** ne peuvent pas être réalisés par un agent : création de comptes,
paiements, validation d'identité, appareils physiques, décisions.

## 1. Décisions

Les décisions sont prises : [cahier des charges §9](02-cahier-des-charges.md#9-décisions). Trois valeurs
par défaut restent modifiables sans conséquence sur l'architecture : le barème des crédits (D-06),
l'état initial des cases des familles d'applications (D-13) et la licence du dépôt (D-11).

## 2. Comptes et services

| # | Élément | Pour quoi | Quand | Notes |
|---|---|---|---|---|
| 2.1 | **Dépôt GitHub** `nathan33670-spec/Seekmind` | Code, CI | Fait | Activer GitHub Actions. Protéger `main` : PR obligatoire, vérifications `ci` et `device-tests` requises, fusion automatique autorisée. |
| 2.2 | **Compte Google Play Console** (personnel, existant) | Publication | Avant M11 | Vérifier dans la console si le **test fermé** s'applique : pour un compte personnel créé après le 13 novembre 2023, au moins 12 testeurs inscrits pendant 14 jours consécutifs avant l'accès à la production. Recruter ces testeurs tôt, dont certains équipés d'Android 11 à 13 et de Samsung ou Xiaomi (§7). |
| 2.3 | **[Humain]** Compte **AdMob** | Publicité récompensée (variante `ads`) | À ouvrir dès maintenant ; nécessaire pour M8-03 | Créer l'application et un bloc d'annonces **récompensé** ; relever l'identifiant d'application et l'identifiant du bloc. Configurer le **message de consentement** pour l'Europe (rubrique de confidentialité et de messages d'AdMob). La diffusion complète suppose que l'application soit publiée et liée à sa fiche. |
| 2.4 | **[Humain]** **Site sur GitHub Pages** | Politique de confidentialité (URL obligatoire pour Google Play), assistance, page de relais des liens d'activation, `app-ads.txt` | Avant M11 ; la page de relais dès M8-05 | Voir §2.1 ci-dessous. |
| 2.5 | **[Humain]** Adresse e-mail d'assistance | Fiche Play, politique de confidentialité | Avant M11 | |
| 2.6 | **[Humain]** Avis juridique | RGPD et consentement publicitaire, politique de confidentialité | Avant publication | Risque R-13. |

Ne sont **pas** nécessaires en v1 : nom de domaine, hébergement de fonctions, régie publicitaire web.
Ils ne le deviendraient que pour la solution de repli par le navigateur ([annexe §6](specs/annexe-recompense-navigateur.md)).

### 2.1 Site sur GitHub Pages

- Le contenu du site vit dans le dossier `site/` de ce dépôt (pages statiques, rédigées par le ticket M11-02).
- GitHub Pages ne publie le site d'un dépôt **privé** qu'avec une offre payante. Si le dépôt reste privé
  sur une offre gratuite, publier `site/` depuis un petit dépôt public dédié.
- **`app-ads.txt`** : AdMob cherche ce fichier à la **racine** du site web du développeur déclaré dans la
  fiche Google Play. Un site de projet (`https://<compte>.github.io/Seekmind/`) ne convient pas pour ce
  fichier : il faut le site de compte (`https://<compte>.github.io/`, dépôt `<compte>.github.io`), ou un
  domaine personnalisé. Sans `app-ads.txt` vérifié, la diffusion des annonces peut être limitée.
- Un domaine personnalisé pourra remplacer GitHub Pages plus tard sans toucher à l'application : seules
  l'URL de la politique de confidentialité et celle de la page de relais sont des paramètres de build.

## 3. Clés et secrets

Aucun de ces éléments ne va dans le dépôt (INV-10). Les clés publiques, elles, sont committées.

| Clé | Algorithme | Générée par | Partie privée conservée | Partie publique |
|---|---|---|---|---|
| **Clé d'import Play** (*upload key*) | RSA 4096, keystore PKCS12 | **[Humain]** `keytool` | Gestionnaire de mots de passe + secret GitHub `UPLOAD_KEYSTORE_B64` | Play Console (Play App Signing) |
| **Clé de signature des licences** | ECDSA P-256 (ES256) | `tools/license/seekmind_keys.py keygen --purpose license` | **Hors ligne** : gestionnaire de mots de passe ou clé matérielle. Jamais sur un serveur. | `engine/monetization/.../keys-release.json`, `kid` = `l1` |
| Clé de **test** | ECDSA P-256 | Committée dans `engine/monetization/src/debug/` et `src/test/` | Committée (ne protège rien) | `keys-debug.json`, `kid` = `l-test` ; refusée par le build `release` |

Rotation : le champ `kid` des jetons permet d'embarquer plusieurs clés publiques. Une clé compromise est
retirée de `keys-release.json` dans une mise à jour de l'application.

**Identifiants AdMob** : l'identifiant d'application et l'identifiant du bloc récompensé ne sont pas des
secrets (ils figurent dans l'application publiée). Ils sont fournis au build `release` par les propriétés
`seekmind.admob.appId` et `seekmind.admob.rewardedUnitId`. Le build `debug` utilise les identifiants de
test publics de Google : aucun compte n'est nécessaire pour développer.

Secrets GitHub Actions nécessaires à la publication (M11-05) : `UPLOAD_KEYSTORE_B64`,
`UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`. La CI de développement n'a besoin
d'aucun secret.

## 4. Outillage de développement

| Outil | Version | Usage |
|---|---|---|
| JDK | 17 minimum, 21 recommandé | Gradle, AGP |
| SDK Android | `platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools` | Compilation — installé par `scripts/setup-android-sdk.sh` |
| Gradle | 9.9.1, via le wrapper (créé en M0-01) | Build |
| Python | 3.13+ | `tools/model` (modèles, vocabulaires, jeux de référence), `tools/license` |
| Android Studio | Dernière stable — facultatif | Débogage sur appareil par un humain |

Versions des bibliothèques : [03 §8](03-architecture.md#8-pile-technique-et-versions).

## 5. Environnement des agents IA

Les développements sont réalisés par des agents dans des environnements cloud éphémères. Conséquences :

### 5.1 Ce que l'environnement doit fournir

- **JDK 17+ et Python 3.13+.**
- **Le SDK Android.** Le dépôt fournit `scripts/setup-android-sdk.sh` (idempotent, non interactif,
  environ 500 Mo, une minute). Vérifié le 12 novembre 2026 dans un environnement cloud vierge.
  Il lit les versions de la plateforme et des build-tools dans `gradle/libs.versions.toml`.
  `scripts/doctor.sh` vérifie ensuite les prérequis et donne la commande qui corrige chaque manque.
  À appeler depuis le script d'initialisation de l'environnement, qui s'exécute un répertoire au-dessus du dépôt :

  ```bash
  if [ -d Seekmind ]; then bash Seekmind/scripts/setup-android-sdk.sh; fi
  ```

- **L'accès réseau aux dépôts de paquets.** Si la politique réseau est restrictive, autoriser :

  | Hôte | Usage |
  |---|---|
  | `dl.google.com` | SDK Android et dépôt Maven de Google |
  | `repo.maven.apache.org`, `repo1.maven.org` | Maven Central |
  | `plugins.gradle.org`, `services.gradle.org`, `downloads.gradle.org` | Plugins et distribution Gradle |
  | `huggingface.co` et son CDN de fichiers | Téléchargement des modèles par `tools/model` (jalons M0-09, M0-10, M4) |
  | `pypi.org`, `files.pythonhosted.org` | `tools/` |

### 5.2 Ce que l'environnement ne fournit pas

- **Pas d'émulateur ni d'appareil.** Les tests instrumentés et de bout en bout tournent dans GitHub
  Actions (workflow `device-tests`). L'agent pousse sa branche, lit le résultat, corrige.
- **Pas de validation visuelle sur un vrai téléphone.** Les tickets qui l'exigent le signalent par la
  mention « Validation manuelle » ; la procédure et le résultat sont consignés dans la PR par un humain.
- **Pas de Google Play ni de publicité réelle.** Le téléchargement d'un pack de langue et l'affichage
  d'une annonce ne se valident que sur un téléphone.

### 5.3 Organisation du travail

- Un ticket = une session d'agent = une branche = une PR vers `main`.
- **Revue et fusion** (décision D-14) : un second agent relit chaque PR ; la fusion est automatique
  quand la CI et la revue passent ; certains tickets attendent la validation du porteur du projet.
  Règles : [plan §1.4](plan/README.md#14-revue-et-fusion).
- L'ordre et les parallélisations possibles sont dans [plan/README](plan/README.md).
- Les agents suivent [AGENTS.md](../AGENTS.md) ; `CLAUDE.md` l'importe.
- Points de conflit entre agents travaillant en parallèle : `gradle/libs.versions.toml`,
  `settings.gradle.kts`, les modules Hilt de `:app`, les fichiers `strings.xml`. Règle : fusionner
  `main` dans sa branche avant d'ouvrir la PR, et garder ces modifications minimales.

## 6. Intégration continue

Créée par les tickets M0-02 à M0-04 et M0-08. GitHub Actions, exécuteurs `ubuntu-latest`.

| Workflow | Déclencheur | Contenu | Durée visée |
|---|---|---|---|
| `ci` | Chaque PR et `main` | Compilation des **deux variantes**, `spotlessCheck`, `lint`, tests JVM, garde-fous (§10.4 de l'architecture), tests des outils Python | ≤ 12 min |
| `device-tests` | Chaque PR et `main` | Émulateurs API 30, 34 et 36 (x86_64, accélération KVM) : tests instrumentés et de bout en bout avec l'application de test, variante `ads` ; contrôle de fumée de la variante `offline` sur l'API 34 | ≤ 25 min |
| `model` | Manuel, hebdomadaire, et sur les PR qui touchent `tools/model` ou `model.lock.json` | Reconstruit les deux modèles depuis leurs sources figées, vérifie les empreintes, régénère les jeux de référence et échoue s'ils changent, alimente le cache | ≤ 30 min |
| `pages` | Modification de `site/` sur `main` | Publie le site statique | ≤ 2 min |
| `release` | Étiquette `v*` | AAB signé de la variante `ads` **et** de la variante `offline`, artefacts à téléverser | ≤ 20 min |

Les modèles sont mis en cache par GitHub Actions (action `.github/actions/model-artifacts`), avec pour clé
`model.lock.json` et l'outillage : `ci` et `device-tests` restaurent le jeu `fr`, ou le reconstruisent
et le vérifient en cas d'absence.
Dépôt privé : surveiller le quota de minutes ; `device-tests` est le poste principal.

## 7. Matériel de test **[Humain]**

Disponible : **un téléphone sous Android 14 ou plus récent** (décision D-16). C'est l'appareil de
référence pour toutes les validations manuelles.

Ce qui n'est **pas** couvert par cet appareil, et comment y remédier (risque R-14) :

| Lacune | Remède |
|---|---|
| Android 11 à 13 : détection heuristique des fenêtres sécurisées, performances plancher | Émulateur API 30 en CI pour le fonctionnel ; pour le reste, testeurs du test fermé équipés d'Android 11 à 13, avec le protocole des écrans sensibles |
| Surcouches Samsung et Xiaomi, qui arrêtent les services en arrière-plan | Au moins un testeur par marque dans le test fermé ; retour sur la tenue de la mémorisation pendant 48 h |
| Appareil bas de gamme (4 Go de RAM) | Mesures sur émulateur à mémoire réduite, à confirmer par un testeur |

Applications à installer pour la recette : Chrome, Firefox, WhatsApp, Instagram, TikTok, YouTube,
Reddit, Gmail, Google Maps, une application bancaire, un gestionnaire de mots de passe.

Installation d'un build de test : `adb install`. Une installation par fichier APK depuis un gestionnaire
de fichiers déclenche les « paramètres restreints » d'Android 13+, qui empêchent d'activer le service
d'accessibilité tant que l'utilisateur ne les a pas levés manuellement.

## 8. Listes de contrôle

### Prêt pour M0 (démarrage des développements)

- [x] Décisions prises (§1).
- [ ] GitHub Actions activé sur le dépôt ; fusion automatique autorisée.
- [ ] Environnement des agents : script d'initialisation en place (§5.1), hôtes autorisés.
- [ ] Téléphone de test disponible pour les validations manuelles des prototypes M0-07 à M0-09.

### Prêt pour M8 (monétisation)

- [ ] Compte AdMob ouvert ; application et bloc d'annonces récompensé créés ; identifiants transmis.
- [ ] Message de consentement européen configuré dans AdMob.
- [ ] Paire de clés « licence » générée ; clé publique transmise pour intégration.
- [ ] Page de relais des liens d'activation en ligne (site GitHub Pages).

### Prêt pour M11 (publication)

- [ ] Compte Google Play Console vérifié ; nécessité du test fermé confirmée.
- [ ] Clé d'import générée, secrets GitHub renseignés.
- [ ] Site et politique de confidentialité en ligne ; `app-ads.txt` à la racine du site déclaré.
- [ ] Douze testeurs recrutés si le test fermé s'applique, dont des appareils Android 11 à 13, Samsung et Xiaomi.
- [ ] Avis juridique obtenu.
