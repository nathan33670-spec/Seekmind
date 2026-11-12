# Prototype S2 — ML Kit sans télémétrie

| | |
|---|---|
| **Ticket** | [M0-07](../plan/M0-fondations.md#m0-07--prototype-s2--ml-kit-sans-télémétrie) |
| **Date** | 12 novembre 2026 |
| **Code** | `engine/ocr/src/main/…/MlKitOcrEngine.kt` · `app/src/androidTest/…/prototype/MlKitTelemetryPrototypeTest.kt` · `app/src/main/AndroidManifest.xml` · `app/src/offline/AndroidManifest.xml` |
| **Exécution** | GitHub Actions, émulateurs `google_apis` x86_64, API 30, 34 et 36 ; variantes `adsDebug` et `offlineDebug`, installation neuve à chaque exécution |
| **Résultat** | Option B retenue : 6 exécutions sur 6 vertes (3 niveaux d'API × 2 variantes), aucun octet émis |

## 1. Questions posées

1. Le modèle embarqué de ML Kit (`com.google.mlkit:text-recognition` 16.0.1) reconnaît-il 100 fois de
   suite un texte dessiné, sans plantage ni erreur fatale ?
2. Que fait sa télémétrie (`com.google.android.datatransport`) selon le réglage du manifeste :
   option A (permission `INTERNET` retirée en variante `offline` seulement) ou option B (composants de
   transport retirés dans les deux variantes) ?
3. La variante `offline` peut-elle se passer de `ACCESS_NETWORK_STATE` ?
4. Quel poids ML Kit ajoute-t-il à l'APK, par ABI ? Ses bibliothèques natives sont-elles alignées sur
   16 Ko ?

## 2. Méthode

Le test `MlKitTelemetryPrototypeTest`, exécuté dans le processus de l'application (variantes `ads` et
`offline`) :

1. dessine sur un bitmap de 1 080 × 400 px, en noir sur blanc, la ligne repère `SEEKMIND OCR 2026` et une
   seconde ligne ; lance 100 reconnaissances par `MlKitOcrEngine` ;
2. attend 10 s, relève les tâches planifiées du paquet (`dumpsys jobscheduler`) et les force à
   s'exécuter (`cmd jobscheduler run -f`), puis attend 15 s de plus ;
3. mesure les octets émis par l'UID de l'application (`TrafficStats.getUidTxBytes`, avant et après) ;
4. relève les lignes d'erreur du journal système écrites par son processus (étiquettes seulement) et le
   tampon `crash`.

Il échoue si une reconnaissance manque le repère, si le tampon `crash` mentionne l'application, si le
système ne compte pas le trafic de l'UID, ou si la variante qui détient `INTERNET` a émis un seul octet.

Les deux options ont été mesurées sur deux commits successifs de la même branche : option A sur
`2905d58` (exécution 43216421398), option B sur la tête suivante (exécution 43219487956). L'option A n'a
pas de mesure en API 30 : le job a échoué avant le test, sur une indisponibilité passagère du dépôt de
plugins Gradle.

## 3. Mesures

### 3.1 Option A — permission retirée en `offline` seulement

| Mesure | API 34 `ads` | API 34 `offline` | API 36 `ads` | API 36 `offline` |
|---|---|---|---|---|
| Reconnaissances correctes | 100/100 | 100/100 | 100/100 | 100/100 |
| **Octets émis** | **2 161** | 0 | **2 019** | 0 |
| Tâches planifiées après 10 s | 0 | 0 | 0 | 0 |
| Lignes d'erreur du processus | 0 | 40 (`TransportRuntime.Executor`) | 1 (`ashmem`) | 41 (`TransportRuntime.Executor`, `ashmem`) |
| Plantages | 0 | 0 | 0 | 0 |

### 3.2 Option B — composants de transport retirés dans les deux variantes

| Mesure | API 30 `ads` | API 30 `offline` | API 34 `ads` | API 34 `offline` | API 36 `ads` | API 36 `offline` |
|---|---|---|---|---|---|---|
| Reconnaissances correctes | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 | 100/100 |
| **Octets émis** | **0** | 0 | **0** | 0 | **0** | 0 |
| Tâches planifiées après 10 s | 0 | 0 | 0 | 0 | 0 | 0 |
| Lignes d'erreur du processus | 0 | 0 | 0 | 0 | 1 (`ashmem`) | 1 (`ashmem`) |
| Plantages | 0 | 0 | 0 | 0 | 0 | 0 |
| Première reconnaissance | 3,7 s | 3,8 s | 4,0 s | 4,1 s | 2,9 s | 2,4 s |
| Reconnaissance, médiane | 3,3 s | 3,3 s | 3,5 s | 3,5 s | 2,0 s | 2,1 s |

La ligne `ashmem` vient d'une bibliothèque du système en API 36 et apparaît sous les deux options ; elle
ne concerne pas ML Kit.

Les durées sont celles d'un émulateur x86_64 sans accélération graphique, sur un runner partagé : elles
ne disent rien d'un téléphone. Elles se mesureront sur appareil (validation manuelle, puis M3-04).

### 3.3 Poids et alignement

APK `adsRelease`, avant et après l'ajout de ML Kit (le code de l'application n'appelle pas encore le
moteur ; R8 garde néanmoins une partie de ML Kit par ses règles de conservation).

