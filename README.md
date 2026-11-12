# SeekMind

> Moteur de recherche mémoriel **100 % local** pour Android : retrouver n'importe quelle information
> aperçue sur son téléphone — sans capture d'écran, sans cloud, sans compte.
>
> *Nom de travail du brief initial : ScreenMind.*

**État du dépôt :** spécification complète, aucun code applicatif. Le développement démarre au jalon
[M0](docs/plan/M0-fondations.md) et sera réalisé par des agents IA, ticket par ticket.

## Le produit en bref

SeekMind lit le texte affiché à l'écran (via l'API d'accessibilité d'Android, avec le consentement
explicite de l'utilisateur), l'indexe dans une base chiffrée sur le téléphone, et permet de le retrouver
en langage naturel : « le resto italien avec des pâtes à la truffe vu mardi soir ». Chaque résultat
permet de revenir à l'application ou à la page d'origine.

| Les 4 piliers | En v1 |
|---|---|
| Recherche en langage naturel (mots-clés + sémantique) | Oui |
| Retour à la source (URL, application) | Oui |
| Actions contextuelles (agenda, carte, suivi de colis…) | Oui (dernier jalon fonctionnel) |
| Bouclier « Zéro Cloud » | Oui — invariant d'architecture |

**Modèle économique v1 :** gratuit avec publicité récompensée (1 publicité = 5 recherches) **ou**
code d'activation qui supprime la publicité et rend les recherches illimitées.

## Les invariants du projet

Ces règles priment sur tout le reste ; elles sont vérifiées automatiquement en CI.

1. **Zéro réseau.** L'application n'a pas la permission `INTERNET` et n'embarque aucune API réseau.
2. **Stricte passivité.** Le service d'accessibilité lit l'écran ; il n'agit jamais à la place de l'utilisateur.
3. **Aucune image persistée.** Les captures d'écran servant à l'OCR ne vivent qu'en mémoire vive.
4. **Consentement d'abord.** Rien n'est lu avant l'acceptation de l'écran de divulgation.
5. **Tout est chiffré sur l'appareil**, et les contenus sensibles (mots de passe, navigation privée,
   fenêtres sécurisées, applications bancaires) ne sont jamais indexés.

## Carte de la documentation

| Document | Contenu |
|---|---|
| [docs/01-vision-produit.md](docs/01-vision-produit.md) | Problème, cible, positionnement, modèle économique, feuille de route |
| [docs/02-cahier-des-charges.md](docs/02-cahier-des-charges.md) | Périmètre v1, exigences fonctionnelles et non fonctionnelles, risques, **décisions à valider** |
| [docs/03-architecture.md](docs/03-architecture.md) | Modules, flux, threads, pile technique et versions, garde-fous |
| [docs/04-prerequis.md](docs/04-prerequis.md) | Comptes, clés, outillage, environnement des agents, matériel de test |
| [docs/05-conformite-play-store.md](docs/05-conformite-play-store.md) | Politique d'accessibilité Google Play, divulgation, déclarations, vidéo |
| [docs/06-qualite-tests-ci.md](docs/06-qualite-tests-ci.md) | Stratégie de tests, application de test, CI, mesures de performance |
| [docs/07-securite-vie-privee.md](docs/07-securite-vie-privee.md) | Modèle de menace, cycle de vie des données, chiffrement |
| [docs/specs/](docs/specs/) | Spécifications techniques détaillées par sous-système |
| [docs/adr/](docs/adr/) | Décisions d'architecture (ADR) et leurs alternatives |
| [docs/plan/](docs/plan/) | **Plan de développement pas à pas** : jalons M0 → M11, tickets, dépendances |
| [AGENTS.md](AGENTS.md) | Consignes permanentes pour les agents de développement |

## Lancer les développements

1. Valider les décisions ouvertes : [cahier des charges §9](docs/02-cahier-des-charges.md#9-décisions-à-valider).
2. Réunir les prérequis : [docs/04-prerequis.md](docs/04-prerequis.md) (la liste « prêt pour M0 » suffit pour commencer).
3. Confier les tickets aux agents dans l'ordre du [plan](docs/plan/README.md), un ticket = une branche = une PR.

Consigne type à donner à un agent :

```text
Implémente le ticket M0-01 décrit dans docs/plan/M0-fondations.md.
Lis d'abord AGENTS.md, puis les sections de spécification référencées par le ticket.
Respecte la « Definition of Done » et ouvre une pull request vers main.
```

## Arborescence cible

```text
app/                 Application Android (manifeste, injection, navigation)
core/                common · model · database · designsystem · testing
engine/              privacy · capture · ocr · index · embedding · search · source · actions · monetization
feature/             onboarding · search · journal · settings · paywall
fixtureapp/          Application de test pilotée par les tests de bout en bout
build-logic/         Plugins de convention Gradle
tools/               model (Python) · license (Python)
reward-web/          Page de publicité récompensée + signature des jetons (Cloudflare Workers)
scripts/             Outillage (SDK Android, garde-fous CI)
docs/                Spécifications
```

Seuls `docs/`, `scripts/` et les fichiers racine existent aujourd'hui ; le reste est créé par les tickets.
