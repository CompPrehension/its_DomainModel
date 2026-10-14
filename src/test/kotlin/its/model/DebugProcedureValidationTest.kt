package its.model

import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.TreeLoqiBuilder
import its.model.nodes.InvalidDecisionTreeException
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DebugProcedureValidationTest {

    private val domain = DomainLoqiBuilder.buildDomain(StringReader("""
        class item {
            obj prop sold: bool ;
        }
    """))

    private fun model(tree: String) = DomainSolvingModel(
        domain,
        emptyMap(),
        mapOf("" to TreeLoqiBuilder.buildTree(StringReader(tree))),
    )

    private fun askTree(condition: String) = model("""
        tpg T(X: item) {
            ask ($condition) {
                true -> { conclude: correct };
                false -> { conclude: error };
            }
        }
    """)

    private fun assertRejected(model: DomainSolvingModel, procedureName: String) {
        val exception = assertFailsWith<InvalidDecisionTreeException> { model.validate() }
        assertContains(exception.message!!, "debug=false: $procedureName")
    }

    /** Отладочный вызов-инструкция по-прежнему запрещен без debug. */
    @Test
    fun debugStatementIsRejected() {
        // Arrange.
        val model = model("""
            tpg T(X: item) {
                debug:print(X);
                conclude: correct
            }
        """)

        // Act & Assert.
        assertRejected(model, "debug:print")
    }

    /** Отладочный вызов внутри блока в выражении запрещен без debug, хотя его значение не используется. */
    @Test
    fun debugCallInExpressionBlockIsRejected() {
        // Act & Assert.
        assertRejected(askTree("{ debug:print(X); X.sold }"), "debug:print")
    }

    /** Вложенный отладочный вызов в выражении запрещен без debug. */
    @Test
    fun nestedDebugCallInExpressionIsRejected() {
        // Act & Assert.
        assertRejected(askTree("X.sold == debug:trace(true)"), "debug:trace")
    }

    /** Отладочный вызов в действии узла результата запрещен без debug. */
    @Test
    fun debugCallInConclusionActionIsRejected() {
        // Arrange.
        val model = model("""
            tpg T(X: item) {
                conclude: correct with ({ debug:dump("c"); X.sold = true })
            }
        """)

        // Act & Assert.
        assertRejected(model, "debug:dump")
    }

    /** Отладочный вызов в аргументах перенаправления результата в субинтерпретатор запрещен без debug. */
    @Test
    fun debugCallInRedirectingConclusionIsRejected() {
        // Arrange.
        val model = model("""
            tpg T(X: item) {
                conclude: subcall("sub", debug:print(X))
            }
        """)

        // Act & Assert.
        assertRejected(model, "debug:print")
    }

    /** С debug отладочные вызовы в выражениях разрешены. */
    @Test
    fun debugCallInExpressionIsAllowedInDebugMode() {
        // Act & Assert.
        askTree("{ debug:print(X); X.sold }").validate(debug = true)
    }

    /** Обычные процедуры в выражениях проверку отладочных вызовов не затрагивают. */
    @Test
    fun nonDebugCallInExpressionIsAllowed() {
        // Act & Assert.
        askTree("subcall(\"sub\", X) == true").validate()
    }
}
