package fr.corsicajack.seekmind.engine.embedding.tokenizer

import java.io.File

/** One line of `testdata/tokenizer_golden_<id>.jsonl`: `{"text": ..., "ids": [...]}`. */
internal class GoldenLine(val text: String, val ids: IntArray)

/** Minimal reader for the two fields of the golden lines (no JSON library on the JVM test classpath). */
internal object GoldenLines {
    fun read(file: File): List<GoldenLine> = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { parse(it) }

    fun parse(line: String): GoldenLine {
        val reader = Reader(line)
        var text: String? = null
        var ids: IntArray? = null
        reader.expect('{')
        do {
            when (val key = reader.string()) {
                "text" -> text = reader.also { it.expect(':') }.string()
                "ids" -> ids = reader.also { it.expect(':') }.intArray()
                else -> error("unexpected key '$key'")
            }
        } while (reader.next(',', '}') == ',')
        return GoldenLine(checkNotNull(text), checkNotNull(ids))
    }

    private class Reader(private val s: String) {
        private var i = 0

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expect(c: Char) {
            skipSpaces()
            check(s[i] == c) { "expected '$c' at $i" }
            i++
        }

        fun next(vararg options: Char): Char {
            skipSpaces()
            val c = s[i++]
            check(c in options) { "unexpected '$c' at ${i - 1}" }
            return c
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()

                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)

                            'b' -> out.append('\b')

                            'f' -> out.append(Char(0x0C))

                            'n' -> out.append('\n')

                            'r' -> out.append('\r')

                            't' -> out.append('\t')

                            'u' -> {
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }

                            else -> error("bad escape '\\$e'")
                        }
                    }

                    else -> out.append(c)
                }
            }
        }

        fun intArray(): IntArray {
            expect('[')
            val values = mutableListOf<Int>()
            skipSpaces()
            if (s[i] == ']') {
                i++
                return IntArray(0)
            }
            do {
                skipSpaces()
                val start = i
                while (s[i].isDigit() || s[i] == '-') i++
                values += s.substring(start, i).toInt()
            } while (next(',', ']') == ',')
            return values.toIntArray()
        }
    }
}
