package its.model.nodes

import its.model.TypedVariable
import its.model.definition.types.ObjectType
import its.model.definition.types.Type
import its.model.expressions.operators.CallProcedure

/**
 * Контекст проверки деревьев решений
 * @param variableTypes соответствие имен переменных дерева решений и названий их типов
 */
open class DecisionTreeContext(
    open val variableTypes: MutableMap<String, Type<*>> = mutableMapOf(),
) {
    /**
     * Вызовы процедур внутри выражений дерева, встреченные при проверке.
     * Вызовы-инструкции ([ProcedureCallNode]) сюда не входят - они являются узлами дерева
     */
    val expressionProcedureCalls: MutableList<CallProcedure> = mutableListOf()

    /**
     * Добавить объектную ([ObjectType]) переменную
     */
    fun add(variable: TypedVariable) {
        variableTypes[variable.varName] = ObjectType(variable.className)
    }

    /**
     * Удалить переменную из контекста
     */
    fun remove(variable: TypedVariable) {
        variableTypes.remove(variable.varName)
    }
}
