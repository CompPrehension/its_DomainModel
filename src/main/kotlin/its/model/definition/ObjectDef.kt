package its.model.definition

import mp.utils.findCycles
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
    private data class ObjectClassIndex(
        val version: Long,
        // Таблица объектов по имени каждого класса из их lineage.
        val objectsByClassName: Map<String, List<ObjectDef>>,
    )

    @Volatile
    private var objectClassIndexCache: ObjectClassIndex? = null
    private val objectClassIndexCacheLock = Any()

    private fun objectClassIndex(): ObjectClassIndex {
        val version = domainModel.definitionVersion
        val cached = objectClassIndexCache
        if (cached != null && cached.version == version) return cached

        return synchronized(objectClassIndexCacheLock) {
            val lockedCached = objectClassIndexCache
            if (lockedCached != null && lockedCached.version == version) lockedCached else buildObjectClassIndex(version).also {
                objectClassIndexCache = it
            }
        }
    }

    private fun buildObjectClassIndex(version: Long): ObjectClassIndex {
        val objectsByClassName = linkedMapOf<String, MutableList<ObjectDef>>()
        for (objectDef in this) {
            for (clazz in objectDef.getInheritanceLineage()) {
                objectsByClassName.computeIfAbsent(clazz.name) { mutableListOf() }.add(objectDef)
            }
        }
        return ObjectClassIndex(
            version,
            objectsByClassName.mapValues { (_, objects) -> objects.toList() },
        )
    }

    fun objectsAssignableTo(className: String): List<ObjectDef> {
        return objectClassIndex().objectsByClassName[className] ?: emptyList()
    }

    override fun validate(results: DomainValidationResults) {
        super.validate(results)

        //проверка квантификаторов отношений
        val subjects = mutableMapOf<Pair<ObjectDef, RelationshipDef>, Int>()
        val objects = mutableMapOf<Pair<ObjectDef, RelationshipDef>, Int>()
        //связи "субъект -> объект" по шкальным отношениям, для проверки ацикличности
        val scaleLinks = mutableMapOf<RelationshipDef, MutableMap<ObjectDef, ObjectDef>>()
        for (subj in this) {
            for (link in subj.relationshipLinks) {
                val relationship = link.getKnownRelationship(results)
                if (relationship == null || !relationship.isBinary)
                    continue

                val obj = link.getKnownObjects(results).firstOrNull() ?: continue

                objects[subj to relationship] = (objects[subj to relationship] ?: 0) + 1
                subjects[obj to relationship] = (subjects[obj to relationship] ?: 0) + 1

                if (relationship.isScalar) {
                    //Лишние связи здесь игнорируются - о них сообщит проверка квантификаторов
                    scaleLinks.computeIfAbsent(relationship) { mutableMapOf() }.putIfAbsent(subj, obj)
                }
            }
        }
        for ((subjToRel, count) in objects) {
            val subj = subjToRel.first
            val relationship = subjToRel.second

            val quantifier = relationship.effectiveQuantifier
            results.checkValid(
                objects[subj to relationship]!! <= quantifier.objCount,
                "$subj has too many outgoing links of $relationship: " +
                        "it is a subject of $count links, but the relationship is quantified as $quantifier"
            )
        }
        for ((objToRel, count) in subjects) {
            val obj = objToRel.first
            val relationship = objToRel.second

            val quantifier = relationship.effectiveQuantifier
            results.checkValid(
                subjects[obj to relationship]!! <= quantifier.subjCount,
                "$obj has too many incoming links of $relationship: " +
                        "it is an object of $count links, but the relationship is quantified as $quantifier"
            )
        }

        //проверка ацикличности структур, задаваемых шкалами
        checkScalesAreAcyclic(scaleLinks, results)
    }

    /**
     * Проверить, что структуры, образуемые отношениями со шкалой ([RelationshipDef.isScalar]), не содержат циклов.
     *
     * Шкала требует, чтобы связанные ей объекты выстраивались в линию (линейная шкала) или в дерево (частичная),
     * а значит цепочки связей не должны замыкаться. Квантификаторы этого не гарантируют:
     * цикл `a => rel(b)`, `b => rel(c)`, `c => rel(a)` не нарушает ни `{1 -> 1}`, ни `{* -> 1}`.
     *
     * @param linksByRelationship для каждого шкального отношения - его связи в виде "субъект -> объект"
     */
    private fun checkScalesAreAcyclic(
        linksByRelationship: Map<RelationshipDef, Map<ObjectDef, ObjectDef>>,
        results: DomainValidationResults,
    ) {
        for ((relationship, links) in linksByRelationship) {
            val scaleType = (relationship.kind as BaseRelationshipKind).scaleType
            for (cycle in findCycles(links.keys) { links[it] }) {
                results.invalid(
                    "$relationship has to form an acyclic structure as it is declared as $scaleType, " +
                            "but its links form a cycle: " +
                            (cycle + cycle.first()).joinToString(" => ") { it.name }
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
