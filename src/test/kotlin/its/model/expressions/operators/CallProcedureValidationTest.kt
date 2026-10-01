package its.model.expressions.operators

import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.TreeLoqiBuilder
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertTrue

class CallProcedureValidationTest {

    private val domain = DomainLoqiBuilder.buildDomain(StringReader("""
        class item {
            obj prop sold: bool ;
        }
    """)).also { it.validateAndThrow() }

    private fun askTree(condition: String) = TreeLoqiBuilder.buildTree(StringReader("""
        tpg T(X: item) {
            ask ($condition) {
                true -> { conclude: correct };
                false -> { conclude: null };
            }
        }
    """))

    /** Процедура с возвращаемым типом (subcall) допустима внутри выражения. */
    @Test
    fun procedureWithReturnTypeIsValidInExpression() {
        // Act.
        val results = askTree("subcall(\"someTree\", X) == true").validateAndGet(domain)

        // Assert.
        assertTrue(results.invalids.isEmpty(), results.invalids.joinToString { it.message.toString() })
    }

    /** Процедура без возвращаемого типа внутри выражения по-прежнему считается невалидной. */
    @Test
    fun procedureWithoutReturnTypeIsInvalidInExpression() {
        // Act.
        val results = askTree("debug:assert(X.sold, \"msg\") == true").validateAndGet(domain)

        // Assert.
        assertTrue(
            results.invalids.any { it.message == "Procedure with no return type can't be used as expression" },
            results.invalids.joinToString { it.message.toString() }
        )
    }
}
