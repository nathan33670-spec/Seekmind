# ADR-0002 — La publicité récompensée s'affiche dans le navigateur

| | |
|---|---|
| **Statut** | **Écarté pour la v1** par le porteur du projet le 12 novembre 2026 — conservé comme solution de repli (voir [ADR-0009](0009-publicite-admob-et-variante-sans-reseau.md)) |
| **Date** | 2026-11-12 |

> **Note du 12 novembre 2026.** Le porteur du projet a retenu le SDK AdMob dans l'application
> (ADR-0009). Ce document n'est pas modifié sur le fond : il décrit la solution vers laquelle basculer
> si Google Play refuse la variante avec publicité intégrée.

## Contexte

Deux exigences du projet sont incompatibles telles quelles :

1. l'application n'a aucune permission réseau (ADR-0001), argument central de confiance et de validation ;
2. la formule gratuite est financée par la publicité récompensée : une publicité = cinq recherches.

Tout SDK publicitaire exige `INTERNET`, collecte des identifiants d'appareil et s'exécute dans le même
processus que le service qui lit l'écran.

## Décision

La publicité est affichée **hors de l'application**, dans le navigateur de l'utilisateur, sur une page
web de l'éditeur. La page renvoie à l'application, par un lien `seekmind://`, un **jeton signé** lié à
un nonce que l'application a émis. L'application vérifie le jeton hors ligne et crédite cinq recherches.
Protocole : [annexe — récompense par le navigateur](../specs/annexe-recompense-navigateur.md).

Le fournisseur de publicité est isolé derrière un adaptateur côté page web. Référence : Google Ad
Manager, publicité récompensée pour le web.

## Conséquences

**Favorables**

- L'invariant « zéro réseau » est intact, et devient un argument commercial : « même la publicité ne
  tourne pas dans l'application ».
- Aucun SDK tiers dans le processus qui lit l'écran ; aucune donnée d'appareil transmise par l'application.
- Le recueil du consentement publicitaire se fait sur la page web, pas dans l'application.

**Défavorables**

- Un composant à exploiter : page web, fonction de signature, domaine.
- La publicité récompensée sur le web n'offre pas de confirmation de serveur à serveur : la récompense
  est déclarée par la page. Fraude possible par un utilisateur technique (parades : anti-robot,
  limitation de débit, plafond quotidien).
- Revenus et taux de remplissage incertains, en général inférieurs à ceux d'un SDK mobile ; l'accès à
  la régie suppose un site réel et une approbation (risque R-02).
- Deux taps de plus dans le parcours (aller et retour du navigateur).
- Sans connexion, pas de récompense (le reste de l'application fonctionne).

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| **SDK publicitaire dans l'application** (AdMob récompensé) | Le plus simple et le mieux rémunéré, avec vérification côté serveur. Mais il impose `INTERNET`, donc détruit la garantie vérifiable de l'ADR-0001, alourdit la déclaration « Sécurité des données » et fragilise le dossier d'accessibilité. **C'est l'option de repli** si le porteur privilégie le revenu : elle se limite à remplacer `RewardFlow` par un fournisseur SDK, au prix d'un nouvel ADR remplaçant celui-ci et l'ADR-0001. |
| **Application compagnon** qui affiche les publicités et transmet des crédits | Deux applications à installer ; une application dont l'objet principal est d'afficher des publicités contrevient aux règles de Google Play. |
| **Environnement d'exécution isolé pour SDK** (Privacy Sandbox) | Technologie en cours de retrait par Google ; non pérenne. |
| **Pas de publicité, formule payante seule** | Contraire au modèle économique demandé pour la v1. |
