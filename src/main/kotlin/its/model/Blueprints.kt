package its.model

import its.model.definition.ObjectDef
import its.model.definition.ParamsValues
import its.model.definition.PropertyValueStatement
import its.model.definition.RelationshipLinkStatement
import its.model.expressions.Operator

@FunctionalInterface
interface BlueprintContextProvider {
    fun provide(ctx: Blueprint<*>, name: String): Any
}

interface Blueprint<T> {
    fun build(ctx: BlueprintContextProvider): T
}

data class RelationshipLinkBlueprint(
    val name: String, val value: List<Operator>, val params: ParamsValues
) : Blueprint<RelationshipLinkStatement> {

    override fun build(ctx: BlueprintContextProvider): RelationshipLinkStatement {
        return RelationshipLinkStatement(
            ctx.provide(this, "owner") as ObjectDef,
            name,
            ctx.provide(this, "names") as List<String>,
            params,
        )
    }
}

data class ObjectPropertyValueBlueprint(
    val name: String, val value: Operator, val params: ParamsValues
) : Blueprint<PropertyValueStatement<*>> {
    override fun build(ctx: BlueprintContextProvider): PropertyValueStatement<ObjectDef> {
        return PropertyValueStatement(ctx.provide(this, "owner") as ObjectDef,
            name, params, ctx.provide(this, "value"))
    }
}

class ObjectDefBlueprint(val className: String,
                         val properties: MutableList<ObjectPropertyValueBlueprint> = mutableListOf(),
                         val relationships: MutableList<RelationshipLinkBlueprint> = mutableListOf()): Blueprint<ObjectDef> {
    override fun build(ctx: BlueprintContextProvider): ObjectDef {
        val obj = ObjectDef(ctx.provide(this, "objectName") as String, className)
        val newCtx = {bp: Blueprint<*>, name: String ->
            if (name == "owner") obj
            else ctx.provide(bp, name)
        } as BlueprintContextProvider
        obj.definedPropertyValues.addAll(properties.map { p -> p.build(newCtx) })
        obj.relationshipLinks.addAll(relationships.map { r -> r.build(newCtx) })
        return obj
    }
}