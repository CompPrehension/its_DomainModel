package its.model

import its.model.definition.*
import its.model.expressions.Operator

@FunctionalInterface
interface BlueprintContextProvider {
    fun provide(ctx: Blueprint<*>, name: String): Any
}

interface Blueprint<T> {
    fun build(domain: DomainModel, ctx: BlueprintContextProvider): T
}

data class RelationshipLinkBlueprint(
    val name: String, val value: List<Operator>, val params: ParamsValues, val applyIf: Operator
) : Blueprint<RelationshipLinkStatement?> {

    override fun build(owner: DomainModel, ctx: BlueprintContextProvider): RelationshipLinkStatement? {
        if (!(ctx.provide(this, "applyIf") as Boolean)) {
            return null
        }
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
    override fun build(owner: DomainModel, ctx: BlueprintContextProvider): PropertyValueStatement<ObjectDef> {
        return PropertyValueStatement(ctx.provide(this, "owner") as ObjectDef,
            name, params, ctx.provide(this, "value"))
    }
}

class ObjectDefBlueprint(val className: String,
                         val properties: MutableList<ObjectPropertyValueBlueprint> = mutableListOf(),
                         val relationships: MutableList<RelationshipLinkBlueprint> = mutableListOf()): Blueprint<ObjectDef> {
    override fun build(domain: DomainModel, ctx: BlueprintContextProvider): ObjectDef {
        val obj = ObjectDef(ctx.provide(this, "objectName") as String, className)
        val newCtx = object : BlueprintContextProvider {
            override fun provide(bp: Blueprint<*>, name: String): Any {
                return if (name == "owner") obj
                else ctx.provide(bp, name)
            }
        }
        obj.domainModel = domain;
        obj.definedPropertyValues.addAll(properties.map { p -> p.build(domain, newCtx) })
        obj.relationshipLinks.addAll(relationships.mapNotNull { r -> r.build(domain, newCtx) })
        return obj
    }
}