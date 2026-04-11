package its.model.definition.procedures

import its.model.definition.types.Type
import its.model.expressions.Operator
import its.model.expressions.operators.CallProcedure
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
open class Namespace(val parent: Namespace?, val name: String) {
    init {
        require((parent == null && name == "") || name != null)
    }

    fun getScopeResolution(): Array<String> {
        val list = mutableListOf(name)
        var curr: Namespace? = parent
        while (curr != null && name != "") {
            list.add(curr.name)
            curr = curr.parent
        }
        list.reverse()
        return list.toTypedArray()
    }

    fun getScopeParts(): List<String> {
        return getScopeResolution().filter { it.isNotEmpty() }
    }

    fun matches(scopeParts: List<String>): Boolean {
        return getScopeParts() == scopeParts
    }
}

object GlobalNamespace : Namespace(null, "")

/**
 * Встраиваемая в дерево процедура, влияющая на поведение интерпретатора или построителя дерева из языка loqi2
 */
open class CallableProcedureDef(
    open val name: String,
    open val arguments: List<ProcedureArgument>,
    open val returnType: Type<*>? = null,
    open val namespace: Namespace = GlobalNamespace,
    open val scopeCapture: Boolean = false,
    open val varArgs: Boolean = false,
) {
    val qualifiedName: String
        get() = (namespace.getScopeParts() + name).joinToString(":")

    fun acceptsArguments(): Boolean {
        return varArgs || arguments.isNotEmpty()
    }

    fun hasReturnType(): Boolean {
        return returnType != null
    }

    fun callNode(callArguments: List<Operator>, next: DecisionTreeNode): ProcedureCallNode {
        return ProcedureCallNode(this, callArguments, Outcomes(mutableListOf(Outcome(true, next))))
    }

    fun callExpr(callArguments: List<Operator>): CallProcedure {
        return CallProcedure(this, callArguments)
    }
}
