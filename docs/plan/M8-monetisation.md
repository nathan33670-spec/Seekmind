# M8 — Monétisation

**Objectif.** Formule gratuite à crédits (1 publicité = 5 recherches) et formule activée par code. La
publicité passe par le SDK AdMob dans la variante `ads` ; la variante `offline`, sans réseau, reste un
produit complet.

**Avant de commencer.** Liste « Prêt pour M8 » des [prérequis §8](../04-prerequis.md#8-listes-de-contrôle).
Tout le jalon se développe avec les identifiants publicitaires de test de Google et la clé de licence de
test : aucun compte n'est nécessaire pour écrire et tester le code.

**Sortie du jalon.** Scénario E2E-15 vert ; test de silence réseau vert ; parcours P3 et P4 du cahier des
charges réalisables ; variante `offline` vérifiée.

**Tous les tickets de ce jalon attendent l'accord du porteur du projet avant fusion** ([plan §1.4](README.md#14-revue-et-fusion)).

---

### M8-01 — Droits et registre de crédits

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-05 | `engine:monetization` | [monetisation §2, §4](../specs/monetisation.md) · EF-MON-01, 04, 07, 08, 11 |

**À faire**

1. `CreditLedger` : crédit de bienvenue à la création de la base, mouvements (`WELCOME`, `REWARD`,
   `COURTESY`, `DAILY`, `SEARCH`), solde, plafonds de solde, de récompenses et de courtoisie par jour civil.
2. Crédits quotidiens gratuits pilotés par `MonetizationConfig.dailyFreeCredits` (0 par défaut).
3. `EntitlementGate` : `state`, `authorize`, `commit`, selon le tableau de la spec.
4. Remplacer l'implémentation « tout autorisé » posée par M6-03.

**Critères d'acceptation**

- [ ] Un test par ligne du tableau « Règles » : activé, requête répétée sous 10 minutes, solde suffisant, solde nul.
- [ ] `commit` avec zéro résultat ne débite pas.
- [ ] Plafonds respectés (horloge simulée, changement de jour civil).
- [ ] `dailyFreeCredits = 5` : le solde est complété à 5 à la première recherche du jour, sans cumul ; à 0, rien.
- [ ] Le solde survit au redémarrage du processus.

---

### M8-02 — Vérification des jetons de licence

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-01 | `engine:monetization`, `tools/license`, `testdata/tokens` | [monetisation §5.1, §5.2](../specs/monetisation.md) · [prérequis §3](../04-prerequis.md#3-clés-et-secrets) |

**À faire**

1. `tools/license/seekmind_keys.py` (génération de paires ES256) et `gen_test_vectors.py` ; clé de test
   committée dans les sources `debug` et `test` ; `testdata/tokens/` (valides et invalides).
2. `TokenVerifier` : contrôles de la spec §5.2, conversion de signature, trousseau par usage.
3. `keys-release.json` avec un emplacement pour la clé de production ; `keys-debug.json` réservé au build `debug`.

**Critères d'acceptation**

- [ ] Chaque vecteur invalide est refusé pour la bonne raison : signature altérée, charge altérée, `alg`
      autre qu'ES256 (dont `none`), `kid` inconnu, usage inattendu, longueur excessive, base64 invalide.
- [ ] Le build `release` refuse un jeton signé par la clé de test (test dédié).
- [ ] Aucune clé privée de production dans le dépôt ; la clé de test est nommée comme telle.

---

### M8-03 — Module publicitaire

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M1-01, M0-04 | `engine:ads`, `app` (variante `ads`) | [monetisation §3](../specs/monetisation.md) · [architecture §3.2, §10.2 à §10.4](../03-architecture.md) · [ADR-0009](../adr/0009-publicite-admob-et-variante-sans-reseau.md) · EF-MON-02, 09, 10 · INV-01 |

**À faire**

1. Dépendances `play-services-ads` et `user-messaging-platform` dans `engine:ads` uniquement ; listes
   blanches de dépendances et de permissions de la variante `ads` mises à jour.
2. `AdMobRewardProvider` (implémente `RewardProvider`) : consentement, initialisation paresseuse,
   chargement avec délai maximal, affichage, correspondance des issues et des codes d'erreur.
3. Complément de manifeste de la variante `ads` : identifiant d'application AdMob (valeur de test en
   `debug`, propriété de build en `release`), mesure d'audience retardée, permissions retirées.
4. Règles de confidentialité de la spec §3.3 : requête nue, classification, aucun appel aux API de
   ciblage ; motifs correspondants ajoutés à `gradle/forbidden-apis.txt` pour `engine/ads`.
5. Choix de confidentialité publicitaire (`privacyOptionsRequired`, `showPrivacyOptions`).
6. **Silence réseau** : vérifier ce que fait le composant d'initialisation automatique du SDK au
   démarrage ; le neutraliser s'il émet quoi que ce soit ; écrire `NetworkSilenceTest`.
7. Liaison Hilt de `RewardProvider` dans `app/src/ads/`.

**Critères d'acceptation**

- [ ] `engine:ads` ne dépend que de `core:common` et `core:model` (garde-fou) ; son interface publique ne
      comporte aucun paramètre de donnée utilisateur.
- [ ] `NetworkSilenceTest` : démarrage de l'application, service actif, capture, recherche → **zéro octet
      émis** ; le compteur ne bouge qu'après `prepare()`.
- [ ] Tests JVM avec un SDK simulé : correspondance des codes d'erreur vers `NO_FILL`, `OFFLINE`,
      `ERROR` ; `EARNED` seulement si la récompense a été signalée avant la fermeture ; délai maximal de chargement.
- [ ] `:app:verifyAdsReleaseManifest` vert : `ACCESS_ADSERVICES_ATTRIBUTION` et `ACCESS_ADSERVICES_TOPICS` absentes.
- [ ] Le service d'accessibilité ne capture pas l'écran de l'annonce (paquet de l'application ignoré).

