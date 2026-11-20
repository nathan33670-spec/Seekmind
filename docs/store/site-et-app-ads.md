# Site, politique de confidentialité et `app-ads.txt` (ticket M11-02)

## Le site

Le dossier `site/` contient des pages statiques, sans script, à l'exception de la page de relais des
liens d'activation (`a.html`, M8-05), et sans aucune ressource externe :

| Page | Contenu |
|---|---|
| `index.html` | Accueil, en français et en anglais |
| `faq.html`, `faq-en.html` | Questions fréquentes |
| `assistance.html` | Contact, en français et en anglais |
| `confidentialite.html`, `privacy.html` | Politique de confidentialité ([sécurité §7](../07-securite-vie-privee.md#7-politique-de-confidentialité)) |
| `a.html` | Relais des liens d'activation (M8-05) |

`scripts/check-site.sh site` vérifie à chaque PR (CI) que chaque page déclare la politique de sécurité
stricte, ne charge rien d'ailleurs et n'a pas de script. Avec `--release`, il refuse aussi les
emplacements restés à compléter, surlignés en jaune dans les pages.

**Emplacements à compléter par le porteur** avant la publication (M11-06) : nom de l'éditeur, adresse
e-mail d'assistance, date d'entrée en vigueur, droit applicable et autorité de contrôle. Ces deux derniers
points sont fixés après la relecture juridique.

Les chemins des réglages cités dans la FAQ et dans la politique (Réglages › Applications, Réglages ›
Données) sont ceux de [ui-ux §3.5](../specs/ui-ux.md). Ils sont à revérifier dans l'application, une fois
M6-06 fait, lors de la recette (M11-06).

## Publication sur GitHub Pages (porteur)

1. Si le dépôt reste privé sur une offre gratuite, publier `site/` depuis un dépôt public dédié
   ([prérequis §2.1](../04-prerequis.md#21-site-sur-github-pages)).
2. Dans le dépôt : Settings › Pages › Source : **GitHub Actions**.
3. Settings › Secrets and variables › Actions › Variables : créer `PAGES_ENABLED` = `true`. Tant que
   cette variable manque, le workflow `pages` ne fait rien, si bien qu'un push sur `main` n'échoue pas.
4. Lancer le workflow `pages` (Actions › pages › Run workflow), puis vérifier l'adresse de la politique.
   Cette adresse va dans la fiche Play et dans le build (paramètre de build de l'URL de la politique).

## `app-ads.txt` (porteur)

AdMob cherche ce fichier à la **racine** du site du développeur déclaré dans la fiche Google Play. Le
site de projet (`https://<compte>.github.io/Seekmind/`) ne convient pas : il faut le site de compte
(dépôt `<compte>.github.io`) ou un domaine personnalisé.

1. Copier `docs/store/app-ads.txt.template` et remplacer `pub-0000000000000000` par l'identifiant
   d'éditeur AdMob (AdMob › Paramètres › Informations sur le compte). `f08c47fec0942fa0` est
   l'identifiant de certification de Google, toujours le même.
2. Placer le fichier sous le nom `app-ads.txt` à la racine du site de compte.
3. Déclarer l'adresse de ce site dans la fiche Google Play (« Site Web »).
4. Dans AdMob › Applications › `app-ads.txt`, lancer la vérification. Elle peut prendre 24 h.