| Élément | Taille |
|---|---|
| `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so` | 11,06 Mo (non compressé dans l'APK) |
| `lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so` | 6,78 Mo |
| Modèles (`assets/mlkit-google-ocr-models/`) | 1,49 Mo (1,27 Mo compressés) |
| `classes.dex` | + 0,55 Mo |
| **Ajout par ABI** (bundle découpé par ABI) | **≈ 13,1 Mo en arm64-v8a ; ≈ 8,8 Mo en armeabi-v7a** |
| APK universel non signé | 1,2 Mo → 21,2 Mo |

| Bibliothèque | Alignement des segments `LOAD` |
|---|---|
| arm64-v8a, x86_64 | 2¹⁴ (16 Ko) |
| armeabi-v7a, x86 | 2¹² (4 Ko) |

## 4. Constats

1. **ML Kit envoie bien des statistiques d'usage.** Avec l'option A, la variante `ads` émet environ 2 Ko
   après 100 reconnaissances. Aucune tâche n'est encore planifiée 10 s après : l'envoi part sans attendre
   le planificateur. Le retrait de la seule permission ne protège donc que la variante `offline`, et il y
   laisse une quarantaine d'erreurs `TransportRuntime.Executor` (envois refusés) dans le journal.
2. **L'option B coupe la télémétrie à la source**, dans les deux variantes. Sans
   `TransportBackendDiscovery`, aucun service d'envoi n'est enregistré : les événements sont écartés
   avant d'être stockés ou planifiés. Aucun octet, aucune erreur, aucun plantage, sur les trois niveaux
   d'API. La reconnaissance n'est pas affectée.
3. **`ACCESS_NETWORK_STATE` est retirée de la variante `offline`** sans effet visible : avec l'option B,
   plus aucun composant restant ne s'en sert. WorkManager, qui la déclare aussi, n'est pas encore dans
   le build : il ne l'utilise que pour les contraintes de réseau, que les tâches de SeekMind n'emploient
   pas. Le ticket qui introduit WorkManager (M1-06) vérifie qu'aucune tâche n'emploie de contrainte de réseau.
4. **La dépendance « Play Services » est nécessaire et sans danger.** L'artefact embarqué tire
   `com.google.android.gms:play-services-mlkit-text-recognition`, qui porte l'API publique
   (`TextRecognizerOptions`) ; l'artefact embarqué fournit le module local (modèle et bibliothèque
   native), celui qui est chargé. La spec interdisait cet artefact par erreur : elle interdit désormais de
   le déclarer sans l'artefact embarqué, et de déclarer la méta-donnée qui fait télécharger le modèle.
5. **Alignement 16 Ko.** Les bibliothèques 64 bits sont alignées ; les 32 bits ne le sont pas. Google Play
   n'exige l'alignement que sur les appareils 64 bits, seuls à avoir des pages de 16 Ko : le garde-fou
   `scripts/check-elf-alignment.sh` refuse désormais une bibliothèque 64 bits mal alignée et signale
   seulement une bibliothèque 32 bits (test ajouté à `scripts/tests/test-guard-scripts.sh`).
6. **Poids.** ML Kit est la plus grosse dépendance de l'application : environ 13 Mo en arm64-v8a. Le
   modèle `fr` des embeddings (≈ 36 Mo) restera le premier poste.

## 5. Modifications apportées

| Fichier | Modification |
|---|---|
| `app/src/main/AndroidManifest.xml` | Option B : `tools:node="remove"` sur les trois composants de transport. `tools:ignore="MissingClass"` sur ces trois marqueurs seulement : leurs classes sont dans une dépendance d'exécution transitive de `:engine:ocr`, hors du classpath qu'inspecte lint. |
| `app/src/offline/AndroidManifest.xml` | Retrait de `ACCESS_NETWORK_STATE`. |
| `gradle/permission-allowlist-offline.txt` | `ACCESS_NETWORK_STATE` retirée : sa réapparition fait échouer `:app:verifyOfflineReleaseManifest`. |
| `gradle/dependency-allowlist-{ads,offline}.txt` | ML Kit et ses dépendances transitives (justification par ligne) ; groupes AndroidX tirés par les services Play. |
| `scripts/check-elf-alignment.sh` | Contrôle limité aux bibliothèques 64 bits (constat 5). |
| `docs/specs/ocr.md` §4 et §4.1 | Option retenue, statut de l'artefact Play Services, test rejoué à chaque montée de version de ML Kit. |
| `docs/03-architecture.md` §10.2, §10.3, §10.4 | Composants retirés, `ACCESS_NETWORK_STATE` interdite en `offline`, portée du contrôle d'alignement. |

## 6. Conclusion

**Option B retenue, dans les deux variantes.** ML Kit reconnaît le texte de façon fiable sans aucune
émission réseau. `ACCESS_NETWORK_STATE` est retirée de la variante `offline`. Le moteur de repli ONNX
(risque R-11) n'est pas nécessaire. Chaque montée de version de ML Kit rejoue `MlKitTelemetryPrototypeTest`
dans `device-tests`.

## 7. Validation manuelle (téléphone du porteur du projet)

À consigner dans la PR :

1. Désinstaller toute version précédente ; installer `app-ads-debug.apk` ; activer le mode avion.
2. Lancer 20 reconnaissances : `adb shell am instrument -w -e class
   fr.corsicajack.seekmind.prototype.MlKitTelemetryPrototypeTest
   fr.corsicajack.seekmind.debug.test/androidx.test.runner.AndroidJUnitRunner`, après avoir installé
   l'APK de test (`./gradlew :app:installAdsDebugAndroidTest`). Le test fait 100 reconnaissances ; en
   mode avion, il vérifie aussi qu'aucun octet ne part.
3. Relever `adb logcat -d -s SeekMindProto:I` : `ocr_correct`, `ocr_median_ms`, `tx_bytes`, `log_errors`.
