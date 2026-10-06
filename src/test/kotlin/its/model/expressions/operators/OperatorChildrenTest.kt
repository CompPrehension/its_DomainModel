package its.model.expressions.operators

import its.model.TypedVariable
import its.model.expressions.getUsedVariables
import its.model.expressions.literals.BooleanLiteral
import its.model.expressions.literals.DecisionTreeVarLiteral
import its.model.expressions.literals.VariableLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [its.model.expressions.Operator.children] должен содержать все подвыражения оператора:
 * по нему обходят дерево выражения (поиск переменных, проверка на изменение состояния, ключи мемоизации)
 */
class OperatorChildrenTest {

    /** Полная форма условного оператора перечисляет и ветку "иначе". */
    @Test
    fun ifThenWithElseListsElseBranch() {
        // Arrange.
        val condition = BooleanLiteral(true)
        val thenExpr = VariableLiteral("a")
        val elseExpr = VariableLiteral("b")
        val ifThen = IfThen(condition, thenExpr, elseExpr)

        // Act.
        val children = ifThen.children

        // Assert.
        assertEquals(listOf(condition, thenExpr, elseExpr), children)
    }

    /** Неполная форма условного оператора не добавляет отсутствующую ветку. */
    @Test
    fun ifThenWithoutElseListsConditionAndThen() {
        // Arrange.
        val condition = BooleanLiteral(true)
        val thenExpr = VariableLiteral("a")
        val ifThen = IfThen(condition, thenExpr)

        // Act.
        val children = ifThen.children

        // Assert.
        assertEquals(listOf(condition, thenExpr), children)
    }

    /** Переменные, упомянутые только в ветке "иначе", считаются используемыми. */
    @Test
    fun ifThenElseBranchVariablesAreUsed() {
        // Arrange.
        val ifThen = IfThen(BooleanLiteral(true), VariableLiteral("a"), DecisionTreeVarLiteral("X"))

        // Act.
        val usedVariables = ifThen.getUsedVariables()

        // Assert.
        assertEquals(setOf("X"), usedVariables)
    }

    /** Поиск экстремума перечисляет и условие выборки, и условие экстремума - в этом порядке. */
    @Test
    fun getExtremeListsExtremeCondition() {
        // Arrange.
        val condition = BooleanLiteral(true)
        val extremeCondition = VariableLiteral("ex")
        val getExtreme = GetExtreme("item", "i", condition, "ex", extremeCondition)

        // Act.
        val children = getExtreme.children

        // Assert.
        assertEquals(2, children.size)
        assertSame(condition, children[0])
        assertSame(extremeCondition, children[1])
    }

    /** Переменные, упомянутые только в условии экстремума, считаются используемыми. */
    @Test
    fun getExtremeConditionVariablesAreUsed() {
        // Arrange.
        val getExtreme = GetExtreme("item", "i", BooleanLiteral(true), "ex", DecisionTreeVarLiteral("X"))

        // Act.
        val usedVariables = getExtreme.getUsedVariables()

        // Assert.
        assertEquals(setOf("X"), usedVariables)
    }

    /** Квантор без селектора перечисляет только условие. */
    @Test
    fun quantifierWithoutSelectorListsOnlyCondition() {
        // Arrange.
        val condition = BooleanLiteral(true)
        val quantifier = ExistenceQuantifier(TypedVariable("item", "i"), conditionExpr = condition)

        // Act.
        val children = quantifier.children

        // Assert.
        assertEquals(listOf(condition), children)
    }

    /** У литерала нет подвыражений. */
    @Test
    fun literalHasNoChildren() {
        // Arrange.
        val literal = BooleanLiteral(false)

        // Act.
        val children = literal.children

        // Assert.
        assertTrue(children.isEmpty())
    }
}
