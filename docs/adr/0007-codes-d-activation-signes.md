# ADR-0007 — Codes d'activation : jetons signés vérifiés hors ligne

| | |
|---|---|
| **Statut** | Accepté — distribution gratuite confirmée par le porteur du projet le 12 novembre 2026 |
| **Date** | 2026-11-12 |

## Contexte

La v1 prévoit un code d'activation qui supprime la publicité. L'application ne fait aucun appel réseau
de son propre fait — le réseau est réservé au module publicitaire dans la variante `ads`, et absent de la
variante `offline` (ADR-0001, ADR-0009) : elle ne peut pas interroger un serveur de licences. Deux questions : comment vérifier un
code sans réseau, et comment le distribuer sans enfreindre les règles de Google Play.

## Décision

**Technique.** Un code est un jeton JWS ES256 (ECDSA P-256) signé par une clé privée que l'éditeur
conserve hors ligne. L'application embarque la clé publique et vérifie la signature localement.
ES256 est disponible dans la plateforme Android à tous les niveaux d'API visés, dans WebCrypto et dans
les bibliothèques courantes : aucune dépendance cryptographique n'est ajoutée. Le code se colle ou
s'ouvre par un lien ; il ne se tape pas (environ 230 caractères).

**Distribution.** Au lancement, les codes sont **offerts** (testeurs, partenaires, presse, opérations
promotionnelles). Aucune vente hors de Google Play n'est engagée sans vérification de conformité.

## Conséquences

- Un secret embarqué dans l'application aurait permis des codes courts, mais aussi un générateur de
  codes pirate : la signature asymétrique l'exclut.
- Un code n'est pas lié à un appareil et ne peut pas être révoqué instantanément : la révocation passe
  par une liste embarquée, mise à jour avec l'application.
- La clé privée de licence est un actif critique ([04 §3](../04-prerequis.md#3-clés-et-secrets)).
- **Risque de conformité (R-03).** La politique de paiement de Google Play impose son système de
  facturation pour le déblocage de fonctionnalités, y compris une version sans publicité, sauf dans les
  cadres qu'elle prévoit (programmes d'offres externes ou de choix de facturation selon les pays).
  Vendre des codes en dehors de ces cadres expose au retrait de l'application.
- La voie pérenne pour vendre est l'achat intégré Google Play (v2). La bibliothèque de facturation ne
  déclare que la permission `BILLING` ; elle tire en revanche la même dépendance de transport que ML Kit,
  à neutraliser de la même façon.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| Code court vérifié par un secret embarqué (HMAC tronqué) | Secret extractible de l'APK : générateur de codes possible. |
| Code court validé par un serveur via le navigateur, qui renvoie un jeton lié à l'appareil | Bonne solution (codes saisissables, limite d'activations, révocation) mais demande un service avec état. Hors périmètre de la v1 ; le vérificateur de l'application est déjà compatible. |
| Achat intégré Google Play dès la v1 | Hors du périmètre demandé pour la v1 ; prévu en v2. |
| Ed25519 | Disponibilité inégale dans la plateforme selon les versions d'Android ; ES256 est universel. |
