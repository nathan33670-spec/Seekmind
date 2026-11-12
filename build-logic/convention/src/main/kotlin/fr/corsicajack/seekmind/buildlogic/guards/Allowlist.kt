package fr.corsicajack.seekmind.buildlogic.guards

/** Reads an allowlist file: one entry per line, `#` starts a comment, blank lines are ignored. */
internal fun parseAllowlist(text: String): List<String> = text.lineSequence()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
    .toList()
