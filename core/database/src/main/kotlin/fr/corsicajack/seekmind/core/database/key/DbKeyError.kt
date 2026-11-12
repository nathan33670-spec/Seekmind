package fr.corsicajack.seekmind.core.database.key

/** Why the database key cannot be obtained (docs/07-securite-vie-privee.md §4). No exception escapes. */
sealed interface DbKeyError {
    /** The database exists but its key file is gone: the database cannot be read again. */
    data object KeyMissing : DbKeyError

    /** The Keystore key is absent or permanently invalidated: the wrapped key cannot be opened. */
    data object AliasInvalid : DbKeyError

    /** The key file is not in this format, or it was altered (authentication tag rejected). */
    data object FileCorrupt : DbKeyError
}
