package fr.corsicajack.seekmind.engine.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/specs/indexation.md §3 (ticket M4-01). */
class SimHashTest {
    @Test
    fun `FNV-1a 64 matches the reference values`() {
        assertThat(SimHash.fnv1a64(ByteArray(0))).isEqualTo(0xcbf29ce484222325uL.toLong())
        assertThat(SimHash.fnv1a64("a".toByteArray())).isEqualTo(0xaf63dc4c8601ec8cuL.toLong())
        assertThat(SimHash.fnv1a64("foobar".toByteArray())).isEqualTo(0x85944171f73967e8uL.toLong())
    }

    @Test
    fun `words are runs of letters or digits, lower-cased, without diacritics`() {
        assertThat(
            SimHash.words("Crème brûlée, 12 €… L'été!"),
        ).containsExactly("creme", "brulee", "12", "l", "ete").inOrder()
    }

    @Test
    fun `the fingerprint is deterministic and the same text gives distance 0`() {
        val text = "Le restaurant italien de la rue Paradis sert des pâtes à la truffe le mardi soir."
        assertThat(SimHash.of(text)).isEqualTo(SimHash.of(text))
        assertThat(SimHash.hamming(SimHash.of(text), SimHash.of(String(text.toCharArray())))).isEqualTo(0)
        assertThat(SimHash.of("pâtes à la truffe")).isEqualTo(SimHash.of("PATES A LA TRUFFE"))
    }

    @Test
    fun `fingerprints match reference values computed independently from the spec`() {
        // Stored fingerprints depend on these values: a change of the algorithm must be deliberate.
        assertThat(SimHash.of("pâtes à la truffe")).isEqualTo(0x808da1800200c020uL.toLong())
        assertThat(SimHash.of("Le restaurant italien de la rue Paradis sert des pâtes à la truffe le mardi soir."))
            .isEqualTo(0x920c89ba9204c825uL.toLong())
    }

    @Test
    fun `fewer than three words are their own features`() {
        assertThat(SimHash.of("")).isEqualTo(0L)
        assertThat(SimHash.of("…")).isEqualTo(0L)
        assertThat(SimHash.of("bonjour")).isEqualTo(SimHash.fnv1a64("bonjour".toByteArray()))
        assertThat(SimHash.of("deux mots")).isNotEqualTo(0L)
    }

    @Test
    fun `swapping two lines moves the fingerprint a little`() {
        val a = (1..12).joinToString("\n") { "ligne $it de la liste des courses avec ${it * 3} articles" }
        val lines = a.split('\n').toMutableList()
        lines[3] = lines[4].also { lines[4] = lines[3] }
        assertThat(SimHash.hamming(SimHash.of(a), SimHash.of(lines.joinToString("\n")))).isAtMost(9)
    }

    @Test
    fun `hamming counts the differing bits`() {
        assertThat(SimHash.hamming(0L, -1L)).isEqualTo(64)
        assertThat(SimHash.hamming(0b1010L, 0b0110L)).isEqualTo(2)
    }
}
