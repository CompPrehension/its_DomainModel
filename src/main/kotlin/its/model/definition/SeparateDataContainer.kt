package its.model.definition

/**
 * Контейнер для отдельно хранимых данных (конструкции 'values for ...' и 'meta for ...' в LOQI)
 */
sealed class SeparateDataContainer<Key : DomainRef<*>, Value : Any>(
    override val domainModel: DomainModel
) : DomainElement(), Map<Key, Value> {
    protected val map: MutableMap<Key, Value> = mutableMapOf()

    /**
     * Добавить данные (или сразу же прикрепить их к владельцу, если он есть)
     */
    fun add(key: Key, value: Value) {
        val owner = key.findIn(domainModel)
        if (owner != null) {
            attachValue(owner, value)
        } else if (map.containsKey(key)) {
            map[key] = mergeValues(map[key]!!, value)
        } else {
            map[key] = value
        }
    }

    protected open fun mergeValues(old: Value, new: Value): Value {
        return new
    }

    fun addAll(other: Map<Key, Value>) = other.forEach { (k, v) -> add(k, v) }

    fun remove(key: Key) {
        map.remove(key)
    }

    protected abstract fun subtractValues(existing: Value, other: Value): Boolean

    fun subtract(other: SeparateDataContainer<Key, Value>) {
        for ((k, v) in other) {
            val existing = get(k) ?: continue
            val shouldDelete = subtractValues(existing, v)
            if (shouldDelete) {
                remove(k)
            }
        }
    }

    /**
     * Забрать из контейнера и прикрепить данные, принадлежащие владельцу [domainDef], если такие есть
     */
    fun claimIfPresent(domainDef: DomainDef<*>) {
        val ref = domainDef.reference
        if (map.containsKey(ref)) {
            val value = map.remove(ref)!!
            attachValue(domainDef, value)
        }
    }

    /**
     * "Прикрепить" данные [value] к владельцу [domainDef]
     */
    abstract fun attachValue(domainDef: DomainDef<*>, value: Value)

    override val entries: Set<Map.Entry<Key, Value>>
        get() = map.entries
    override val keys: Set<Key>
        get() = map.keys
    override val size: Int
        get() = map.size
    override val values: Collection<Value>
        get() = map.values

    override fun containsKey(key: Key) = map.containsKey(key)
    override fun containsValue(value: Value) = map.containsValue(value)
    override fun get(key: Key) = map[key]
    override fun isEmpty() = map.isEmpty()
}

/**
 * Значения свойств, заданные отдельно от класса; проверяются, когда класс находится и они к нему прикрепляются
 */
class SeparateClassPropertyValuesContainer(
    domainModel: DomainModel
) : SeparateDataContainer<ClassRef, MutableList<ClassPropertyValueStatement>>(domainModel) {

    override fun mergeValues(
        old: MutableList<ClassPropertyValueStatement>,
        new: MutableList<ClassPropertyValueStatement>
    ): MutableList<ClassPropertyValueStatement> {
        return old.also { it.addAll(new) }
    }

    override fun subtractValues(
        existing: MutableList<ClassPropertyValueStatement>,
        other: MutableList<ClassPropertyValueStatement>
    ): Boolean {
        existing.removeAll { statement -> other.any { it.definesSameValueAs(statement) } }
        return existing.isEmpty()
    }

    // Владельцы отдельных значений - временные классы, поэтому сравнивается только само утверждение.
    private fun ClassPropertyValueStatement.definesSameValueAs(other: ClassPropertyValueStatement) =
        propertyName == other.propertyName && paramsValues == other.paramsValues && value == other.value

    override fun attachValue(domainDef: DomainDef<*>, value: MutableList<ClassPropertyValueStatement>) {
        if (domainDef !is ClassDef) return
        domainDef.definedPropertyValues.addAll(value)
    }

    override fun validate(results: DomainValidationResults) {
        for (classRef in map.keys) {
            results.unknown("No class '${classRef.className}' found to attach separate property values")
        }
    }
}

class SeparateMetadataContainer(
    domainModel: DomainModel
) : SeparateDataContainer<DomainRef<*>, MetaData>(domainModel) {
    override fun mergeValues(old: MetaData, new: MetaData): MetaData {
        return old.also { it.addAll(new) }
    }

    override fun subtractValues(existing: MetaData, other: MetaData): Boolean {
        existing.subtract(other)
        return existing.isEmpty()
    }

    override fun attachValue(domainDef: DomainDef<*>, value: MetaData) {
        if (domainDef !is DomainDefWithMeta<*>) return
        domainDef.metadata.addAll(value)
    }

    override fun validate(results: DomainValidationResults) {
        for (classRef in map.keys) {
            results.unknown("No definition matching reference '$classRef' found to attach separate metadata")
        }
    }
}