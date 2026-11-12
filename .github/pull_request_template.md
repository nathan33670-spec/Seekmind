## Ticket

<!-- Identifiant et lien, ex. : M2-03 — docs/plan/M2-capture.md#m2-03 -->

## Résumé

<!-- Ce qui a été fait, en 3 à 6 lignes, en français. -->

## Critères d'acceptation

<!-- Recopier les critères du ticket et cocher ceux qui sont démontrés par un test. -->

- [ ] …

## Vérifications exécutées

- [ ] `./gradlew spotlessCheck lint` — OK
- [ ] `./gradlew testDebugUnitTest` — OK
- [ ] `./gradlew :app:verifyReleaseManifest` et `scripts/check-forbidden-apis.sh` — OK
- [ ] Tests sur appareil (CI `device-tests`) — OK / non concerné

## Invariants (AGENTS.md §2)

- [ ] Aucune API réseau, aucune permission ajoutée, aucune dépendance hors liste blanche
- [ ] Aucune action automatisée sur l'interface d'autres applications
- [ ] Aucun contenu utilisateur dans les logs, aucun fichier image écrit
- [ ] Chaînes visibles par l'utilisateur en ressources FR + EN

## Écarts par rapport à la spécification

<!-- « Aucun », ou la liste des écarts avec leur justification (et l'ADR créé si la décision est structurante). -->

## Statut du plan

- [ ] Statut du ticket mis à jour dans `docs/plan/`
