# ADR-0010 — Modèle français embarqué et packs de langue téléchargeables

| | |
|---|---|
| **Statut** | Accepté — décision du porteur du projet |
| **Date** | 2026-11-12 |
| **Remplace** | [ADR-0006](0006-modele-d-embeddings.md) pour le choix du modèle embarqué (son analyse reste valable) |

## Contexte

L'ADR-0006 retenait le modèle multilingue `multilingual-e5-small` (118 Mo), soit une application
d'environ 150 Mo. Le porteur du projet a demandé le 12 novembre 2026 : **le français d'abord, avec la
possibilité pour l'utilisateur de télécharger sa langue**.

L'essentiel du poids d'un modèle multilingue est son vocabulaire (250 002 jetons). Une variante du même
modèle au vocabulaire réduit au français existe : `antoinelouis/french-me5-small` — 37 200 jetons,
mêmes 12 couches et 384 dimensions, licence MIT, 144 Mo en flottants, soit environ 36 Mo une fois
quantifiée (vérifié sur le dépôt des modèles le 12 novembre 2026).

## Décision

### 1. Un catalogue de modèles

| Identifiant | Modèle | Langues | Taille | Livraison |
|---|---|---|---|---|
| `fr` | `antoinelouis/french-me5-small`, révision `1605fe3b64cb22dde93f30e8fbfb1889742d2753` | Français | ~36 Mo | **Embarqué** dans l'application |
| `multi` | `intfloat/multilingual-e5-small`, révision `614241f622f53c4eeff9890bdc4f31cfecc418b3` | Une centaine de langues | ~118 Mo | **Pack de langue** téléchargé à la demande |

Les deux modèles partagent l'architecture, la dimension des vecteurs, les préfixes et l'algorithme de
tokenisation : le moteur, le tokenizer et l'index sont communs ; seuls changent le fichier du modèle et
son vocabulaire.

### 2. Téléchargement par Google Play, pas par l'application

Le pack `multi` est un **pack d'assets à la demande** (Play Asset Delivery). C'est le Play Store qui
télécharge ; l'application ne fait qu'en faire la demande par une API locale. La bibliothèque ne déclare
aucune permission réseau : le mécanisme fonctionne dans les deux variantes, y compris `offline`.

Hors Google Play (builds de développement, distribution alternative), un pack s'installe par
**import d'un fichier** choisi par l'utilisateur dans le sélecteur de fichiers.

### 3. Changer de modèle

Un seul modèle est actif à la fois. En changer invalide les vecteurs existants : ils sont recalculés en
arrière-plan, comme pour tout changement de modèle ([indexation §7.3](../specs/indexation.md)). La
recherche par mots-clés, indépendante du modèle, n'est jamais interrompue et fonctionne dans toutes les langues.

### 4. Proposer le pack au bon moment

Si la langue du téléphone n'est pas le français, l'application propose le pack multilingue à la fin de
l'accueil et dans les réglages. Elle ne le télécharge jamais d'elle-même.

## Conséquences

- Application d'environ 70 Mo au lieu de 150 Mo (cible ENF-STO-02 ramenée à 100 Mo).
- Avec le modèle `fr` seul, la recherche **par le sens** est fiable en français ; sur un contenu en
  anglais ou dans une autre langue, sa qualité est incertaine et doit être **mesurée** (ticket M5-07).
  L'interface le dit simplement : « Pour chercher par le sens dans d'autres langues, ajoutez le pack multilingue. »
- Le modèle `fr` est peu diffusé : sa qualité est vérifiée sur le jeu d'évaluation avant tout
  engagement (tickets M0-09, M5-07). S'il déçoit, le repli est un modèle au vocabulaire réduit par nos
  soins au français et à l'anglais à partir du modèle multilingue (ticket M10-07).
- L'outillage (`tools/model`) fabrique deux jeux d'artefacts ; le modèle `fr` n'est pas distribué au
  format ONNX et doit être exporté.
- Le téléchargement à la demande ne peut pas être testé en intégration continue (il exige le Play
  Store) : il est validé sur la piste de test interne ; l'import de fichier sert aux tests automatisés.
- Le catalogue permet d'ajouter plus tard des packs par langue (vocabulaire réduit à l'anglais, à
  l'espagnol, à l'allemand…), plus légers que le pack multilingue.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| Multilingue embarqué (ADR-0006) | Écarté par le porteur : poids de l'application. |
| Téléchargement par l'application depuis un serveur | Ajouterait un second usage du réseau et un hébergement, et serait impossible dans la variante `offline`. |
| Un pack par langue dès la v1 | Autant de modèles à fabriquer et à évaluer ; le pack multilingue couvre toutes les langues en attendant. |
| Répartition automatique par langue de l'appareil à l'installation | Possible avec les lots d'application, mais suppose un modèle par langue ; à reconsidérer avec les packs par langue. |
