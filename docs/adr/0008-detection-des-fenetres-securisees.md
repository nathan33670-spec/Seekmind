# ADR-0008 — Détection des fenêtres sécurisées par sonde

| | |
|---|---|
| **Statut** | Accepté |
| **Date** | 2026-11-12 |

## Contexte

Le brief demande d'exclure toute fenêtre portant `FLAG_SECURE` (applications bancaires, contenus
protégés, navigation privée). Or l'API d'accessibilité **n'expose pas** les attributs d'une fenêtre :
ni `AccessibilityWindowInfo` ni `AccessibilityNodeInfo` ne permettent de savoir qu'une fenêtre est
sécurisée, et l'arbre d'une telle fenêtre reste lisible. Le système, en revanche, exclut le contenu
sécurisé des captures d'écran.

## Décision

Utiliser la capture d'écran comme **sonde** :

| Version | Mécanisme | Fiabilité |
|---|---|---|
| Android 14+ (API 34) | `takeScreenshotOfWindow()` échoue avec `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`. Sonde à chaque capture. | Exacte |
| Android 11 à 13 (API 30–33) | `takeScreenshot()` rend une image où le contenu sécurisé est noirci : heuristique d'image noire. Sonde à chaque capture pour les navigateurs et messageries ; une fois par écran et par jour pour les autres applications. | Heuristique |

Une fenêtre détectée comme sécurisée entraîne le rejet de la capture entière, texte de l'arbre compris.
Détail : [specs/ocr §3](../specs/ocr.md).

La sonde complète les autres couches (liste d'applications sensibles, champs de mot de passe, marqueurs
de navigation privée) ; elle ne les remplace pas.

## Conséquences

- La promesse « les fenêtres sécurisées ne sont pas indexées » est tenue exactement à partir d'Android
  14, et au mieux sur Android 11 à 13 : cette nuance figure dans la FAQ et dans les critères d'acceptation.
- Coût : une capture d'écran en mémoire par capture de texte sur Android 14+. Sans OCR, le tampon est
  fermé sans copie. Le coût réel est mesuré au prototype M0-08 et dans la campagne batterie M10-01 ; s'il
  est excessif, repli sur la politique « par écran et par jour » pour les applications hors liste.
- Un écran réellement noir est traité comme sécurisé sur Android 11 à 13 : erreur sans conséquence.
- La même image sert à l'OCR quand il est nécessaire : pas de double capture.

## Alternatives écartées

- **Liste d'applications seule** : ne couvre ni les applications inconnues ni les écrans sécurisés
  ponctuels (onglet privé d'un navigateur par ailleurs autorisé).
- **Ne rien faire sur Android 11 à 13** : laisserait la navigation privée dépendre des seuls marqueurs
  de l'arbre, fragiles.
