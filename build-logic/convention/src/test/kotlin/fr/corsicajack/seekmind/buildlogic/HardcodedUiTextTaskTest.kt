package fr.corsicajack.seekmind.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class HardcodedUiTextTaskTest {
    private fun findings(vararg lines: String) =
        HardcodedUiTextTask.findHardcodedUiText(lines.toList()).map { it.first }

    @Test
    fun `flags literal text passed to Text`() {
        assertEquals(listOf(1), findings("""Text("Bonjour")"""))
        assertEquals(listOf(1), findings("""    Text( "Hello world", style = x)"""))
    }

    @Test
    fun `flags literal text in named UI parameters`() {
        assertEquals(listOf(1, 2), findings("""Icon(x, contentDescription = "Search")""", """label = "Nom""""))
    }

    @Test
    fun `accepts string resources, empty or symbol-only literals and comments`() {
        assertEquals(
            emptyList(),
            findings(
                """Text(stringResource(R.string.app_name))""",
                """Text("")""",
                """Text(" · ")""",
                """// Text("example in a comment")""",
                """ * Text("example in KDoc")""",
                """Text("SeekMind") // ui-text-ok""",
                """val tag = "Search"""",
            ),
        )
    }
}
