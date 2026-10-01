package its.model

import its.model.definition.UnknownDomainDefinitionException
import its.model.definition.loqi.DomainLoqiBuilder
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainSolvingModelValidationTest {

    private fun domain(loqi: String) = DomainLoqiBuilder.buildDomain(StringReader(loqi))

    private val base = """
        class operation {
            class prop rank: int ;
        }
        class call : operation { }
    """

    /** Конкретный класс тега, так и не задавший значение свойства, ловится при валидации модели с указанием тега. */
    @Test
    fun tagLeafClassWithoutValueIsRejected() {
        // Arrange.
        val model = DomainSolvingModel(
            domain(base),
            mapOf("python" to domain("class py_len : call { rank = 2 ; } class py_input : call { }")),
            emptyMap(),
        )

        // Act & Assert.
        val exception = assertFailsWith<UnknownDomainDefinitionException> { model.validate() }
        assertEquals(
            "In tag 'python': class py_input does not define a value for class property operation.rank",
            exception.message
        )
    }

    /** Абстрактные классы основной модели, дополняемые классами тегов, значения задавать не обязаны. */
    @Test
    fun classesWithSubclassesNeedNoValues() {
        // Arrange.
        val model = DomainSolvingModel(
            domain(base),
            mapOf("python" to domain("class py_len : call { rank = 2 ; }")),
            emptyMap(),
        )

        // Act & Assert.
        model.validate()
    }

    /** Без тегов конкретными считаются листовые классы основной модели. */
    @Test
    fun baseLeafClassWithoutValueIsRejectedWithoutTags() {
        // Arrange.
        val model = DomainSolvingModel(domain(base), emptyMap(), emptyMap())

        // Act & Assert.
        val exception = assertFailsWith<UnknownDomainDefinitionException> { model.validate() }
        assertContains(exception.message!!, "class call")
    }

    /** Ситуация с объектом класса, не задавшего значение свойства без параметров, не проходит валидацию. */
    @Test
    fun situationObjectOfClassWithoutValueIsRejected() {
        // Arrange.
        val situation = domain(base + "obj o : call { }")

        // Act & Assert.
        assertFailsWith<UnknownDomainDefinitionException> { situation.validateAndThrow() }
    }
}