**Validation manuelle** — sur le téléphone de test, build `debug` : annonce de test affichée et
récompense signalée ; fenêtre de consentement affichée (région Europe) ; formulaire des choix de
confidentialité accessible.

---

### M8-04 — Parcours de récompense et feuille de crédits

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-01, M8-03, M6-03 | `engine:monetization`, `feature:paywall`, `feature:search`, `feature:settings` | [monetisation §3.2, §6](../specs/monetisation.md) · [ui-ux §3.2, §3.5](../specs/ui-ux.md) · EF-MON-02, 03, 11 |

**À faire**

1. `RewardFlow` : plafond quotidien, `prepare` puis `show`, crédit de 5, crédit de courtoisie, messages.
2. Indicateur de solde ou badge « Activé » sur l'écran de recherche.
3. Feuille « Continuer à chercher » : publicité (avec état de chargement), code, rappel de
   confidentialité, état « plafond atteint » ; chargement de l'annonce à l'ouverture de la feuille seulement.
4. Recherche au fil de la frappe en formule activée ; validation explicite en formule gratuite.
5. Rubrique Activation des réglages : solde ou licence, « Confidentialité des publicités » si requis.

**Critères d'acceptation**

- [ ] Avec un `FakeRewardProvider` : chaque issue du tableau de la spec §3.2 produit le bon mouvement et le bon message.
- [ ] Solde nul → la feuille s'ouvre à la validation d'une recherche ; la recherche part après une récompense.
- [ ] Mention « Cette recherche ne vous a pas été décomptée » quand il n'y a aucun résultat.
- [ ] Formule activée : aucun indicateur de crédits, aucune feuille, aucune annonce chargée.
- [ ] Si `RewardProvider.isSupported` est faux, aucun bouton de publicité n'est composé.

---

### M8-05 — Outil de licences et activation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-02, M8-01 | `tools/license`, `engine:monetization`, `feature:settings`, `app`, `site` | [monetisation §5.3 à §5.6](../specs/monetisation.md) · [ADR-0007](../adr/0007-codes-d-activation-signes.md) · EF-MON-05, EF-MON-06 |

**À faire**

