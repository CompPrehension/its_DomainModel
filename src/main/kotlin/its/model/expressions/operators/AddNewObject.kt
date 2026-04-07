package its.model.expressions.operators

import its.model.definition.DomainModel
import its.model.definition.DomainValidationResults
import its.model.definition.ObjectDef
import its.model.definition.types.ObjectType
import its.model.definition.types.Type
import its.model.expressions.ExpressionContext
import its.model.expressions.ExpressionValidationResults
import its.model.expressions.Operator
import its.model.expressions.visitors.OperatorBehaviour

class AddNewObject(
    val objectDef: ObjectDef
) : Operator() {
    override val children: List<Operator>
        get() = listOf()

    override fun validateAndGetType(
        domainModel: DomainModel,
        results: ExpressionValidationResults,
        context: ExpressionContext
    ): Type<*> {
        objectDef.validate(DomainValidationResults())
        return ObjectType(objectDef.className)
    }

    override fun <I> use(behaviour: OperatorBehaviour<I>): I {
        return behaviour.process(this)
    }
}