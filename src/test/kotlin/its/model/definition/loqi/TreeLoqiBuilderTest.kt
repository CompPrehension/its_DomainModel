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
}
