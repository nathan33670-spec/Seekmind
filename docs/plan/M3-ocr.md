# M3 — OCR et fenêtres sécurisées

**Objectif.** Lire le texte des images et des vidéos sans jamais écrire d'image, et rejeter les fenêtres
sécurisées.

**Sortie du jalon.** Scénarios E2E-03, 05 et 06 verts ; garde-fou INV-03 actif.

---

### M3-01 — Capture d'écran en mémoire

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M2-01 | `engine:ocr` | [ocr §2](../specs/ocr.md) · INV-03 |

**À faire**

1. `ScreenshotSource` et `A11yScreenshotSource` : appel selon le niveau d'API, conversion en bitmap
   logiciel, recadrage, réduction, libération dans `finally`.
2. Limiteur d'intervalle entre deux demandes ; correspondance des codes d'erreur du système vers `Shot`.
3. Ajouter au script de garde-fous les motifs interdits dans `engine/ocr` et `engine/capture`.

**Critères d'acceptation**

- [ ] Le bitmap n'est accessible que pendant `block` ; il est recyclé même si `block` échoue.
- [ ] Deux demandes rapprochées → `RateLimited`, sans appel au système.
- [ ] Après 200 captures consécutives, la mémoire native du processus ne croît pas au-delà de 10 Mo.

---

### M3-02 — Sonde de fenêtre sécurisée

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3-01, M1-05 | `engine:ocr` | [ocr §3](../specs/ocr.md) · [ADR-0008](../adr/0008-detection-des-fenetres-securisees.md) · EF-PRIV-02, EF-PRIV-03 |

**À faire**

1. `SecureWindowProbe` : politique par niveau d'API ; heuristique d'image noire ; cache `secure_probe`.
2. Liste `probeAlwaysPackages` : navigateurs de `BrowserProfiles` et messageries à contenu éphémère.
3. Traitement du résultat `Unknown`.

**Critères d'acceptation**

- [ ] API 34+ : `SecureActivity` → `Secure` ; `PlainTextActivity` → `NotSecure`.
- [ ] API 30 : `SecureActivity` → `Secure` par l'heuristique ; un écran clair → `NotSecure`.
- [ ] Heuristique testée sur des bitmaps de synthèse : noir, presque noir, sombre avec texte, clair.
- [ ] Cache : pas de seconde sonde dans les 24 h pour une application hors liste (API 30–33) ; toujours une sonde pour un navigateur.

**Validation manuelle** — onglet privé de Chrome et de Firefox sur le téléphone de test (Android 14+) :
aucune capture ; résultat dans la PR. Le cas Android 11 à 13 n'est vérifiable que sur émulateur à ce
stade ; il est rejoué par les testeurs du test fermé (M11-05, risque R-14).

---

### M3-03 — Reconnaissance de texte

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3-01, M0-07 | `engine:ocr` | [ocr §4, §7](../specs/ocr.md) · EF-OCR-01, EF-OCR-04 |

**À faire**

1. `MlKitOcrEngine` (reprend le prototype S2, avec l'option retenue par son rapport) : un seul
   reconnaisseur, adaptation de la `Task`, filtrage des lignes.
2. Fusion des lignes d'OCR avec celles de l'arbre.

**Critères d'acceptation**

- [ ] Trois images de synthèse (texte clair sur fond sombre, sombre sur clair, petit corps) : texte reconnu.
- [ ] Fusion : une ligne déjà présente dans l'arbre n'est pas ajoutée ; une ligne nouvelle l'est ; l'origine OCR est marquée.

---

### M3-04 — Politique d'OCR et gouverneur

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3-03 | `engine:ocr` | [ocr §5, §6](../specs/ocr.md) · EF-OCR-03 · ENF-BAT-01 |

**À faire**

1. `OcrPolicy` : besoin, mémo des écrans déjà lus, budgets.
2. `ThermalGovernor` : état thermique, économie d'énergie, batterie, charge ; `StateFlow` partagé.

**Critères d'acceptation**

- [ ] Table de décision de `OcrPolicy` ; budgets par intervalle, par minute et par jour (horloge simulée).
- [ ] Le gouverneur suspend l'OCR et les embeddings pour chacun des trois signaux, et les rétablit.

**Validation manuelle** — sur dix applications (dont Instagram, TikTok, YouTube, un jeu, une application
Flutter) : relever quand l'OCR se déclenche et ce qu'il apporte ; ajuster les seuils de `OcrConfig` ;
consigner le tableau dans la PR.

---

### M3-05 — Intégration au pipeline et tests de bout en bout

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M3-02, M3-04, M2-06 | `engine:capture`, `app`, `fixtureapp` | [capture §6](../specs/capture.md) · [tests §4](../06-qualite-tests-ci.md#4-tests-de-bout-en-bout) |

**À faire**

1. Brancher la sonde et l'OCR à l'étape 6 du pipeline : une seule capture d'écran pour les deux.
2. Scénarios E2E-03, 05 et 06.

**Critères d'acceptation**

- [ ] E2E-03 : `SecureActivity` → `Dropped(SECURE_WINDOW)`, sur les trois niveaux d'API.
- [ ] E2E-05 : le texte de `ImageTextActivity` est stocké avec l'origine OCR et retrouvé par la recherche.
- [ ] E2E-06 : aucun fichier créé hors des fichiers de la base.
- [ ] Gouverneur en état « suspendu » : aucun OCR, la capture du texte de l'arbre continue.
