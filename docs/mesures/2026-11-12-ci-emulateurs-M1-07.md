# Mesures — couche de données sur émulateurs (M1-07)

| | |
|---|---|
| **Date** | 12 novembre 2026 |
| **Ticket** | [M1-07](../plan/M1-donnees.md#m1-07--outils-de-test-et-premiers-bancs-de-mesure) |
| **Exécution** | GitHub Actions, `device-tests` n° 43297410624 (commit `347e73e`) |
| **Appareils** | Émulateurs `google_apis` x86_64, 2 cœurs, API 30, 34 et 36, rendu logiciel |
| **Conditions** | APK de test **debuggable**, non compilé à l'avance ; Microbenchmark avec les erreurs `EMULATOR`, `DEBUGGABLE`, `NOT-AOT-COMPILED`, `LOW-BATTERY`, `UNLOCKED`, `ENG-BUILD` neutralisées. Chiffres **indicatifs** : un téléphone en `release` est plus rapide. |
| **Sources** | Artefacts `device-tests-api{30,34,36}` : `benchmark/build/outputs/connected_android_test_additional_output/**/*-benchmarkData.json` et `build/device/measurements-api*.txt` (étiquette `SeekMindBench`) |

## 1. Générateur de base synthétique

| Mesure | API 30 | API 34 | API 36 | Cible |
|---|---|---|---|---|
| 100 000 captures (`insertAll`, lots de 1 000) | 31,9 s | 48,5 s | 27,4 s | < 2 min |
| 10 000 captures avec un fragment et un vecteur chacune | 4,0 s | 12,1 s | 4,5 s | |

## 2. Bancs

50 itérations mesurées par banc. Le p95 est calculé sur les 50 durées du JSON.

| Banc | API 30 (médiane / p95) | API 34 | API 36 | Cible |
|---|---|---|---|---|
| Insertion d'une capture (transaction complète : capture, FTS5, fragment), base de 10 000 captures — ENF-PERF-05 | 1,54 / 1,75 ms | 2,07 / 2,30 ms | 1,28 / 1,47 ms | ≤ 15 ms (p95) |
| Requête lexicale, un mot, 100 000 captures, 50 résultats — ENF-PERF-03 | 14,6 / 29,8 ms | 13,8 / 24,8 ms | 16,3 / 23,6 ms | ≤ 100 ms (p95) |
| Requête lexicale, deux mots | 10,8 / 14,3 ms | 10,7 / 17,9 ms | 9,2 / 12,8 ms | ≤ 100 ms (p95) |
| Requête lexicale, préfixe de 3 lettres | 15,4 / **112 ms** | 23,3 / 61,4 ms | 19,4 / 52,5 ms | ≤ 100 ms (p95) |

## 3. Constats

1. **Insertion** : environ 2 ms au p95, soit sept fois sous la cible.
2. **Requêtes par mot** : 13 à 30 ms au p95 sur 100 000 captures, avec la forme de requête retenue par le
   prototype S1. Le nombre de captures a été multiplié par 10 depuis S1, et le coût n'a été multiplié que
   par deux ou trois : il dépend surtout du nombre de correspondances.
3. **Préfixes courts : point à surveiller.** Le préfixe de 3 lettres dépasse la cible en API 30
   (112 ms au p95) et s'en approche en API 34. Un préfixe de 3 lettres parcourt tous les termes qui le
   prolongent. La recherche ajoute `*` au dernier jeton de 3 caractères ou plus
   ([recherche §3](../specs/recherche.md)) : c'est le cas réel le plus coûteux. Le ticket de la recherche
   lexicale (M5) mesure à nouveau ce cas sur téléphone. Si la cible n'y est pas tenue, l'option FTS5
   `prefix='3'` (index de préfixes, au prix d'un index plus gros) ou un préfixe minimal de 4 caractères
   sont les deux remèdes ; la spec stockage §3.2 écarte aujourd'hui l'index de préfixes.
4. **Générateur** : 27 à 48 s pour 100 000 captures, sous la limite de 2 minutes ; l'insertion par lots
   est 3 à 4 fois plus rapide qu'une transaction par capture.