1. `tools/license/seekmind_license.py` : `issue`, `verify` ; sortie CSV (identifiant, code, lien) ; tests `pytest`.
2. `LicenseActivator` : vérification, expiration, liste de révocation embarquée, enregistrement.
3. `LinkActivity` : réception de `seekmind://activate`, validation des entrées, confirmation, respect du
   verrouillage ; déclaration dans le manifeste.
4. Champ « Coller » dans les réglages ; « Retirer l'activation ».
5. `site/a.html` : page de relais des liens d'activation (lit le fragment, n'envoie rien).
6. `tools/license/README.md` : procédure d'émission et de conservation de la clé privée.

**Critères d'acceptation**

- [ ] Un code émis par l'outil avec la clé de test est accepté par l'application `debug`.
- [ ] Messages distincts : illisible, signature invalide, expiré, révoqué ; aucun changement d'état en cas d'échec.
- [ ] L'activation survit au redémarrage ; « Retirer » ramène à la formule gratuite avec le solde antérieur.
- [ ] Le lien d'activation ne fait rien sans confirmation de l'utilisateur ; entrées malformées ou
      surdimensionnées → message neutre, aucun effet, aucun plantage.
- [ ] `:app:verifyAdsReleaseManifest` et `:app:verifyOfflineReleaseManifest` verts avec le nouveau composant exporté.

---

### M8-06 — Variante `offline`

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-01, M6-02 | `app` (variante `offline`) | [monetisation §4](../specs/monetisation.md) · [conformité §4.4, §8](../05-conformite-play-store.md) · [ADR-0009](../adr/0009-publicite-admob-et-variante-sans-reseau.md) · EF-MON-12 |

**À faire**

1. `NoRewardProvider` et sa liaison Hilt dans `app/src/offline/` ; `MonetizationConfig.dailyFreeCredits = 5`.
2. Texte de divulgation propre à la variante (formulation « sans accès à Internet ») en ressource de la variante.
3. Feuille de crédits sans publicité : code d'activation et mention des recherches offertes chaque jour.
4. Contrôle de fumée de la variante dans `device-tests` (API 34).
5. `docs/store/bascule-offline.md` : marche à suivre pour soumettre cette variante, tirée de la
   procédure de l'ADR-0009, avec la liste des textes à changer dans la Play Console.

**Critères d'acceptation**

- [ ] `:app:verifyOfflineReleaseManifest` vert : aucune permission `INTERNET`, ni publicitaire.
- [ ] `engine:ads`, le SDK publicitaire et le SDK de consentement sont absents des dépendances de la variante.
- [ ] Sur émulateur : capture, recherche, crédits quotidiens et activation par code fonctionnent ; aucun
      élément d'interface ne mentionne la publicité.
- [ ] Le texte de divulgation affiché est celui de la variante.

---

### M8-07 — Récompense par le navigateur (hors plan)

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Reporté | — | — | [annexe — récompense par le navigateur](../specs/annexe-recompense-navigateur.md) |

Solution de repli, **non réalisée en v1**. À n'ouvrir que si la variante `offline` est publiée à la
place de la variante `ads` et que le porteur du projet veut y rétablir une publicité. L'annexe se
découpe alors en quatre tickets (son §8).

---

### M8-08 — Tests de bout en bout de la monétisation

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M8-04, M8-05, M8-06 | `app`, `fixtureapp` | [tests §4](../06-qualite-tests-ci.md#4-tests-de-bout-en-bout) · [monetisation §8](../specs/monetisation.md) |

**À faire**

1. Liaison Hilt de test remplaçant `RewardProvider` par un fournisseur factice pilotable.
2. Scénario E2E-15 ; scénario d'activation par lien ; scénario « solde nul → feuille → récompense → recherche ».
3. E2E-20 (silence réseau) intégré à `device-tests`.

**Critères d'acceptation**

- [ ] E2E-15 vert sur les trois niveaux d'API : +5 après une annonce vue, rien après une annonce fermée, +1 sans annonce.
- [ ] Capture, journal, réglages et suppression restent accessibles avec un solde nul (EF-MON-07).
- [ ] E2E-20 vert.
