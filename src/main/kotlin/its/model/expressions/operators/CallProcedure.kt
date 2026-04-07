package its.model.expressions.operators

import its.model.definition.DomainModel
import its.model.definition.loqi.tree.CallableProcedureDef
import its.model.definition.types.Type
import its.model.expressions.ExpressionContext
import its.model.expressions.ExpressionValidationResults
import its.model.expressions.Operator
import its.model.expressions.visitors.OperatorBehaviour

class CallProcedure(val procedure: CallableProcedureDef,
                    override val children: List<Operator>
): Operator() {

    val args: List<Operator>
        get() = args

    override fun validateAndGetType(
        domainModel: DomainModel,
        results: ExpressionValidationResults,
        context: ExpressionContext
    ): Type<*> {
        results.checkValid(procedure.returnType == null, "Procedure with no return type can't be used as expression");
        return procedure.returnType!!;
    }

    override fun <I> use(behaviour: OperatorBehaviour<I>): I {
        return behaviour.process(this)
    }

}