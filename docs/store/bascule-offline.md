# Bascule vers la variante `offline`

Marche à suivre si Google Play refuse la variante `ads` pour un motif lié à la publicité ou à l'accès
réseau ([ADR-0009, « Procédure de bascule »](../adr/0009-publicite-admob-et-variante-sans-reseau.md),
[conformité §8](../05-conformite-play-store.md#8-plan-de-repli)). La variante est construite, testée et
vérifiée à chaque changement : la bascule est une soumission, pas un développement.

## 1. Vérifier la variante

Sur le commit à soumettre, la CI doit être verte. En local :

```bash
./gradlew :app:verifyOfflineReleaseManifest :app:verifyDependencies verifyModuleGraph
./gradlew :app:bundleOfflineRelease
scripts/check-elf-alignment.sh
```

À contrôler :

- aucune permission `INTERNET` ni `ACCESS_NETWORK_STATE`, aucun identifiant publicitaire ;
- `engine:ads`, le SDK publicitaire et le SDK de consentement sont absents ;
- le test `OfflineVariantTest` est vert dans `device-tests`.

## 2. Ce que fait la variante

- **Formule gratuite** : le solde est complété chaque jour civil, à la première recherche, jusqu'à
  5 recherches non cumulables ([monetisation §4](../specs/monetisation.md)). La feuille de crédits ne propose
  pas de publicité, mais le code d'activation et la mention « 5 recherches offertes chaque jour ».
- **Formule activée** : code d'activation, collé ou par lien, comme dans la variante `ads`.
- **Divulgation** : le deuxième point de « Ce que deviennent ces données » devient « L'application n'a
  pas l'autorisation d'accéder à Internet. » ([conformité §4.4](../05-conformite-play-store.md#44-variante-offline)).
  La version de la divulgation reste la même : le texte est une ressource de la variante.

## 3. Textes à changer dans la Play Console

| Rubrique | Variante `ads` | Variante `offline` |
|---|---|---|
| Publicité (« Contient des annonces ») | Oui | **Non** |
| Identifiant publicitaire | Oui, pour la publicité | **Non**, retirer la déclaration |
| Sécurité des données | Données collectées et partagées par le SDK publicitaire ([conformité §5.2](../05-conformite-play-store.md#52-sécurité-des-données)) | **Aucune donnée collectée ni partagée** |
| Déclaration d'accessibilité | Inchangée (§5.1) | Inchangée. Préciser qu'il n'y a ni SDK publicitaire ni accès réseau. |
| Fiche : description | « Ce que SeekMind mémorise ne quitte jamais votre téléphone » | On peut alors écrire « sans accès à Internet » (§7) |
| Fiche : captures d'écran | Feuille de crédits avec publicité | Feuille de crédits sans publicité |
| `app-ads.txt` | Publié sur le site | Peut être retiré |

## 4. Textes à changer ailleurs

- **Politique de confidentialité** ([sécurité §7](../07-securite-vie-privee.md#7-politique-de-confidentialité)) : retirer la
  section sur le SDK publicitaire et le consentement ; indiquer que l'application n'a pas accès à Internet.
- **Site** : retirer les mentions de la publicité ; la page `a.html` des liens d'activation reste.
- **Vidéo de démonstration** (§6) : refaire la séquence de la feuille de crédits si elle montre une publicité.

## 5. Après la soumission

1. Consigner la bascule par un nouvel ADR (date, motif du refus, numéro de version soumis).
2. Si une publicité doit être rétablie sans réseau dans l'application, réaliser la récompense par le
   navigateur ([annexe](../specs/annexe-recompense-navigateur.md), ticket M8-07, aujourd'hui « Reporté »).
