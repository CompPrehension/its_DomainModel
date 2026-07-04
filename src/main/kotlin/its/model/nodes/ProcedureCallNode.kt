package its.model.nodes

import its.model.definition.DomainModel
import its.model.definition.DomainUseException
import its.model.definition.procedures.CallableProcedureDef
import its.model.expressions.Operator
import its.model.expressions.operators.CallProcedure
import its.model.nodes.visitors.LinkNodeBehaviour

class ProcedureCallNode(val procedure: CallableProcedureDef, val arguments: List<Operator>,
                        override val outcomes: Outcomes<Boolean>
) : LinkNode<Boolean>() {
    var next: DecisionTreeElement?
        get() = outcomes[0]?.node
        set(node) {
            outcomes.removeAt(0)
            outcomes.add(Outcome(true, node as DecisionTreeNode))
        }

    override val linkedElements: List<DecisionTreeElement>
        get() = listOf(next) as List<DecisionTreeElement>

    override fun validate(
        domainModel: DomainModel,
        results: DecisionTreeValidationResults,
        context: DecisionTreeContext
    ) {
        results.checkValid(
            outcomes.size == 1,
            "Procedure call must have only one outcome"
        )
        results.checkValid(
            outcomes.get(0).key,
            "Procedure call outcome must be `true` value (by contract)"
        )
        if (arguments.size != procedure.arguments.size && !procedure.varArgs) {
            throw DomainUseException("Argument size mismatch for ${procedure.name} (${procedure.javaClass.name}) (${arguments.size} != ${procedure.arguments.size})")
        }
        for (argument in arguments) {
            argument.validateForDecisionTree(domainModel, results, context)
        }
        validateLinked(domainModel, results, context)
    }

    override fun <I> use(behaviour: LinkNodeBehaviour<I>): I {
       return behaviour.process(this)
    }

    fun asExpr(): CallProcedure {
        return CallProcedure(procedure, arguments)
    }
}