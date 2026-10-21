package its.model.definition

import its.model.definition.loqi.DomainLoqiBuilder
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DomainModelOperationsTest {
    private fun domain(text: String) = StringReader(text).use(DomainLoqiBuilder::buildDomain)

    @Test
    fun `subtract retains changed values and links but removes matching statements`() {
        val left = domain("obj a : Thing { x = 1; y = 5; next(b); }")
        val right = domain("obj a : Thing { x = 2; y = 5; next(c); }")
        left.subtract(right)
        val a = assertNotNull(left.objects.get("a"))
        assertEquals(listOf("x" to 1), a.definedPropertyValues.map { it.propertyName to it.value })
        assertEquals(listOf(listOf("b")), a.relationshipLinks.map { it.objectNames })
    }

    @Test
    fun `merge rejects changed property values on the same object`() {
        val left = domain("obj a : Thing { x = 1; }")
        val right = domain("obj a : Thing { x = 2; }")
        assertFailsWith<InvalidDomainDefinitionException> { left.addMerge(right) }
        assertEquals(1, left.objects.get("a")!!.definedPropertyValues.single().value)
    }

    @Test
    fun `merge identical links does not violate relationship cardinality`() {
        val model = domain("""
            class Thing { rel next(Thing): {1 -> 1}; }
            obj a : Thing { next(b); }
            obj b : Thing
        """.trimIndent())
        model.addMerge(model.copy())
        model.validateAndThrow()
        assertEquals(1, model.objects.get("a")!!.relationshipLinks.size)
    }

    @Test
    fun `merge retains distinct links and subtract compares relationship parameters`() {
        val left = domain("obj a : Thing { next<1>(b); }")
        val right = domain("obj a : Thing { next<2>(b); next<1>(c); }")
        left.subtract(right)
        assertEquals(1, left.objects.get("a")!!.relationshipLinks.size)
        left.addMerge(right)
        assertEquals(3, left.objects.get("a")!!.relationshipLinks.size)
        left.subtract(right)
        val remaining = left.objects.get("a")!!.relationshipLinks.single()
        assertEquals(listOf("b"), remaining.objectNames)
        assertEquals(OrderedParamsValues(listOf(1)), remaining.paramsValues)
    }
}
