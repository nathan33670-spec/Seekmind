# ADR-0001 — L'application n'a aucune permission réseau

| | |
|---|---|
| **Statut** | Accepté — amendé par [ADR-0009](0009-publicite-admob-et-variante-sans-reseau.md) : s'applique tel quel à la variante `offline` ; dans la variante `ads`, le réseau est cantonné au module publicitaire |
| **Date** | 2026-11-12 |

## Contexte

SeekMind lit, par l'API d'accessibilité, tout ce qui s'affiche à l'écran. C'est la capacité la plus
intrusive qu'une application Android puisse demander ; Google Play la contrôle étroitement et les
utilisateurs s'en méfient à raison. Une promesse de confidentialité ne suffit pas : il faut une
garantie que l'utilisateur, un chercheur en sécurité ou un examinateur de Google puisse **vérifier**.

## Décision

Le manifeste de l'application publiée ne contient pas `android.permission.INTERNET`, et le code
n'embarque aucune API réseau. Sans cette permission, le système refuse à l'application toute ouverture
de connexion.

Mise en œuvre :

- la permission, apportée par des dépendances (ML Kit via `com.google.android.datatransport`), est
  retirée du manifeste fusionné par `tools:node="remove"` ;
- le build échoue si elle réapparaît (`:app:verifyOfflineReleaseManifest`) ;
- les API réseau sont interdites dans les sources (`scripts/check-forbidden-apis.sh`) ;
- les dépendances sont limitées à une liste blanche.

## Conséquences

- **Aucun SDK en ligne** : pas de publicité intégrée, pas d'analytics, pas de rapport de plantage, pas
  de configuration distante, pas de téléchargement de modèle. Tout est embarqué.
- La publicité ne peut pas être intégrée à l'application : c'est ce qui a conduit à l'ADR-0002, puis à
  la variante `ads` de l'ADR-0009. L'activation passe par des jetons signés (ADR-0007).
- Le diagnostic est local ; l'assistance repose sur un rapport que l'utilisateur partage lui-même.
- La taille de l'application augmente (modèles embarqués).
- Un canal résiduel existe : une application peut faire ouvrir une URL par le navigateur. Les seules
  URL ouvertes vers le site de l'éditeur ne portent qu'un nonce aléatoire (modèle de menace, T2).
- `ACCESS_NETWORK_STATE` (connaître l'état du réseau, sans pouvoir émettre) reste tolérée tant que
  WorkManager ou ML Kit en dépendent ; objectif : la retirer (architecture §10.3).

## Alternatives écartées

- **Permission présente, engagement contractuel de ne rien envoyer** : invérifiable, donc sans valeur
  pour la confiance comme pour la revue Google Play.
- **Isolation par processus** : les permissions Android s'appliquent à l'application entière, pas à un
  processus ; un processus « sans réseau » dans une application qui a la permission ne prouve rien.
