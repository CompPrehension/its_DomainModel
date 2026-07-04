package its.model.expressions.operators

import its.model.definition.DomainModel
import its.model.definition.DomainUseException
import its.model.definition.procedures.CallableProcedureDef
import its.model.definition.types.Type
import its.model.expressions.ExpressionContext
import its.model.expressions.ExpressionValidationResults
import its.model.expressions.Operator
import its.model.expressions.visitors.OperatorBehaviour

class CallProcedure(val procedure: CallableProcedureDef,
                    override val children: List<Operator>
): Operator() {

    val arguments: List<Operator>
        get() = children

    override fun validateAndGetType(
        domainModel: DomainModel,
        results: ExpressionValidationResults,
        context: ExpressionContext
    ): Type<*> {
        results.checkValid(procedure.returnType == null, "Procedure with no return type can't be used as expression");
        if (arguments.size != procedure.arguments.size && !procedure.varArgs) {
            throw DomainUseException("Argument size mismatch for ${procedure.name} (${procedure.javaClass.name}) (${arguments.size} != ${procedure.arguments.size})")
        }
        for (argument in arguments) {
            argument.validateAndGetType(domainModel, results, context)
        }
        return procedure.returnType!!;
    }

    override fun <I> use(behaviour: OperatorBehaviour<I>): I {
        return behaviour.process(this)
    }

}