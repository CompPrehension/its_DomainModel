package its.model.definition

import its.model.definition.loqi.DomainLoqiBuilder
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropertyValueStatementsTest {

    private val domain = DomainLoqiBuilder.buildDomain(StringReader("""
        enum Lang { en, ru }
        class item {
            obj prop weight: int ;
            obj prop label<lang: Lang>: string ;
        }
        obj a : item { weight = 1 ; label<Lang:en> = "a" ; }
    """)).also { it.validateAndThrow() }

    private val a = domain.objects.get("a")!!

    private fun statement(name: String, value: Any, params: ParamsValues = ParamsValues.EMPTY) =
        PropertyValueStatement(a, name, params, value)

    /** Замена значения свойства: старое значение исчезает, новое читается. */
    @Test
    fun replaceOverwritesExistingValue() {
        // Act.
        a.definedPropertyValues.addOrReplace(statement("weight", 2))

        // Assert.
        assertEquals(2, a.getPropertyValue("weight"))
        assertEquals(1, a.definedPropertyValues.count { it.propertyName == "weight" })
    }

    /** Замена значения не заданного ранее свойства добавляет его. */
    @Test
    fun replaceAddsMissingValue() {
        // Act.
        a.definedPropertyValues.addOrReplace(statement("label", "а", NamedParamsValues(mapOf("lang" to EnumValueRef("Lang", "ru")))))

        // Assert.
        assertEquals("а", a.getPropertyValue("label", mapOf("lang" to EnumValueRef("Lang", "ru"))))
        assertEquals("a", a.getPropertyValue("label", mapOf("lang" to EnumValueRef("Lang", "en"))))
    }

    /** Невалидная замена отвергается, а старое значение остаётся на месте. */
    @Test
    fun invalidReplacementKeepsOldValue() {
        // Act.
        assertFailsWith<InvalidDomainDefinitionException> { a.definedPropertyValues.addOrReplace(statement("weight", "heavy")) }

        // Assert.
        assertEquals(1, a.getPropertyValue("weight"))
    }
}
