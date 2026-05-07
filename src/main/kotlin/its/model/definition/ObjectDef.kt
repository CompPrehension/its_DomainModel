package its.model.definition

import java.util.*

/**
 * Модель объекта в домене ([DomainModel])
 */
class ObjectDef(
    override val name: String,
    val className: String,
) : ClassInheritorDef<ObjectDef>() {

    override val parentClassName: String
        get() = className

    override val description = "object $name"
    override val reference = ObjectRef(name)

    /**
     * Значения свойств для данного объекта
     */
    override val definedPropertyValues = ObjectPropertyValueStatements(this)

    /**
     * Связи данного объекта с другими
     */
    val relationshipLinks = RelationshipLinkStatements(this)

    override fun validate(results: DomainValidationResults) {
        super.validate(results)

        getKnownParentClass(results)

        definedPropertyValues.validate(results)
        relationshipLinks.validate(results)
    }

    //----------------------------------

    override fun plainCopy(name: String) = ObjectDef(name, className)

    override fun mergeEquals(other: ObjectDef): Boolean {
        if (!super.mergeEquals(other)) return false
        return name == other.name
                && className == other.className
    }

    override fun addMerge(other: ObjectDef) {
        super.addMerge(other)
        definedPropertyValues.addAll(other.definedPropertyValues)
        relationshipLinks.addAll(other.relationshipLinks)
    }

    override val isEmpty: Boolean
        get() = super.isEmpty
                && definedPropertyValues.isEmpty()
                && relationshipLinks.isEmpty()

    override fun subtract(other: ObjectDef) {
        super.subtract(other)
        definedPropertyValues.subtract(other.definedPropertyValues)
        relationshipLinks.subtract(other.relationshipLinks)
    }

    //---Операции (на валидном домене)---

    /**
     * Класс данного объекта
     */
    val clazz: ClassDef
        get() = parentClass!!

    fun getRelationshipLink(relationshipName: String): RelationshipLinkStatement {
        return relationshipLinks.stream().filter { link -> link.relationshipName == relationshipName }
            .findFirst()
            .orElseThrow {
                nonConforming("relationship link $relationshipName not found")
            }
    }

    fun hasRelationshipLink(relationshipName: String): Boolean {
        return relationshipLinks.stream().anyMatch { link -> link.relationshipName == relationshipName };
    }

    /**
     * Является ли экземпляром класса
     *
     * (alias для [inheritsFrom])
     */
    fun isInstanceOf(className: String) = inheritsFrom(className)
    /**
     * @see isInstanceOf
     */
    fun isInstanceOf(classDef: ClassDef) = inheritsFrom(classDef)
}

class ObjectContainer(domainModel: DomainModel) : RootDefContainer<ObjectDef>(domainModel) {
    override fun validate(results: DomainValidationResults) {
        super.validate(results)

        //проверка квантификаторов отношений
        val subjects = mutableMapOf<Pair<ObjectDef, RelationshipDef>, Int>()
        val objects = mutableMapOf<Pair<ObjectDef, RelationshipDef>, Int>()
        for (subj in this) {
            for (link in subj.relationshipLinks) {
                val relationship = link.getKnownRelationship(results)
                if (relationship == null || !relationship.isBinary)
                    continue

                val obj = link.getKnownObjects(results).firstOrNull() ?: continue

                objects[subj to relationship] = (objects[subj to relationship] ?: 0) + 1
                subjects[obj to relationship] = (subjects[obj to relationship] ?: 0) + 1
            }
        }
        val relationships = domainModel.classes.flatMap { it.declaredRelationships }.filter { it.isBinary }
        for (relationship in relationships) {
            val subjectClass = relationship.getKnownSubjectClass(results) ?: continue
            val objectClass = relationship.getKnownObjectClasses(results).singleOrNull() ?: continue
            val quantifier = relationship.effectiveQuantifier
            val hasExplicitQuantifier = relationship.kind is BaseRelationshipKind && relationship.kind.quantifier != null

            for (subj in subjectClass.instances) {
                val actualCount = objects[subj to relationship] ?: 0
                results.checkValid(
                    if (hasExplicitQuantifier) quantifier.objCount.accepts(actualCount)
                    else quantifier.objCount.acceptsAsUpperBound(actualCount),
                    "$subj has invalid outgoing links of $relationship: " +
                            "it is a subject of $actualCount links, but the relationship is quantified as $quantifier"
                )
            }

            for (obj in objectClass.instances) {
                val actualCount = subjects[obj to relationship] ?: 0
                results.checkValid(
                    if (hasExplicitQuantifier) quantifier.subjCount.accepts(actualCount)
                    else quantifier.subjCount.acceptsAsUpperBound(actualCount),
                    "$obj has invalid incoming links of $relationship: " +
                            "it is an object of $actualCount links, but the relationship is quantified as $quantifier"
                )
            }
        }
    }
}

class ObjectRef(
    val objectName: String,
) : DomainRef<ObjectDef> {
    override fun findIn(domainModel: DomainModel) = domainModel.objects.get(objectName)
    override fun toString() = "object $objectName"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObjectRef) return false

        if (objectName != other.objectName) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(this::class, objectName)
    }
}
