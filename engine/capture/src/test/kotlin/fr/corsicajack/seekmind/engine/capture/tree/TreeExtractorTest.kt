package fr.corsicajack.seekmind.engine.capture.tree

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.common.Outcome
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.testing.FakeClock
import org.junit.Test

/** docs/specs/capture.md §4.2 on fake trees (ticket M2-03). */
class TreeExtractorTest {
    private val clock = FakeClock(now = 0, elapsed = 0)
    private val config = CaptureConfig()

    // --- Rules -----------------------------------------------------------------------------------

    @Test
    fun `an invisible node is skipped with its subtree`() {
        val (result, tree) = extract {
            child { text = "visible" }
            child {
                visible = false
                text = "caché"
                child { text = "enfant d'un caché" }
            }
        }
        assertThat(texts(result)).containsExactly("visible")
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `a password field stops the walk at once`() {
        val (result, tree) = extract {
            child { text = "avant" }
            child { password = true }
            child { text = "après" }
        }
        assertThat(result.hasPasswordField).isTrue()
        assertThat(texts(result)).doesNotContain("après")
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `an editable field is skipped with its subtree`() {
        val (result, _) = extract {
            child {
                editable = true
                text = "brouillon de message"
                child { text = "suggestion" }
            }
            child { text = "texte statique" }
        }
        assertThat(texts(result)).containsExactly("texte statique")
    }

    @Test
    fun `a node with text gives a line, in reading order`() {
        val (result, _) = extract {
            child {
                text = "premier"
                child { text = "premier enfant" }
            }
            child { text = "second" }
        }
        assertThat(texts(result)).containsExactly("premier", "premier enfant", "second").inOrder()
        assertThat(result.lines.none { it.fromDescription }).isTrue()
    }

    @Test
    fun `a description is kept for images and non-clickable nodes, not for buttons`() {
        val (result, _) = extract {
            child { description = "Photo d'un coucher de soleil" }
            child {
                clickable = true
                className = "android.widget.Button"
                description = "Partager"
            }
            child {
                clickable = true
                className = "android.widget.ImageView"
                description = "Affiche du concert"
            }
            child {
                text = "texte visible"
                description = "ignorée : le texte prime"
            }
        }
        assertThat(
            texts(result),
        ).containsExactly("Photo d'un coucher de soleil", "Affiche du concert", "texte visible").inOrder()
        assertThat(result.lines.filter { it.fromDescription }.map { it.text })
            .containsExactly("Photo d'un coucher de soleil", "Affiche du concert")
    }

    @Test
    fun `a heading with text joins the headings`() {
        val (result, _) = extract {
            child {
                heading = true
                text = "Résultats de la recherche"
            }
            child { text = "un résultat" }
        }
        assertThat(result.headings).containsExactly("Résultats de la recherche")
    }

    @Test
    fun `a duplicate line is kept once`() {
        val (result, _) = extract {
            child { text = "même texte" }
            child { text = "même   texte " }
            child { text = "autre" }
        }
        assertThat(texts(result)).containsExactly("même texte", "autre").inOrder()
    }

    @Test
    fun `lines are cleaned, capped, and need two alphanumeric characters`() {
        val (result, _) = extract {
            child { text = "  plusieurs\n espaces\tici  " }
            child { text = "→ !" }
            child { text = "a" }
            child { text = "x".repeat(3_000) }
        }
        assertThat(texts(result)).containsExactly("plusieurs espaces ici", "x".repeat(2_000)).inOrder()
    }

    // --- Budgets ---------------------------------------------------------------------------------

    @Test
    fun `the node budget stops the walk and keeps the result`() {
        val (result, tree) = extract(config.copy(maxNodes = 10)) { items(30) }
        assertThat(result.truncated).isTrue()
        assertThat(result.nodeCount).isEqualTo(10)
        assertThat(result.lines).hasSize(9) // the root has no text
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `a subtree deeper than the depth budget is skipped`() {
        val (result, _) = extract(config.copy(maxDepth = 2)) {
            child {
                text = "profondeur 1"
                child {
                    text = "profondeur 2"
                    child { text = "profondeur 3" }
                }
            }
        }
        assertThat(texts(result)).containsExactly("profondeur 1", "profondeur 2").inOrder()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `past the soft time budget the walk stops with a partial result`() {
        // 10 groups of 20 items, 4 ms per child obtained: at the check of node 50, 70 children were
        // obtained (10 groups, then the items of three groups), 280 ms, between the soft (150) and hard
        // (400) budgets.
        val tree = FakeTree(clock, msPerChild = 4)
        val root = tree.tree { repeat(10) { child { items(20) } } }
        val outcome = TreeExtractor(config, clock).extract(root)
        root.release() // the root stays the caller's
        val result = (outcome as Outcome.Success).value
        assertThat(result.truncated).isTrue()
        assertThat(result.nodeCount).isEqualTo(50)
        assertThat(result.lines).isNotEmpty()
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `past the hard time budget the screen is dropped and every node released`() {
        val tree = FakeTree(clock, msPerChild = 20)
        val root = tree.tree { items(200) }
        val outcome = TreeExtractor(config, clock).extract(root)
        root.release()
        assertThat(outcome).isEqualTo(Outcome.Failure(DropReason.TREE_TIMEOUT))
        assertThat(tree.unreleased).isEmpty()
    }

    @Test
    fun `the text budget stops the walk`() {
        val (result, _) = extract(config.copy(maxTextChars = 50)) { items(40, prefix = "élément numéro") }
        assertThat(result.truncated).isTrue()
        assertThat(result.textChars).isAtLeast(50)
        assertThat(result.textChars).isLessThan(50 + "élément numéro 39".length)
    }

    @Test
    fun `the first visible node of a page class gives the page title`() {
        val (result, _) = extract {
            child {
                className = PAGE_CLASS
                visible = false
                text = "onglet en arrière-plan"
            }
            child {
                className = PAGE_CLASS
                text = "  Recette des   pâtes "
            }
            child {
                className = PAGE_CLASS
                text = "second cadre"
            }
        }
        assertThat(result.pageTitle).isEqualTo("Recette des pâtes")
    }

    @Test
    fun `without a page node there is no page title`() {
        val (result, _) = extract { child { text = "Recette des pâtes" } }
        assertThat(result.pageTitle).isNull()
    }

    // --- Statistics and release ------------------------------------------------------------------

    @Test
    fun `statistics describe the screen`() {
        val (result, _) = extract({
            bounds = IntRect(0, 0, 100, 100)
            viewId = "com.example:id/root"
        }) {
            child {
                text = "abcdef"
                bounds = IntRect(0, 0, 50, 20)
                viewId = "com.example:id/url_bar"
            }
            child {
                text = "ghij"
                bounds = IntRect(0, 20, 50, 40)
            }
        }
        assertThat(result.textChars).isEqualTo(10)
        assertThat(result.textCoverage).isWithin(1e-6f).of(0.2f)
        assertThat(result.viewIds).containsExactly("com.example:id/root", "com.example:id/url_bar")
        assertThat(result.windowBounds).isEqualTo(IntRect(0, 0, 100, 100))
        assertThat(result.nodeCount).isEqualTo(3)
    }

    @Test
    fun `coverage is capped at one`() {
        val (result, _) = extract({ bounds = IntRect(0, 0, 10, 10) }) { items(5) }
        assertThat(result.textCoverage).isAtMost(1f)
    }

    @Test
    fun `every visited node is released exactly once`() {
        val (_, tree) = extract {
            child {
                text = "a b"
                items(10)
            }
            child { visible = false }
            child { editable = true }
        }
        assertThat(tree.obtained).isNotEmpty()
        assertThat(tree.unreleased).isEmpty()
    }

    private fun extract(rootSpec: FakeTree.Spec.() -> Unit, build: FakeTree.Spec.() -> Unit) =
        extract(config, rootSpec, build)

    private fun extract(build: FakeTree.Spec.() -> Unit) = extract(config, {}, build)

    private fun extract(config: CaptureConfig, build: FakeTree.Spec.() -> Unit) = extract(config, {}, build)

    private fun extract(
        config: CaptureConfig,
        rootSpec: FakeTree.Spec.() -> Unit,
        build: FakeTree.Spec.() -> Unit,
    ): Pair<TreeExtraction, FakeTree> {
        val tree = FakeTree()
        val root = tree.tree {
            className = "android.widget.FrameLayout"
            bounds = IntRect(0, 0, 1_000, 2_000)
            rootSpec()
            build()
        }
        val outcome = TreeExtractor(config, clock, setOf(PAGE_CLASS)).extract(root)
        // Throws if the extractor released the root, which stays the caller's.
        root.release()
        return (outcome as Outcome.Success).value to tree
    }

    private fun texts(result: TreeExtraction) = result.lines.map { it.text }

    private companion object {
        const val PAGE_CLASS = "org.example.PageView"
    }
}
