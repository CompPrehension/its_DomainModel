package its.model.definition.loqi

import its.model.DomainSolvingModel
import its.model.definition.InvalidDomainDefinitionException
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeparateClassPropertyValuesTest {

    private fun domain(loqi: String) = DomainLoqiBuilder.buildDomain(StringReader(loqi))

    private val base = """
        class operation {
            class prop rank: int ;
        }
    """

    /** `values for class` в том же файле задаёт значение свойства объявленного там класса. */
    @Test
    fun valuesForClassInSameDomain() {
        // Act.
        val domain = domain(base + "values for class operation { rank = 2 ; }")

        // Assert.
        assertEquals(2, domain.classes.get("operation")!!.getPropertyValue("rank"))
    }

    /** Тег задаёт значение свойства класса основной модели, хотя сам этого класса не видит. */
    @Test
    fun tagDefinesValueForBaseClass() {
        // Arrange.
        val model = DomainSolvingModel(
            domain(base),
            mapOf("python" to domain("values for class operation { rank = 3 ; }")),
            emptyMap(),
        )

        // Act.
        val merged = model.getMergedTagDomain("python")

        // Assert.
        assertEquals(3, merged.classes.get("operation")!!.getPropertyValue("rank"))
    }

    /** Значение не того типа из тега отвергается при слиянии с основной моделью. */
    @Test
    fun tagValueOfWrongTypeIsRejectedOnMerge() {
        // Arrange.
        val model = DomainSolvingModel(
            domain(base),
            mapOf("python" to domain("values for class operation { rank = true ; }")),
            emptyMap(),
        )

        // Act & Assert.
        assertFailsWith<InvalidDomainDefinitionException> { model.getMergedTagDomain("python") }
    }
}
