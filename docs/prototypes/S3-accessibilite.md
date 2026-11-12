# Prototype S3 — Service d'accessibilité, captures d'écran en mémoire, fenêtres sécurisées

| | |
|---|---|
| **Ticket** | [M0-08](../plan/M0-fondations.md#m0-08--prototype-s3--service-daccessibilité-application-de-test-ci-device-tests) |
| **Date** | 12 novembre 2026 |
| **Code** | `engine/capture/src/androidTest/…/prototype/` (service et tests, uniquement dans l'APK de test) · `fixtureapp/` · `.github/workflows/device-tests.yml` |
| **Exécution** | GitHub Actions, émulateurs `google_apis` x86_64, API 30, 34 et 36, rendu logiciel (pas de GPU), écran 320 × 640 |
| **Résultat** | 18 tests sur 18 verts (6 par niveau d'API) ; workflow en 6 min 30 |

## 1. Questions posées

1. Un service d'accessibilité configuré comme celui du produit lit-il le texte d'un écran tiers ?
2. Obtient-il une capture d'écran en mémoire, sans `MediaProjection`, sur Android 11 comme sur Android 14+ ?
3. Peut-il reconnaître une fenêtre `FLAG_SECURE`, que l'API d'accessibilité ne signale pas ?
4. Quel intervalle minimal le système impose-t-il entre deux captures ?

## 2. Mesures

| Mesure | API 30 | API 34 | API 36 |
|---|---|---|---|
| Lecture de l'arbre (écran de 4 nœuds, 178 caractères) | 0,05 ms | 0,08 ms | 0,02 ms |
| Capture d'écran (`takeScreenshot` / `takeScreenshotOfWindow`) | 6,9 ms | 21,5 ms | 23,5 ms |
| Copie en bitmap logiciel | 6,1 ms | 420 ms | 243 ms |
| Fenêtre sécurisée : résultat de la capture | image noire | erreur 6 | erreur 6 |
| Fenêtre sécurisée : part de pixels noirs (zone de contenu) | 100 % | — | — |
| Écran ordinaire : part de pixels noirs (zone de contenu) | 0 % | — | — |
| Intervalle minimal entre deux captures | 1 000 ms | 500 ms | 500 ms |
| Code d'erreur en deçà | 3 (intervalle trop court) | 3 | 3 |
| Texte d'une image visible dans l'arbre | 0 caractère | 0 | 0 |

Les mesures brutes sont dans les artefacts `device-tests-api*` du workflow (journal `SeekMindProto`).

**Limites.** Les durées viennent d'émulateurs sans GPU sur des machines partagées : seuls les ordres de
grandeur et les comportements comptent. La copie en bitmap logiciel est particulièrement lente en rendu
logiciel (240 à 420 ms) et doit être mesurée sur appareil. L'écran de test est trivial : la durée de
lecture de l'arbre d'applications réelles relève de la validation manuelle (§5).

## 3. Constats

1. **Lecture de l'arbre** : conforme. Le texte de l'écran de test est lu intégralement, accents compris.
2. **Capture d'écran en mémoire** : conforme sur les trois niveaux, sans boîte de dialogue ni service de
   premier plan. Le tampon matériel est fermé dans tous les cas.
3. **Le texte d'une fenêtre sécurisée reste lisible dans l'arbre.** Le test le vérifie : c'est la
   confirmation directe du contexte de l'[ADR-0008](../adr/0008-detection-des-fenetres-securisees.md).
   Sans sonde, SeekMind indexerait les fenêtres sécurisées.
4. **Sonde de fenêtre sécurisée** :
   - API 34 et 36 : `takeScreenshotOfWindow` échoue avec `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (6).
     Détection exacte, comme prévu.
   - API 30 : la capture réussit mais l'écran sécurisé est entièrement noir (100 % des échantillons sous
     8/255), tandis qu'un écran ordinaire n'a aucun échantillon noir. L'heuristique de la spec
     ([ocr §3.2](../specs/ocr.md)) sépare nettement les deux cas.
5. **Intervalle minimal** : 1 000 ms sous Android 11, 500 ms à partir d'Android 14. Le réglage
   `OcrConfig.screenshotMinIntervalMs = 1 000` convient aux deux ; il peut être abaissé à 500 ms sur
   API 34+ si la sonde systématique l'exige.
6. **Le texte dessiné dans une image n'apparaît pas dans l'arbre**, confirmant le besoin de l'OCR (risque R-08).

## 4. Conséquences pour la suite

| Sujet | Décision |
|---|---|
| ADR-0008 (sonde de fenêtre sécurisée) | **Confirmé** sur les deux branches (code d'erreur ; image noire). Aucun changement. |
| Coût de la sonde sur API 34+ | La sonde seule coûte une capture (≈ 20 ms en émulation), **sans copie** : la spec OCR §3.1 prévoit déjà de fermer le tampon sans copie quand l'OCR n'est pas nécessaire. La copie, coûteuse, est réservée à l'OCR. |
| Événements de fenêtre | Plusieurs `TYPE_WINDOW_STATE_CHANGED` suivent l'ouverture d'un écran, et le dernier peut nommer un conteneur de vues plutôt que l'activité. La spec capture §5.1 le prévoit déjà (nom retenu seulement si c'est une activité) ; précision ajoutée. |
| Tests de bout en bout | Ouvrir les écrans par `am start -W`, attendre l'événement de l'activité et le texte repère, jamais une durée ; activer le service une fois par classe de test. Ces règles valent pour le harnais définitif (M2-08). |
| Manifestes de test | Dans un APK de test de bibliothèque, déclarer les composants par leur nom complet : un nom relatif se résout dans le paquet de test. |

**Conclusion : la stratégie de capture est confirmée ; aucune décision d'architecture n'est à réviser.**

## 5. Validation manuelle (téléphone du porteur du projet)

À consigner dans la PR, d'après le ticket :

- [ ] Lecture de l'arbre de trois applications courantes (nombre de nœuds, durée).
- [ ] Sonde sur un onglet de navigation privée de Chrome : fenêtre reconnue comme sécurisée.
- [ ] Durée de la copie en bitmap logiciel sur appareil réel.

Mode opératoire : installer l'APK de test (`./gradlew :engine:capture:installDebugAndroidTest`) et
l'application de test, puis lancer `adb shell am instrument -w fr.corsicajack.seekmind.engine.capture.test/androidx.test.runner.AndroidJUnitRunner`.
Les tests d'applications réelles seront ajoutés au protocole par M2-08.
