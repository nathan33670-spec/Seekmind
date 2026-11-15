# ADR-0012 — Composants exportés de Play Asset Delivery

| | |
|---|---|
| **Statut** | Accepté — décision du porteur du projet (15 novembre 2026, option 1) |
| **Date** | 2026-11-15 |
| **Concerne** | INV-12, [embeddings §5.2](../specs/embeddings.md), [ADR-0010](0010-modele-francais-et-packs-de-langue.md), ticket M4-07 |

## Contexte

L'ADR-0010 fait livrer le pack `multi` par Google Play, en pack d'assets à la demande. La bibliothèque
`com.google.android.play:asset-delivery` 2.3.0 ajoute au manifeste trois composants :

| Composant | `exported` | `enabled` | Permission |
|---|---|---|---|
| `AssetPackExtractionService` (service) | `true` | `false` | aucune |
| `SessionStateBroadcastReceiver` (récepteur) | `true` | `true` | `android.permission.INSTALL_PACKAGES` |
| `ExtractionForegroundService` (service) | `false` | `false` | — |

Ce sont les canaux par lesquels le Play Store parle à l'application. INV-12 n'admet un composant exporté
de bibliothèque que s'il est protégé par une permission système. Le récepteur l'est :
`INSTALL_PACKAGES` est une permission `signature|privileged`, qu'aucune application tierce n'obtient. Le
service ne l'est pas.

## Décision

1. Les deux composants sont admis dans `gradle/exported-allowlist.txt`. `AssetPackExtractionService` y
   figure sans permission : c'est une exception explicite à INV-12, limitée à ce composant.
2. Le manifeste fusionné n'est pas modifié (pas de `tools:node="remove"`) : sans ces composants, le
   fonctionnement de Play Asset Delivery n'est plus garanti.
3. Ce qui arrive par ce canal n'est jamais cru sur parole : avant toute activation, le catalogue vérifie
   la taille et le SHA-256 de `model.onnx` et de `vocab.bin` contre `model.lock.json`
   ([embeddings §5.2](../specs/embeddings.md)). Un fichier altéré rend le pack inutilisable, sans autre effet.
4. Play Asset Delivery n'est appelé que si Google Play a installé l'application
   (`InstallSourceInfo.installingPackageName` = `com.android.vending`). Sinon, `FileModelPackInstaller` sert.

## Conséquences

- Le service est déclaré désactivé ; seule l'application elle-même (ici, la bibliothèque) peut l'activer.
  Une fois actif, une application tierce pourrait tenter de s'y lier. Le pire cas est un pack altéré,
  que la vérification des empreintes refuse.
- La bibliothèque ne déclare aucune permission réseau ; le Play Store télécharge. INV-01 et la variante
  `offline` ne sont pas touchés. Elle ajoute `FOREGROUND_SERVICE_DATA_SYNC`, déjà admise.
- Toute nouvelle version de la bibliothèque qui changerait ces composants fait échouer
  `:app:verify*ReleaseManifest`, et la question revient au porteur.

## Alternatives écartées

- **Retirer les composants du manifeste** : fonctionnement non garanti, vérifiable seulement sur la piste
  de test de Play.
- **Pas de Play pour la v1** (import de `.smpack` seul) : contredit EF-LANG-02 (« téléchargé par Google Play »).
