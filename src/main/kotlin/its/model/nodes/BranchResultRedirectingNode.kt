package its.model.nodes

import its.model.definition.DomainModel
import its.model.definition.procedures.SubinterpreterProcedure
import its.model.expressions.Operator
import its.model.expressions.operators.CallProcedure
import its.model.nodes.visitors.DecisionTreeBehaviour

/**
 * Узел завершения, который получает результат из субинтерпретатора
 */
class BranchResultRedirectingNode(
    val call: CallProcedure, val actionExpr: Operator?,
) : DecisionTreeNode(), EndingNode {

    override val linkedElements: List<DecisionTreeElement>
        get() = listOf()

    override fun validate(
        domainModel: DomainModel,
        results: DecisionTreeValidationResults,
        context: DecisionTreeContext
    ) {
        actionExpr?.also { it.validateForDecisionTree(domainModel, results, context) }
        results.checkValid(call.procedure is SubinterpreterProcedure, "Branch result must be redirecting only with subinterpreter procedures")
    }


    override fun <I> use(behaviour: DecisionTreeBehaviour<I>): I {
        return behaviour.process(this)
    }
}