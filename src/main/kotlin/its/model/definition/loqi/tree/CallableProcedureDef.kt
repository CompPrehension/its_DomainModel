package its.model.definition.loqi.tree

import its.model.definition.types.Type
import its.model.expressions.Operator
import its.model.nodes.DecisionTreeNode
import its.model.nodes.Outcome
import its.model.nodes.Outcomes
import its.model.nodes.ProcedureCallNode

/**
 * Аргумент процедуры
 */
data class ProcedureArgument(val name: String, var type: Type<*>)

/**
 * Пространство имен для процедур
 */
open class Namespace (val parent: Namespace?, val name: String) {
    init {
        require((parent == null && name == "") || name != null)
    }

    fun getScopeResolution(): Array<String> {
        var list = mutableListOf(name)
        var curr: Namespace? = parent
        while (curr != null && name != "") {
            list.add(curr.name)
            curr = curr.parent
        }
        list.reverse()
        return list.toTypedArray()
    }
}

object GlobalNamespace: Namespace(null, "")

/**
 * Встраиваемая в дерево процедура, влияющая на поведение интерпретатора или построителя дерева из языка loqi2
 */
open class CallableProcedureDef(
    open val name: String, open val arguments: List<ProcedureArgument>,
    open val scopeCapture: Boolean, // процедура захватит все доступные переменные в области вызова
) {

    fun callNode(callArguments: List<Operator>, next: DecisionTreeNode): ProcedureCallNode {
        return ProcedureCallNode(this, callArguments, Outcomes(mutableListOf(Outcome(true, next))))
    }
}
