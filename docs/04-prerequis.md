# 04 — Prérequis

| | |
|---|---|
| **Version** | 1.0 — 12 novembre 2026 |
| **Objet** | Tout ce qui doit exister *en dehors du code* pour que les agents puissent développer, tester et publier |

Les éléments marqués **[Humain]** ne peuvent pas être réalisés par un agent : création de comptes,
paiements, validation d'identité, appareils physiques, décisions.

## 1. Décisions

Valider les décisions du [cahier des charges §9](02-cahier-des-charges.md#9-décisions-à-valider).
D-03 (nom, identifiant d'application, domaine) et D-08 (Android minimum) conditionnent M0 ; D-01 et D-02
(publicité, codes) conditionnent M8.

## 2. Comptes et services

| # | Élément | Pour quoi | Quand | Notes |
|---|---|---|---|---|
| 2.1 | **Dépôt GitHub** `nathan33670-spec/Seekmind` | Code, CI | Fait | Activer GitHub Actions. Protéger `main` : PR obligatoire, vérifications `ci` et `device-tests` requises. |
| 2.2 | **[Humain]** Compte **Google Play Console** | Publication | Avant M11, à ouvrir tôt | 25 USD, vérification d'identité. **Compte personnel** : test fermé obligatoire avec au moins 12 testeurs inscrits pendant 14 jours consécutifs avant l'accès à la production. **Compte d'organisation** : numéro D-U-N-S requis (délai possible de plusieurs semaines), pas de test fermé obligatoire. |
| 2.3 | **[Humain]** **Nom de domaine** | Page de récompense, politique de confidentialité, site | Avant M8 | Le domaine fixe l'identifiant d'application (D-03). |
| 2.4 | **[Humain]** Compte **Cloudflare** (ou équivalent) | Héberger `reward-web` (Workers), anti-robot (Turnstile) | Avant M8 | L'offre gratuite suffit pour démarrer. |
| 2.5 | **[Humain]** **Régie publicitaire web** proposant la publicité récompensée | Revenus | À lancer dès que le site existe : l'approbation prend du temps | Référence : Google Ad Manager, « rewarded ads for web » (balise GPT). La régie exige un site réel avec du contenu ; prévoir le site vitrine (2.6). Pas de vérification côté serveur de la récompense sur le web : voir [specs/monetisation §4.5](specs/monetisation.md). |
| 2.6 | **Site vitrine** : accueil, FAQ, assistance, politique de confidentialité | Fiche Play (URL obligatoire), approbation de la régie | Avant M11 (contenu rédigé en M11-02) | Hébergé sur le même domaine. |
| 2.7 | **[Humain]** Adresse e-mail d'assistance | Fiche Play, politique de confidentialité | Avant M11 | |
| 2.8 | **[Humain]** Avis juridique | RGPD/ePrivacy de la page publicitaire, politique de confidentialité, vente de codes | Avant publication | Risques R-03 et R-13. |

## 3. Clés et secrets

Aucun de ces éléments ne va dans le dépôt (INV-10). Les clés publiques, elles, sont committées.

| Clé | Algorithme | Générée par | Partie privée conservée | Partie publique |
|---|---|---|---|---|
| **Clé d'import Play** (*upload key*) | RSA 4096, keystore PKCS12 | **[Humain]** `keytool` | Gestionnaire de mots de passe + secret GitHub `UPLOAD_KEYSTORE_B64` | Play Console (Play App Signing) |
| **Clé de signature des récompenses** | ECDSA P-256 (ES256) | `tools/license/seekmind_keys.py keygen --purpose reward` | Secret du Worker Cloudflare `REWARD_SIGNING_KEY` | `engine/monetization/.../keys-release.json`, `kid` = `r1` |
| **Clé de signature des licences** | ECDSA P-256 (ES256) | `tools/license/seekmind_keys.py keygen --purpose license` | **Hors ligne** : gestionnaire de mots de passe ou clé matérielle. Jamais sur un serveur en v1. | `keys-release.json`, `kid` = `l1` |
| Clés de **test** | ECDSA P-256 | Committées dans `engine/monetization/src/debug/` et `src/test/` | Committée (ne protège rien) | `keys-debug.json`, `kid` = `r-test`, `l-test` ; refusées par le build `release` |
| Secret **Turnstile** | — | Cloudflare | Secret du Worker `TURNSTILE_SECRET` | Clé de site dans la page |

Rotation : le champ `kid` des jetons permet d'embarquer plusieurs clés publiques. Une clé compromise est
retirée de `keys-release.json` dans une mise à jour de l'application.

Secrets GitHub Actions nécessaires à la publication (M11-05) : `UPLOAD_KEYSTORE_B64`,
`UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`. La CI de développement n'a besoin
d'aucun secret.

## 4. Outillage de développement

| Outil | Version | Usage |
|---|---|---|
| JDK | 17 minimum, 21 recommandé | Gradle, AGP |
| SDK Android | `platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools` | Compilation — installé par `scripts/setup-android-sdk.sh` |
| Gradle | 9.9.1, via le wrapper (créé en M0-01) | Build |
| Python | 3.11+ | `tools/model` (modèle, vocabulaire, jeux de référence), `tools/license` |
| Node.js | 22 | `reward-web` (Wrangler) |
| Android Studio | Dernière stable — facultatif | Débogage sur appareil par un humain |

Versions des bibliothèques : [03 §8](03-architecture.md#8-pile-technique-et-versions).

## 5. Environnement des agents IA

Les développements sont réalisés par des agents dans des environnements cloud éphémères. Conséquences :

### 5.1 Ce que l'environnement doit fournir

- **JDK 17+, Python 3.11+, Node 22.**
- **Le SDK Android.** Le dépôt fournit `scripts/setup-android-sdk.sh` (idempotent, non interactif,
  environ 500 Mo, une minute). Vérifié le 12 novembre 2026 dans un environnement cloud vierge.
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
  | `huggingface.co` et son CDN de fichiers | Téléchargement du modèle par `tools/model` (jalons M0-09, M0-10, M4) |
  | `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org` | `reward-web`, `tools/` |

### 5.2 Ce que l'environnement ne fournit pas

- **Pas d'émulateur ni d'appareil.** Les tests instrumentés et de bout en bout tournent dans GitHub
  Actions (workflow `device-tests`). L'agent pousse sa branche, lit le résultat, corrige.
- **Pas de validation visuelle sur un vrai téléphone.** Les tickets qui l'exigent le signalent par la
  mention « Validation manuelle » ; la procédure et le résultat sont consignés dans la PR par un humain.

### 5.3 Organisation du travail

- Un ticket = une session d'agent = une branche = une PR vers `main`.
- L'ordre et les parallélisations possibles sont dans [plan/README](plan/README.md).
- Les agents suivent [AGENTS.md](../AGENTS.md) ; `CLAUDE.md` l'importe.
- Points de conflit entre agents travaillant en parallèle : `gradle/libs.versions.toml`,
  `settings.gradle.kts`, les modules Hilt de `:app`, les fichiers `strings.xml`. Règle : fusionner
  `main` dans sa branche avant d'ouvrir la PR, et garder ces modifications minimales.

## 6. Intégration continue

Créée par les tickets M0-02 à M0-04 et M0-08. GitHub Actions, exécuteurs `ubuntu-latest`.

| Workflow | Déclencheur | Contenu | Durée visée |
|---|---|---|---|
| `ci` | Chaque PR et `main` | Compilation, `spotlessCheck`, `lint`, tests JVM, garde-fous (§10.3 de l'architecture), tests des outils Python et de `reward-web` | ≤ 10 min |
| `device-tests` | Chaque PR et `main` | Émulateurs API 30, 34 et 36 (x86_64, accélération KVM) : tests instrumentés et de bout en bout avec l'application de test | ≤ 25 min |
| `model` | Manuel et hebdomadaire | Reconstruit le modèle et les jeux de référence, vérifie les empreintes | ≤ 20 min |
| `release` | Étiquette `v*` | AAB signé, artefact à téléverser | ≤ 15 min |

Le modèle (~118 Mo) est mis en cache par GitHub Actions, avec son empreinte SHA-256 comme clé.
Dépôt privé : surveiller le quota de minutes ; `device-tests` est le poste principal.

## 7. Matériel de test **[Humain]**

| Appareil | Rôle |
|---|---|
| Un téléphone Android 11 ou 12, 4 Go de RAM, entrée de gamme | Performance plancher, chemin sans `takeScreenshotOfWindow` |
| Un téléphone Android 14 ou plus récent, milieu de gamme | Référence des mesures ENF |
| Idéalement un Samsung et un Xiaomi | Surcouches qui arrêtent les services en arrière-plan (risque R-04) |

Applications à installer pour la recette : Chrome, Firefox, WhatsApp, Instagram, TikTok, YouTube,
Reddit, Gmail, Google Maps, une application bancaire, un gestionnaire de mots de passe.

Installation d'un build de test : `adb install`. Une installation par fichier APK depuis un gestionnaire
de fichiers déclenche les « paramètres restreints » d'Android 13+, qui empêchent d'activer le service
d'accessibilité tant que l'utilisateur ne les a pas levés manuellement.

## 8. Listes de contrôle

### Prêt pour M0 (démarrage des développements)

- [ ] Décisions D-03 et D-08 confirmées (ou valeurs par défaut acceptées).
- [ ] GitHub Actions activé sur le dépôt.
- [ ] Environnement des agents : script d'initialisation en place (§5.1), hôtes autorisés.
- [ ] Un appareil de test disponible pour les validations manuelles des prototypes M0-07 à M0-09.

### Prêt pour M8 (monétisation)

- [ ] Décisions D-01 et D-02 confirmées.
- [ ] Domaine acquis, compte Cloudflare créé.
- [ ] Paires de clés « récompense » et « licence » générées ; clés publiques transmises pour intégration.
- [ ] Demande d'ouverture de compte auprès de la régie publicitaire déposée.

### Prêt pour M11 (publication)

- [ ] Compte Google Play Console ouvert et vérifié.
- [ ] Clé d'import générée, secrets GitHub renseignés.
- [ ] Site vitrine et politique de confidentialité en ligne.
- [ ] Douze testeurs recrutés si le compte est personnel.
- [ ] Avis juridique obtenu.
