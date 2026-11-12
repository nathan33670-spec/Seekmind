# SeekMind

> Moteur de recherche mémoriel pour Android : retrouver n'importe quelle information aperçue sur son
> téléphone — sans capture d'écran, sans compte, et sans que rien de ce qui est mémorisé ne quitte l'appareil.
>
> *Nom de travail du brief initial : ScreenMind. Identifiant d'application : `fr.corsicajack.seekmind`.*

**État du dépôt :** spécification complète et décisions prises, aucun code applicatif. Le développement
démarre au jalon [M0](docs/plan/M0-fondations.md) et sera réalisé par des agents IA, ticket par ticket.

## Le produit en bref

SeekMind lit le texte affiché à l'écran (via l'API d'accessibilité d'Android, avec le consentement
explicite de l'utilisateur), l'indexe dans une base chiffrée sur le téléphone, et permet de le retrouver
en langage naturel : « le resto italien avec des pâtes à la truffe vu mardi soir ». Chaque résultat
permet de revenir à l'application ou à la page d'origine.

| Les 4 piliers | En v1 |
|---|---|
| Recherche en langage naturel (mots-clés + sémantique) | Oui — modèle français embarqué, pack multilingue téléchargeable |
| Retour à la source (URL, application) | Oui |
| Bouclier de confidentialité | Oui — invariant d'architecture |
| Actions contextuelles (agenda, carte, suivi de colis…) | Après la première publication (v1.1) |

**Modèle économique v1 :** gratuit avec publicité récompensée AdMob (1 publicité = 5 recherches,
uniquement à la demande) **ou** code d'activation, offert au lancement, qui supprime la publicité et
rend les recherches illimitées.

**Deux variantes du même code :** `ads`, publiée sur Google Play ; `offline`, sans aucun accès réseau,
compilée et testée à chaque changement pour pouvoir remplacer la première si Google la refusait.

## Les invariants du projet

Ces règles priment sur tout le reste ; elles sont vérifiées automatiquement en CI.

1. **Ce qui est mémorisé ne quitte jamais le téléphone.** Le réseau n'est accessible qu'au module
   publicitaire, qui ne voit aucune donnée ; l'application n'émet rien tant que l'utilisateur ne demande
   pas de publicité. La variante `offline` n'a même pas la permission `INTERNET`.
2. **Stricte passivité.** Le service d'accessibilité lit l'écran ; il n'agit jamais à la place de l'utilisateur.
3. **Aucune image persistée.** Les captures d'écran servant à l'OCR ne vivent qu'en mémoire vive.
4. **Consentement d'abord.** Rien n'est lu avant l'acceptation de l'écran de divulgation et le choix des
   familles d'applications à mémoriser.
5. **Tout est chiffré sur l'appareil**, et les contenus sensibles (mots de passe, navigation privée,
   fenêtres sécurisées, applications bancaires) ne sont jamais indexés.

## Carte de la documentation

| Document | Contenu |
|---|---|
| [docs/01-vision-produit.md](docs/01-vision-produit.md) | Problème, cible, positionnement, modèle économique, feuille de route |
| [docs/02-cahier-des-charges.md](docs/02-cahier-des-charges.md) | Périmètre v1, exigences fonctionnelles et non fonctionnelles, risques, **décisions prises** |
| [docs/03-architecture.md](docs/03-architecture.md) | Modules, variantes, flux, threads, pile technique et versions, garde-fous |
| [docs/04-prerequis.md](docs/04-prerequis.md) | Comptes, clés, outillage, environnement des agents, matériel de test |
| [docs/05-conformite-play-store.md](docs/05-conformite-play-store.md) | Politique d'accessibilité Google Play, divulgation, déclarations, vidéo, plan de repli |
| [docs/06-qualite-tests-ci.md](docs/06-qualite-tests-ci.md) | Stratégie de tests, application de test, CI, mesures de performance |
| [docs/07-securite-vie-privee.md](docs/07-securite-vie-privee.md) | Modèle de menace, cycle de vie des données, chiffrement |
| [docs/specs/](docs/specs/) | Spécifications techniques détaillées par sous-système |
| [docs/adr/](docs/adr/) | Décisions d'architecture (ADR) et leurs alternatives |
| [docs/plan/](docs/plan/) | **Plan de développement pas à pas** : jalons, tickets, dépendances, revue et fusion |
| [AGENTS.md](AGENTS.md) | Consignes permanentes pour les agents de développement |

## Lancer les développements

1. Réunir les prérequis de la liste « Prêt pour M0 » : [docs/04-prerequis.md §8](docs/04-prerequis.md#8-listes-de-contrôle).
2. Confier les tickets aux agents dans l'ordre du [plan](docs/plan/README.md), un ticket = une branche = une PR.
3. Faire relire chaque PR par un second agent ; la fusion est automatique quand la CI et la revue passent
   ([règles](docs/plan/README.md#14-revue-et-fusion)).

Consigne type à donner à un agent :

```text
Implémente le ticket M0-01 décrit dans docs/plan/M0-fondations.md.
Lis d'abord AGENTS.md, puis les sections de spécification référencées par le ticket.
Respecte la « Definition of Done » et ouvre une pull request vers main.
```

## Arborescence cible

```text
app/                 Application Android (manifeste, injection, navigation ; sources par variante)
core/                common · model · database · designsystem · testing
engine/              privacy · capture · ocr · index · embedding · search · source · monetization · ads
feature/             onboarding · search · journal · settings · paywall
modelpack_multi/     Pack de langue multilingue, livré à la demande par Google Play
fixtureapp/          Application de test pilotée par les tests de bout en bout
build-logic/         Plugins de convention Gradle
tools/               model (Python) · license (Python)
site/                Pages statiques : politique de confidentialité, assistance, relais d'activation
scripts/             Outillage (SDK Android, garde-fous CI)
docs/                Spécifications
```

Seuls `docs/`, `scripts/` et les fichiers racine existent aujourd'hui ; le reste est créé par les tickets.
