package fr.corsicajack.seekmind.core.database.prototype

/** Full-text objects of docs/specs/stockage.md §3.2, outside Room's schema control. */
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

    /** The reference lexical query of docs/specs/stockage.md §5, with one placeholder per package. */
    fun lexicalQuery(packageCount: Int): String {
        val packages = if (packageCount == 0) "NULL" else List(packageCount) { "?" }.joinToString(",")
        return """
            SELECT c.id,
                   bm25(capture_fts, 1.0, 2.0, 0.5) AS rank,
                   snippet(capture_fts, 0, char(2), char(3), '…', 24) AS snip
            FROM capture_fts
            JOIN capture c ON c.id = capture_fts.rowid
            WHERE capture_fts MATCH ?
              AND (? IS NULL OR c.last_seen_at >= ?)
              AND (? IS NULL OR c.first_seen_at <= ?)
              AND (? = 0 OR c.package_name IN ($packages))
            ORDER BY rank
            LIMIT ?
        """.trimIndent()
    }
}
