# M7 — Retour à la source

**Objectif.** Depuis un résultat, rouvrir la page ou l'application d'origine, avec un libellé qui dit
exactement ce qui va se passer.

**Sortie du jalon.** Scénario E2E-14 vert ; règles par application validées sur appareil.

---

### M7-01 — Résolution et lancement

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| Fait | M1-01 | `engine:source` | [retour-source §1 à §3, §5](../specs/retour-source.md) · EF-SRC-01 à 05 |

**À faire**

1. `SourceResolver` : chaîne `BrowserUrlResolver` → `AppRuleResolver` → `LaunchAppResolver` ; niveau `UNAVAILABLE`.
2. `SourceLauncher` : lancement, repli sur le niveau suivant en cas d'échec, schémas admis.
3. `linksIn` : extraction des URL `http` et `https` du texte (cinq au plus).

**Critères d'acceptation**

- [x] Capture avec URL → `EXACT_URL`, Intent `ACTION_VIEW` ciblant le navigateur d'origine s'il est installé.
- [x] Application désinstallée → `UNAVAILABLE`.
- [x] Échec de lancement → repli, jamais de plantage.
- [x] Schémas `file`, `content`, `javascript`, `intent` refusés.

---

### M7-02 — Règles par application

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M7-01 | `engine:source` | [retour-source §4](../specs/retour-source.md) · risque R-10 |

**À faire**

1. Format `source_rules.json`, chargement, substitution et encodage des variables `{title}`, `{handle}`, `{firstLine}`.
2. Règles initiales de la spec.

**Critères d'acceptation**

- [ ] Variable manquante → la règle est ignorée et la chaîne continue.
- [ ] Les valeurs substituées sont encodées et bornées à 100 caractères.
- [ ] Ajouter une règle ne demande aucune modification de code (test avec un fichier de règles de test).

**Validation manuelle** — pour chaque règle initiale, sur un vrai téléphone : l'Intent ouvre-t-il
l'application au bon endroit ? Tableau « application, règle, résultat » dans la PR ; les règles qui
échouent sont retirées du fichier.

---

### M7-03 — Intégration à l'interface

| Statut | Dépend de | Modules | Référence |
|---|---|---|---|
| À faire | M7-01, M6-04 | `feature:search`, `feature:journal`, `fixtureapp` | [retour-source §1, §6](../specs/retour-source.md) · [ui-ux §3.2, §3.3](../specs/ui-ux.md) · EF-SRC-03 |

**À faire**

1. Bouton de retour à la source sur la carte de résultat et dans le détail, avec le libellé du niveau.
2. Liste des liens détectés dans le détail.
3. `DeepLinkActivity` dans l'application de test et une règle de test correspondante.

**Critères d'acceptation**

- [ ] Le libellé du bouton correspond au niveau (`EXACT_URL`, `APP_LINK`, `APP_SEARCH`, `APP_ONLY`, `UNAVAILABLE`).
- [ ] E2E-14 : le retour ouvre `DeepLinkActivity` sur le bon identifiant.
- [ ] Un lien détecté ne s'ouvre que sur tap.
