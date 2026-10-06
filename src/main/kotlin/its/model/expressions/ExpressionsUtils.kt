package its.model.expressions

import its.model.expressions.literals.DecisionTreeVarLiteral

fun Operator.getUsedVariables(): Set<String> {
    val set = mutableSetOf<String>()
    collectUsedVariables(set)
    return set
}

/**
 * Собрать переменные дерева решений в одно множество, не создавая множество на каждый узел выражения
 */
private fun Operator.collectUsedVariables(set: MutableSet<String>) {
    if (this is DecisionTreeVarLiteral) {
        set.add(this.name)
    } else {
        this.children.forEach { it.collectUsedVariables(set) }
    }
}