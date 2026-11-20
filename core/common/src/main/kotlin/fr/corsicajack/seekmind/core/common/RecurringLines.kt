package fr.corsicajack.seekmind.core.common

/**
 * The short lines an application shows on almost every screen: tab bars, menus, buttons
 * (docs/specs/indexation.md §5.1). For each package, the distinct short lines of its last [window]
 * captures are counted; a line found in more than [share] of them is recurring, once at least
 * [minCaptures] captures were counted. Memory only, with at most [maxPackages] packages (the least
 * recently used one is forgotten first). Not thread-safe: one thread, the caller's.
 */
class RecurringLines(
    private val window: Int,
    private val share: Double,
    private val maxLineChars: Int,
    private val minCaptures: Int,
    private val maxPackages: Int = 64,
) {
    private class History {
        val captures = ArrayDeque<Set<String>>()
        val counts = HashMap<String, Int>()
    }

    private val histories = object : LinkedHashMap<String, History>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, History>?) = size > maxPackages
    }

    /** The test of the lines of [packageName] as things stand, before the capture being handled is counted. */
    fun recurringIn(packageName: String): (String) -> Boolean {
        val history = histories[packageName] ?: return { false }
        val total = history.captures.size
        if (total < minCaptures) return { false }
        val counts = HashMap(history.counts)
        return { line -> key(line)?.let { (counts[it] ?: 0) > share * total } ?: false }
    }

    /** Counts the short lines of one capture of [packageName]. */
    fun record(packageName: String, text: String) {
        val history = histories.getOrPut(packageName) { History() }
        val lines = text.lineSequence().mapNotNull(::key).toSet()
        history.captures.addLast(lines)
        lines.forEach { history.counts.merge(it, 1, Int::plus) }
        while (history.captures.size > window) {
            history.captures.removeFirst().forEach { line ->
                val left = history.counts.getValue(line) - 1
                if (left == 0) history.counts.remove(line) else history.counts[line] = left
            }
        }
    }

    /** Whether [packageName] has a history, learnt or seeded, since the start or the last [clear]. */
    fun knows(packageName: String): Boolean = histories.containsKey(packageName)

    /** Forgets everything: after captures are deleted. */
    fun clear() = histories.clear()

    /** A short line, trimmed; null for a blank or long one, which is never recurring. */
    private fun key(line: String): String? = line.trim().takeIf { it.isNotEmpty() && it.length <= maxLineChars }
}
