# ADR-0004 — Room + SQLCipher + FTS5

| | |
|---|---|
| **Statut** | Accepté |
| **Date** | 2026-11-12 |

## Contexte

Il faut un stockage local chiffré, une recherche plein texte classée (BM25) tolérante aux accents, des
insertions de l'ordre de quelques millisecondes, et un outillage que des agents de développement
maîtrisent bien.

## Décision

- **SQLCipher** (`net.zetetic:sqlcipher-android`) comme moteur SQLite. Sa compilation active FTS5
  (`SQLITE_ENABLE_FTS5`, vérifié dans les sources de la bibliothèque ; revérifié à l'exécution par le
  prototype M0-06).
- **Room** pour les tables ordinaires, branché sur SQLCipher par `SupportOpenHelperFactory`.
- **FTS5** en table virtuelle à contenu externe, créée et interrogée par SQL brut (Room ne gère que
  FTS3/FTS4 par annotations).
- Clé aléatoire de 32 octets, enveloppée par le Keystore Android.

## Conséquences

- Un seul fichier, une seule transaction pour le texte et son index.
- Le SQL de la table FTS5 et de ses déclencheurs est hors du contrôle de schéma de Room : il vit dans un
  objet unique (`FtsSchema`) et a ses propres tests de migration.
- Les tests de base sont instrumentés (bibliothèque native) : ils tournent sur émulateur en CI.
- Surcoût du chiffrement accepté (quelques pour cent sur les lectures et écritures).
- Le SQLite de la plateforme n'est pas utilisé : son niveau de version et la présence de FTS5 varient
  selon les appareils.

## Alternatives écartées

| Alternative | Pourquoi non |
|---|---|
| SQLite de la plateforme, sans chiffrement applicatif | FTS5 non garanti ; pas de chiffrement indépendant de celui du système. |
| SQLDelight | Bonne prise en charge de FTS5, mais moins répandu : plus d'erreurs probables en développement automatisé. Réévaluable. |
| ObjectBox (base objet avec index vectoriel) | Pas de recherche plein texte BM25 équivalente ; chiffrement et format propriétaires ; dépendance forte. |
| `androidx.sqlite` embarqué | FTS5 présent mais pas de chiffrement. |
