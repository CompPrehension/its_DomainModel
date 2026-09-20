package its.model.definition.loqi

import its.model.definition.procedures.SubinterpreterCall
import its.model.nodes.BranchResultNode
import its.model.nodes.BranchResultRedirectingNode
import its.model.nodes.QuestionNode
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeLoqiBuilderTest {

    private fun tree(loqi: String) = TreeLoqiBuilder.buildTree(StringReader(loqi))

    private fun written(loqi: String) = StringWriter().also { TreeLoqiWriter.writeTree(tree(loqi), it, "T") }.toString()

    /** Перенаправление результата в вызов субинтерпретатора завершает ветвь. */
    @Test
    fun redirectingConclusionEndsBranch() {
        // Act.
        val tree = tree("tpg T(X: item) { conclude: subcall(\"sub\", X) }")
        val node = tree.mainBranch.start as BranchResultRedirectingNode

        // Assert.
        assertTrue(node.call.procedure is SubinterpreterCall)
        assertEquals(2, node.call.arguments.size)
        assertNull(node.actionExpr)
    }

    /** Перенаправление допустимо после других шагов и внутри исходов, в том числе с действием. */
    @Test
    fun redirectingConclusionInsideOutcomes() {
        // Act.
        val tree = tree("""
            tpg T(X: item) {
                ask (X.sold) {
                    true -> { conclude: subcall("sub", X) with (X.sold = false) };
                    false -> { conclude: error };
                }
            }
        """)
        val question = tree.mainBranch.start as QuestionNode

        // Assert.
        val redirect = question.outcomes[true]!!.node as BranchResultRedirectingNode
        assertTrue(redirect.actionExpr != null)
        assertTrue(question.outcomes[false]!!.node is BranchResultNode)
    }

    /** Перенаправление сохраняется при записи дерева обратно в LOQI. */
    @Test
    fun redirectingConclusionRoundTrips() {
        // Act.
        val text = written("tpg T(X: item) { conclude: subcall(\"sub\", X) with (X.sold = false) }")

        // Assert.
        assertTrue(text.contains("conclude: subcall(\"sub\", X) with ( X.sold = false )"), text)
        assertTrue(tree(text).mainBranch.start is BranchResultRedirectingNode)
    }

    /** Алиас out-исхода в форме со списком веток ставится на стрелку, и метаданные по нему находят исход. */
    @Test
    fun outBranchArrowAliasCarriesMetadata() {
        // Act.
        val tree = tree("""
            tpg T(X: item) {
                ask (X.sold) {
                    true -[skip]-> out;
                    false -> { conclude: error };
                };
                conclude: correct
            }
            meta for skip [ note = "sold" ]
        """)
        val question = tree.mainBranch.start as QuestionNode
        val out = question.outcomes[true]!!

        // Assert.
        assertTrue(out.node is BranchResultNode, "out-ветка продолжается следующим шагом")
        assertEquals("sold", out.metadata["note"])
    }

    /** Out-исход с алиасом переживает запись и повторный разбор вместе со своими метаданными. */
    @Test
    fun outBranchAliasRoundTrips() {
        // Act.
        val text = written("""
            tpg T(X: item) {
                ask (X.sold) {
                    true -[yes]-> { conclude: error };
                    false -[no]-> { conclude: correct };
                }
            }
            meta for yes [ alias = "yes" ; note = "sold" ]
            meta for no [ alias = "no" ; note = "unsold" ]
        """)
        val reparsed = tree(text).mainBranch.start as QuestionNode

        // Assert.
        assertTrue(text.contains("-[yes]-> out;") || text.contains("-[no]-> out;"), text)
        assertEquals("sold", reparsed.outcomes[true]!!.metadata["note"])
        assertEquals("unsold", reparsed.outcomes[false]!!.metadata["note"])
    }

    /** Экранированный алиас на стрелке совпадает с таким же экранированным именем в `meta for`. */
    @Test
    fun escapedArrowAliasMatchesMetaDeclaration() {
        // Act.
        val tree = tree("""
            tpg T(X: item) {
                agg and {
                    _ -[`left.check.1`]-> { conclude: correct };
                    correct -> { conclude: correct };
                }
            }
            meta for `left.check.1` [ note = "first" ]
        """)

        // Assert.
        assertEquals("first", (tree.mainBranch.start as its.model.nodes.BranchAggregationNode).thoughtBranches.single().metadata["note"])
    }

    /** `out`-исход встаёт на своё место в тексте, а не в конец: порядок исходов совпадает с порядком записи. */
    @Test
    fun outOutcomeKeepsTextualPosition() {
        // Act.
        val question = tree("""
            tpg T(X: item) {
                ask switch (X.color) {
                    Color:red -> out;
                    Color:green -> { conclude: error };
                    Color:blue -> { conclude: null };
                };
                conclude: correct
            }
        """).mainBranch.start as QuestionNode
        val outElse = tree("tpg T(X: item) { ask (X.sold) out false else { conclude: error }; conclude: correct }").mainBranch.start as QuestionNode
        val aggregation = tree("""
            tpg T(X: item) {
                agg and {
                    _ -> { conclude: correct };
                    error -> out;
                    correct -> { conclude: correct };
                };
                conclude: error
            }
        """).mainBranch.start as its.model.nodes.BranchAggregationNode

        // Assert.
        assertEquals(listOf("red", "green", "blue"), question.outcomes.map { (it.key as its.model.definition.types.EnumValue).valueName })
        assertEquals(listOf(false, true), outElse.outcomes.map { it.key })
        assertEquals(listOf(its.model.nodes.BranchResult.ERROR, its.model.nodes.BranchResult.CORRECT), aggregation.outcomes.map { it.key })
    }

    /** Какой бы исход writer ни вывел в `out`, после записи и разбора порядок исходов прежний. */
    @Test
    fun outcomeOrderSurvivesRoundTrip() {
        // Arrange: первый исход - большое поддерево (writer выведет его в out), последний - лист.
        val source = """
            tpg T(X: item) {
                ask (X.sold) {
                    true -> {
                        ask (X.weight > 1) out true else { conclude: null };
                        ask (X.weight > 2) out true else { conclude: null };
                        conclude: correct
                    };
                    false -> { conclude: error };
                }
            }
        """

        // Act.
        val text = written(source)
        val original = tree(source).mainBranch.start as QuestionNode
        val reparsed = tree(text).mainBranch.start as QuestionNode

        // Assert.
        assertTrue(text.contains("out true else"), text)
        assertEquals(original.outcomes.map { it.key }, reparsed.outcomes.map { it.key })
        assertEquals(written(source), written(text))
    }
}
