package its.model.definition

/**
 * Общий класс для классов и объектов - реализация наследования от классов
 */
sealed class ClassInheritorDef<Self : ClassInheritorDef<Self>> : DomainDefWithMeta<Self>() {

    protected abstract val parentClassName: String?
    abstract val definedPropertyValues: PropertyValueStatements<Self>

    // Снимок наследуемых данных для быстрых runtime-запросов; накопительная валидация ниже остается без кэша.
    private data class InheritedData(
        val version: Long,
        val lineage: List<ClassDef>,
        val allProperties: List<PropertyDef>,
        val allRelationships: List<RelationshipDef>,
        val propertyByName: Map<String, PropertyDef>,
        val relationshipByName: Map<String, RelationshipDef>,
    )

    @Volatile
    private var inheritedDataCache: InheritedData? = null
    private val inheritedDataCacheLock = Any()

    private fun inheritedData(): InheritedData {
        val version = domainModel.definitionVersion
        val cached = inheritedDataCache
        if (cached != null && cached.version == version) return cached

        return synchronized(inheritedDataCacheLock) {
            val lockedCached = inheritedDataCache
            if (lockedCached != null && lockedCached.version == version) lockedCached else buildInheritedData(version).also {
                inheritedDataCache = it
            }
        }
    }

    private fun buildInheritedData(version: Long): InheritedData {
        val lineage = getKnownInheritanceLineage(DomainValidationResultsThrowImmediately())

        val allProperties = ArrayList<PropertyDef>(lineage.sumOf { it.declaredProperties.size })
        val propertyByName = linkedMapOf<String, PropertyDef>()
        for (clazz in lineage) {
            for (property in clazz.declaredProperties) {
                allProperties.add(property)
                // Первый найденный элемент ближе к текущему классу и повторяет прежнюю логику поиска по lineage.
                propertyByName.putIfAbsent(property.name, property)
            }
        }

        val allRelationships = ArrayList<RelationshipDef>(lineage.sumOf { it.declaredRelationships.size })
        val relationshipByName = linkedMapOf<String, RelationshipDef>()
        for (clazz in lineage) {
            for (relationship in clazz.declaredRelationships) {
                allRelationships.add(relationship)
                // Первый найденный элемент ближе к текущему классу и повторяет прежнюю логику поиска по lineage.
                relationshipByName.putIfAbsent(relationship.name, relationship)
            }
        }

        return InheritedData(
            version,
            lineage,
            allProperties,
            allRelationships,
            propertyByName,
            relationshipByName,
        )
    }

    /**
     * Для валидации - получить класс-родитель,
     * или добавить сообщение о его неизвестности в [results]
     */
    internal fun getKnownParentClass(results: DomainValidationResults): ClassDef? {
        if (parentClassName == null) return null

        val clazz = domainModel.classes.get(parentClassName!!)
        results.checkKnown(
            clazz != null,
            "No class definition '${parentClassName}' found to be defined as $name's parent class"
        )
        return clazz
    }

    /**
     * Для валидации - получить известную цепочку классов-родителей
     * (включая данный класс, если этот метод вызывается для класса)
     * добавляя сообщение о неизвестных родителях в [results], если такие есть
     */
    internal fun getKnownInheritanceLineage(results: DomainValidationResults): List<ClassDef> {
        val lineage = ArrayList<ClassDef>(4)
        var p = if (this is ClassDef) this else getKnownParentClass(results)
        while (p != null) {
            lineage.add(p)
            p = p.getKnownParentClass(results)
            if (p === this) {
                results.invalid("$description is a supertype of itself (lineage is ${lineage.map { it.name }})")
                break
            }
        }
        return lineage
    }

    /**
     * Валидация - найти определение свойства по имени; любые ошибки кладутся в [results]
     */
    internal fun findPropertyDef(propertyName: String, results: DomainValidationResults): PropertyDef? {
        if (results is DomainValidationResultsThrowImmediately) {
            // Быстрый путь только для операций на валидном домене; обычная валидация должна накопить все ошибки.
            return inheritedData().propertyByName[propertyName]
        }
        for (clazz in getKnownInheritanceLineage(results)) {
            val found = clazz.declaredProperties.get(propertyName)
            if (found != null) return found
        }
        return null
    }

    /**
     * Валидация - найти определение отношения по имени; любые ошибки кладутся в [results]
     */
    internal fun findRelationshipDef(
        relationshipName: String,
        results: DomainValidationResults
    ): RelationshipDef? {
        if (results is DomainValidationResultsThrowImmediately) {
            // Быстрый путь только для операций на валидном домене; обычная валидация должна накопить все ошибки.
            return inheritedData().relationshipByName[relationshipName]
        }
        for (clazz in getKnownInheritanceLineage(results)) {
            val found = clazz.declaredRelationships.get(relationshipName)
            if (found != null) return found
        }
        return null
    }

    //---Операции (на валидном домене)---

    /**
     * Родительcкий класс данной сущности
     *
     * *(Для объектов данное свойство всегда присутствует (не null) - используй [ObjectDef.clazz])*
     */
    val parentClass: ClassDef?
        get() = getKnownParentClass(DomainValidationResultsThrowImmediately())

    /**
     * Получить цепочку классов объекта
     */
    fun getInheritanceLineage() = inheritedData().lineage.toMutableList()

    /**
     * Наследуется ли от класса
     */
    fun inheritsFrom(className: String) = getInheritanceLineage().any { it.name == className }

    /**
     * Наследуется ли от класса
     */
    fun inheritsFrom(classDef: ClassDef) = getInheritanceLineage().contains(classDef)

    /**
     * Все определенные для данной сущности свойства
     */
    val allProperties: List<PropertyDef>
        get() {
            return inheritedData().allProperties.toMutableList()
        }

    /**
     * Найти определение свойства по имени, с учетом наследования
     */
    fun findPropertyDef(propertyName: String) =
        findPropertyDef(propertyName, DomainValidationResultsThrowImmediately())

    /**
     * Все определенные для данной сущности отношения
     */
    val allRelationships: List<RelationshipDef>
        get() {
            return inheritedData().allRelationships.toMutableList()
        }

    /**
     * Найти определение отношения по имени, с учетом наследования
     */
    fun findRelationshipDef(relationshipName: String) =
        findRelationshipDef(relationshipName, DomainValidationResultsThrowImmediately())

    /**
     * Получить значение свойства с учетом наследования
     * @throws DomainNonConformityException если такого свойства не существует
     */
    fun getPropertyValue(propertyName: String, paramsValuesMap: Map<String, Any> = mapOf()): Any {
        checkConforming(
            findPropertyDef(propertyName, DomainValidationResultsThrowImmediately()) != null,
            "No property $propertyName exists for $description"
        )
        val defined = definedPropertyValues.get(propertyName, paramsValuesMap)
        if (defined != null) return defined.value
        for (clazz in inheritedData().lineage) {
            val found = clazz.definedPropertyValues.get(propertyName, paramsValuesMap)
            if (found != null) return found.value
        }
        nonConforming(
            "$description does not define a value for property '$propertyName'" +
                    if (paramsValuesMap.isEmpty()) "" else " and params ${NamedParamsValues(paramsValuesMap)}"
        )
    }
}
