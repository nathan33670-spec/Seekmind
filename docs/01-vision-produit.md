# 01 — Vision produit

| | |
|---|---|
| **Produit** | SeekMind (nom de travail du brief : ScreenMind) |
| **Plateforme** | Android 11 et plus (téléphones) |
| **Version du document** | 1.1 — 12 novembre 2026 (décisions du porteur intégrées) |

## 1. Le problème

Un utilisateur passe plus de quatre heures par jour devant son téléphone et y voit défiler des centaines
d'informations éphémères : le nom d'un restaurant dans un Reel, un livre cité sur Reddit, un code promo
dans une newsletter, une adresse reçue sur WhatsApp. Deux issues, toutes deux mauvaises :

- une galerie saturée de captures d'écran que personne ne retrouve ni ne trie ;
- la frustration de ne pas remettre la main sur une information vue trois jours plus tôt.

## 2. La proposition

**Un moteur de recherche sur sa propre mémoire d'écran.** SeekMind retient le texte qui passe à l'écran,
sans geste de l'utilisateur, et le restitue par une recherche en langage naturel :

> « le resto italien avec des pâtes à la truffe vu mardi soir »
> « le code promo de la newsletter Nike »
> « l'adresse que Julie m'a envoyée »

Le tout sans compte, et sans que rien de ce qui est mémorisé ne quitte le téléphone.

## 3. Pourquoi Android, pourquoi maintenant

- **Sur iOS**, le cloisonnement des applications rend le produit irréalisable pour un éditeur tiers.
- **Côté Google**, Pixel Screenshots reste réservé aux Pixel récents et exige une capture manuelle.
- **Android** fournit les trois briques nécessaires à un éditeur tiers :
  1. l'**API d'accessibilité**, qui permet — avec consentement explicite — de lire le texte affiché, pour
     un coût CPU faible et sans stocker d'images ;
  2. l'**IA embarquée** : OCR et modèles d'embeddings tournent désormais sur un téléphone de milieu de gamme ;
  3. les **Intents et liens profonds**, qui permettent de rouvrir la page ou l'application d'origine.

## 4. Les quatre piliers

| # | Pilier | Promesse utilisateur |
|---|---|---|
| 1 | **Recherche en langage naturel** | Pas besoin du mot exact : la recherche comprend l'intention, la date (« mardi soir ») et l'application (« sur Instagram »). |
| 2 | **Retour à la source** | Chaque souvenir garde un lien vers la page ou l'application d'origine. |
| 3 | **Bouclier de confidentialité** | Ce que SeekMind mémorise ne quitte jamais le téléphone. Mots de passe, navigation privée, applications bancaires et fenêtres sécurisées ne sont jamais indexés. L'utilisateur choisit dès l'installation les familles d'applications à mémoriser. |
| 4 | **Actions contextuelles** — *après la première publication* | Une date, une adresse ou un numéro de suivi passe à l'écran : une pastille discrète propose de l'envoyer vers l'agenda, la carte ou le suivi de colis, en un tap. |

Le bouclier de confidentialité est l'argument de confiance n° 1 **et** le principal levier de validation
par Google Play ; il est traité comme un invariant d'architecture, pas comme une fonctionnalité.

Le brief initial le formulait « Zéro Cloud : l'application est techniquement incapable de se connecter à
Internet ». Ce n'est plus vrai de la version publiée, qui intègre la publicité : son accès réseau est
réservé au module publicitaire, qui ne voit aucune donnée. La formulation forte reste celle de la
variante `offline`, maintenue comme solution de repli ([ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md)).

## 5. Cibles

| Persona | Besoin dominant | Requête typique |
|---|---|---|
| **Le « scrolleur »** (18–30 ans, réseaux sociaux) | Retrouver un lieu, un produit, un compte vu en vidéo | « la veste beige vue sur TikTok il y a 3 semaines » |
| **L'organisé débordé** (25–45 ans, actif) | Retrouver un code, une adresse, une référence, un rendez-vous | « numéro de suivi Colissimo » |
| **La personne en difficulté d'attention ou de mémoire** (TDAH, fatigue cognitive) | Une prothèse mémorielle fiable et privée | « ce que le médecin a écrit hier » |

Le troisième persona est un usage réel et assumé du produit. Il ne fait pas de SeekMind un « outil
d'accessibilité » au sens de Google Play : voir [05 — Conformité](05-conformite-play-store.md) §2.

## 6. Modèle économique

### 6.1 Version 1 (périmètre de ce cahier des charges)

| Formule | Contenu |
|---|---|
| **Gratuite** | Toutes les fonctionnalités. Les recherches consomment des crédits : **1 publicité récompensée regardée = 5 recherches**. Crédit de bienvenue à l'installation. |
| **Activée** | Un **code d'activation**, offert au lancement, supprime la publicité et rend les recherches illimitées. |

La publicité est une annonce récompensée AdMob, le seul format utilisé : elle n'apparaît que lorsque
l'utilisateur la demande, jamais à côté de ses données, et n'est jamais choisie en fonction de ce que
SeekMind a lu — voir [ADR-0009](adr/0009-publicite-admob-et-variante-sans-reseau.md).

### 6.2 Après la v1 (hors périmètre, pour mémoire)

Formule **Pro** (3,99 €/mois ou 39 € à vie dans le brief initial) : mémoire sur plusieurs années, recherche
dans les pièces jointes et PDF, export Notion/Obsidian, sauvegarde chiffrée `.smind` choisie par
l'utilisateur via le sélecteur de fichiers Android, achat intégré Google Play.

Marché de lancement : Europe francophone et pays voisins ; interface en français et en anglais.

## 7. Acquisition

Le produit se démontre en dix secondes de vidéo verticale : « je retrouve un vêtement aperçu il y a
trois semaines sur TikTok ». Conséquences sur le produit :

- la recherche doit donner un bon résultat dès la première requête, y compris floue ;
- le résultat doit s'ouvrir sur la source en un tap ;
- l'installation et l'activation de l'accessibilité doivent tenir en moins d'une minute.

## 8. Ce que le produit ne fait pas

- Il n'enregistre ni l'écran en vidéo, ni l'audio, ni les frappes au clavier, ni les notifications.
- Il n'agit jamais à la place de l'utilisateur dans d'autres applications.
- Il ne synchronise rien et ne possède aucun serveur recevant des données utilisateur.
- Il n'affiche ni bannière ni publicité imposée, et ne transmet rien au réseau publicitaire de ce qu'il a lu.
- Il n'est pas un outil de surveillance d'un tiers : il est visible, se met en pause en un tap, et
  s'ouvre par défaut derrière le verrouillage biométrique.

## 9. Feuille de route

| Étape | Contenu |
|---|---|
| **v1.0** | Ce cahier des charges : jalons M0 à M8, M10 et M11. |
| v1.1 | **Actions contextuelles** (jalon M9), widget de recherche. |
| v1.2 | Packs par langue plus légers, moteur vectoriel indexé si le volume l'exige. |
| v2.0 | Formule Pro : rétention longue, PDF et pièces jointes, exports, sauvegarde chiffrée, achat Google Play. |
