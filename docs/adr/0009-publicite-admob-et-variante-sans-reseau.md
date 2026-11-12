# ADR-0009 — Publicité par le SDK AdMob, avec une variante sans réseau maintenue en continu

| | |
|---|---|
| **Statut** | Accepté — décision du porteur du projet |
| **Date** | 2026-11-12 |
| **Remplace** | [ADR-0002](0002-publicite-recompensee-hors-application.md) comme solution active |
| **Amende** | [ADR-0001](0001-zero-permission-internet.md) : le « zéro réseau » ne vaut plus que pour la variante `offline` |

## Contexte

La formule gratuite repose sur la publicité récompensée (une publicité = cinq recherches). L'ADR-0002
proposait de l'afficher dans le navigateur pour conserver une application sans aucune permission réseau.
Le porteur du projet a tranché le 12 novembre 2026 :

- la publicité est intégrée à l'application par le **SDK AdMob** : plus simple pour l'utilisateur,
  mieux rémunérée, sans page web ni service de signature à exploiter ;
- format **récompensé uniquement** ; personnalisation des annonces **selon le consentement** ;
- le raisonnement et la solution sans réseau sont **conservés**, pour pouvoir changer si Google Play
  refuse l'application ;
- la solution de repli n'est pas qu'un document : c'est une **variante compilée et testée à chaque
  changement**.

## Décision

### 1. Deux variantes du même code

Dimension de variante `network` :

| Variante | Destination | Réseau | Publicité |
|---|---|---|---|
| `ads` | Google Play (publication prévue) | Permission `INTERNET`, réservée au module publicitaire | SDK Google Mobile Ads, annonces récompensées |
| `offline` | Solution de repli, prête à soumettre | **Aucune permission `INTERNET`** (ADR-0001 intact) | Aucune ; crédits quotidiens et codes d'activation |

Tout le reste — capture, confidentialité, base, recherche, interface — est strictement identique.

### 2. Le réseau est cantonné

- Un seul module, `engine:ads`, contient le SDK publicitaire et le SDK de consentement. Il n'existe que
  dans la variante `ads`.
- `engine:ads` ne dépend ni de la base, ni d'aucun autre module `engine`. Son interface
  (`RewardProvider`) ne reçoit aucun paramètre capable de transporter une donnée de l'utilisateur :
  on lui demande d'afficher une annonce, il répond si la récompense est acquise.
- Aucune requête publicitaire ne porte de mot-clé, d'URL de contenu ni de ciblage issu de l'application.
- Partout ailleurs, les API réseau restent interdites, comme avant.
- **Silence réseau** : l'application n'émet rien tant que l'utilisateur n'a pas ouvert la feuille de
  crédits ou demandé une publicité. Le SDK n'est initialisé et le consentement publicitaire n'est
  demandé qu'à ce moment. Un test instrumenté le vérifie (compteurs de trafic de l'application à zéro).

### 3. Ce qui est dit à l'utilisateur change

La promesse n'est plus « l'application ne peut pas accéder à Internet » mais « **ce que SeekMind lit
sur votre écran ne quitte jamais votre téléphone** ; l'accès à Internet ne sert qu'à afficher les
publicités que vous choisissez de regarder ». Divulgation, fiche et politique de confidentialité sont
rédigées ainsi pour la variante `ads` ; la variante `offline` garde la formulation forte.

## Conséquences

**Favorables**

- Parcours publicitaire standard, taux de remplissage et revenus d'un SDK mobile.
- Aucun composant web à exploiter pour la publicité ; aucune clé de signature de récompense.
- La variante `offline` reste un produit complet et vérifié : la bascule est une soumission, pas un chantier.

**Défavorables**

- La garantie vérifiable de l'ADR-0001 disparaît de la version publiée : la confidentialité repose sur
  l'architecture (cantonnement, silence réseau) et sur l'engagement de l'éditeur, non sur l'absence de permission.
- Le SDK publicitaire s'exécute dans le même processus que le service qui lit l'écran.
- Le dossier Google Play est plus exposé (risque R-01) : accessibilité **et** publicité dans la même application.
- Déclarations supplémentaires : identifiant publicitaire, « Sécurité des données » (données collectées
  par le SDK), fenêtre de consentement en Europe, fichier `app-ads.txt`.
- Le SDK publicitaire embarque ses propres dépendances (WorkManager, WebView) : la liste blanche des
  dépendances et des permissions est tenue par variante.
- Deux variantes à compiler et à tester : intégration continue un peu plus longue.

## Procédure de bascule

À appliquer si Google Play refuse la variante `ads` pour un motif lié à la publicité ou à l'accès réseau.

1. **Soumettre la variante `offline`** telle quelle : elle est construite, testée et vérifiée sans
   permission `INTERNET` à chaque changement. Formule gratuite : crédits quotidiens ; formule activée : code.
2. Mettre à jour les textes : divulgation (formulation `offline`), fiche, politique de confidentialité,
   déclaration « Sécurité des données » (aucune donnée collectée), retrait de la déclaration d'identifiant publicitaire.
3. **Si** la publicité doit être rétablie sans réseau dans l'application : réaliser la récompense par
   le navigateur, décrite dans l'[annexe](../specs/annexe-recompense-navigateur.md) (jetons signés, page
   web, fournisseur `BrowserRewardProvider`). Elle se branche sur la même interface `RewardProvider`.
4. Consigner la bascule par un nouvel ADR.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| Publicité dans le navigateur seule (ADR-0002) | Écartée par le porteur : parcours moins direct, revenus plus incertains, composant web à exploiter. Reste la solution de repli. |
| SDK AdMob sans variante de repli | La bascule aurait demandé plusieurs jours de développement au pire moment (après un refus). |
| Isoler le SDK dans un processus dédié dès la v1 | Séduisant (le SDK ne partagerait pas le processus du service), mais le SDK planifie des travaux d'arrière-plan dans le processus principal ; non documenté par Google. Réévaluable en durcissement (ticket M10-02). |
| Bannières ou interstitiels | Écartés par le porteur : aucune annonce ne doit s'afficher à côté des données de l'utilisateur. |
