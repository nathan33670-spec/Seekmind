package fr.corsicajack.seekmind.core.database.schema

/**
 * Full-text objects of docs/specs/stockage.md §3.2, outside Room's schema control: the external-content
 * FTS5 table and its triggers. The only place where this SQL lives; used by onCreate and by migrations.
 */
object FtsSchema {
    val CREATE: List<String> = listOf(
        """
        CREATE VIRTUAL TABLE capture_fts USING fts5(
          text, window_title, url,
          content = 'capture',
          content_rowid = 'id',
          tokenize = "unicode61 remove_diacritics 2"
        )
        """.trimIndent(),
        """
        CREATE TRIGGER capture_ai AFTER INSERT ON capture BEGIN
          INSERT INTO capture_fts(rowid, text, window_title, url)
          VALUES (new.id, new.text, new.window_title, new.url);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER capture_ad AFTER DELETE ON capture BEGIN
          INSERT INTO capture_fts(capture_fts, rowid, text, window_title, url)
          VALUES ('delete', old.id, old.text, old.window_title, old.url);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER capture_au AFTER UPDATE OF text, window_title, url ON capture BEGIN
          INSERT INTO capture_fts(capture_fts, rowid, text, window_title, url)
          VALUES ('delete', old.id, old.text, old.window_title, old.url);
          INSERT INTO capture_fts(rowid, text, window_title, url)
          VALUES (new.id, new.text, new.window_title, new.url);
        END
        """.trimIndent(),
    )

    /** Rebuilds the index from `capture`: for a migration that touches `capture` (spec §7). */
    const val REBUILD = "INSERT INTO capture_fts(capture_fts) VALUES ('rebuild')"

    /** Throws an SQLite error if the index does not match `capture` (rank = 1: compare with the content). */
    const val INTEGRITY_CHECK = "INSERT INTO capture_fts(capture_fts, rank) VALUES ('integrity-check', 1)"
}
