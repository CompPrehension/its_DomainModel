package its.model.definition

import its.model.definition.types.EnumType
import mp.utils.MutableMultimapIterator
import mp.utils.getCombinations
import java.util.*

/**
 * Утверждение о значении свойства
 */
class PropertyValueStatement<Owner : ClassInheritorDef<Owner>>(
    override val owner: Owner,
    val propertyName: String,
    val paramsValues: ParamsValues,
    val value: Any,
) : Statement<Owner>() {
    override fun copyForOwner(owner: Owner) = PropertyValueStatement(owner, propertyName, paramsValues, value)

    override val description =
        "statement ${owner.name}.$propertyName${if (paramsValues.isEmpty()) "" else paramsValues.toString()} = $value"

    override fun validate(results: DomainValidationResults) {
        //Существование свойства
        val property = owner.findPropertyDef(propertyName, results)
        if (property == null) {
            results.unknown(
                "No property definition '$propertyName' found to define the value $value in ${owner.description}"
            )
            return
        }

        //Соответствие вида свойства месту определения (внутри класса/объекта)
        results.checkValid(
            property.kind.fits(owner),
            "Cannot define ${property.description} in ${owner.description}"
        )

        if (property.type is EnumType && !property.type.exists(domainModel)) {
            results.unknown(
                "No enum definition '${property.type.enumName}' found to check if a value fits to a enum type"
            )
        } else {
            //Соответствие типа значению
            results.checkValid(
                property.type.fits(value, domainModel),
                "Type of ${property.description} (${property.type}) does not " +
                        "fit the value '$value' defined in ${owner.description}"
            )
        }

        //Валидация параметров (количества и типизации)
        paramsValues.validate(property.paramsDecl, domainModel, this, results)

        //Переопределение значений выше? Но можно сказать что это разрешено
        //Проверка на количество значений не выполняется, т.к. PropertyValueStatements гарантирует уникальность
    }

    fun matches(propertyName: String, valuesMap: Map<String, Any>, paramsDecl: ParamsDecl): Boolean {
        return this.propertyName == propertyName && paramsValues.matchesStrict(valuesMap, paramsDecl)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PropertyValueStatement<*>

        if (owner != other.owner) return false
        if (propertyName != other.propertyName) return false
        if (paramsValues != other.paramsValues) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(owner, propertyName, paramsValues, value)
    }
}

typealias ClassPropertyValueStatement = PropertyValueStatement<ClassDef>
typealias ObjectPropertyValueStatement = PropertyValueStatement<ObjectDef>


class PropertyValueStatements<Owner : ClassInheritorDef<Owner>>(
    owner: Owner,
) : Statements<Owner, PropertyValueStatement<Owner>>(owner) {
    protected val map = linkedMapOf<String, MutableList<PropertyValueStatement<Owner>>>()

    // ParamsDecl зависит от объявлений свойств, поэтому привязан к версии структуры домена.
    private data class ParamsDeclCacheValue(
        val definitionVersion: Long,
        val paramsDecl: ParamsDecl?,
    )

    // Ключ копирует map параметров, чтобы внешний mutable Map не менял уже сохраненный ключ.
    private data class StatementLookupKey(
        val propertyName: String,
        val paramsValuesMap: Map<String, Any>,
    )

    // Результат lookup зависит и от структуры домена, и от самих statement-ов данного owner.
    private data class StatementLookupValue(
        val definitionVersion: Long,
        val statementsVersion: Long,
        val statement: PropertyValueStatement<*>?,
    )

    private val paramsDeclCache = mutableMapOf<String, ParamsDeclCacheValue>()
    private val statementLookupCache = mutableMapOf<StatementLookupKey, StatementLookupValue>()
    private val cacheLock = Any()
    private var statementsVersion: Long = 0

    // Меняются только значения statements; ParamsDecl-кэш при этом остается валидным.
    private fun invalidateStatementLookupCache() {
        synchronized(cacheLock) {
            statementsVersion++
            statementLookupCache.clear()
        }
    }

    override fun addToInner(statement: PropertyValueStatement<Owner>) {
        val existing = getExisting(statement)
        checkValid(
            existing == null || existing.value == statement.value,
            "cannot add ${statement.description}, " +
                    "because ${owner.description} already defines $existing"
        )
        if (existing == null) {
            map.computeIfAbsent(statement.propertyName) { mutableListOf() }.add(statement)
            invalidateStatementLookupCache()
        }
    }

    fun addOrReplace(statement: PropertyValueStatement<Owner>) {
        removeElement(getExisting(statement))
        add(statement)
    }

    override fun removeElement(element: Any?): Boolean {
        if (element !is PropertyValueStatement<*>) return false
        if (contains(element)) {
            val list = map[element.propertyName]!!
            list.remove(element)
            if (list.isEmpty()) {
                map.remove(element.propertyName)
            }
            invalidateStatementLookupCache()
            return true
        }
        return false
    }

    override fun iterator() = MutableMultimapIterator(map) { invalidateStatementLookupCache() }

    override fun clear() {
        if (map.isNotEmpty()) {
            map.clear()
            invalidateStatementLookupCache()
        }
    }

    fun get(propertyName: String, paramsValuesMap: Map<String, Any> = mapOf()): PropertyValueStatement<Owner>? {
        val lookupKey = StatementLookupKey(propertyName, paramsValuesMap.toMap())
        val definitionVersion = domainModel.definitionVersion
        val statementsVersionAtStart: Long
        synchronized(cacheLock) {
            statementsVersionAtStart = statementsVersion
            val cached = statementLookupCache[lookupKey]
            if (
                cached != null &&
                cached.definitionVersion == definitionVersion &&
                cached.statementsVersion == statementsVersionAtStart
            ) {
                @Suppress("UNCHECKED_CAST")
                return cached.statement as PropertyValueStatement<Owner>?
            }
        }

        val paramsDecl = findParamsDecl(propertyName) ?: ParamsDecl()
        val statement = map[propertyName]?.firstOrNull { it.matches(propertyName, paramsValuesMap, paramsDecl) }
        synchronized(cacheLock) {
            if (
                definitionVersion == domainModel.definitionVersion &&
                statementsVersionAtStart == statementsVersion
            ) {
                statementLookupCache[lookupKey] = StatementLookupValue(
                    definitionVersion,
                    statementsVersionAtStart,
                    statement,
                )
            }
        }
        return statement
    }

    private fun getExisting(statement: PropertyValueStatement<*>): PropertyValueStatement<Owner>? {
        val propertyName = statement.propertyName
        val paramsDecl = findParamsDecl(propertyName)
        return map[propertyName]?.firstOrNull {
            if (paramsDecl != null)
                it.paramsValues.matchesStrict(statement.paramsValues, paramsDecl)
            else
                it.paramsValues == statement.paramsValues
        }
    }

    private fun findParamsDecl(propertyName: String): ParamsDecl? {
        val version = domainModel.definitionVersion
        synchronized(cacheLock) {
            paramsDeclCache[propertyName]?.takeIf { it.definitionVersion == version }?.let {
                return it.paramsDecl
            }
        }

        // Здесь намеренно используется накопительная валидация: отсутствие свойства не должно бросать исключение.
        val paramsDecl = owner.findPropertyDef(propertyName, DomainValidationResults())?.paramsDecl
        synchronized(cacheLock) {
            paramsDeclCache[propertyName] = ParamsDeclCacheValue(version, paramsDecl)
        }
        return paramsDecl
    }

    override fun validate(results: DomainValidationResults) {
        super.validate(results)

        //Нет пересечений по параметрам (это не обязательно гарантируется на этапе добавления)
        map.forEach { (propertyName, statementsList) ->
            val paramsDecl = findParamsDecl(propertyName)
            if (paramsDecl != null) {
                statementsList.forEachIndexed { indexA, statementA ->
                    statementsList.subList(indexA + 1, statementsList.size).forEach { statementB ->
                        results.checkValid(
                            !statementB.paramsValues.matchesStrict(statementA.paramsValues, paramsDecl),
                            "Cannot define both $statementA and $statementB," +
                                    " as they define a value for the same property and parameters"
                        )
                    }
                }
            }
        }

        //Определяет все нужные свойства для всех комбинаций параметров
        if (owner is ObjectDef) return //Пока что решили, что объекты не проверяются, и кидается ошибка в рантайме
        if (owner is ClassDef && !owner.isConcrete) return
        val topDownLineage = owner.getKnownInheritanceLineage(results).reversed()
        val undefinedPropertiesAndParams = mutableSetOf<Pair<PropertyDef, Map<String, Any>>>()
        for (clazz in topDownLineage) {
            //добавляем требования
            for (property in clazz.declaredProperties.filter { it.kind.fits(owner) }) {
                val paramsValuesOptions = property.paramsDecl.mapNotNull { it.type.getDiscreteValues(domainModel) }
                if (paramsValuesOptions.size != property.paramsDecl.size) {
                    //Если не все параметры дискретные, то не проверяем - это ошибка само по себе
                    continue
                }
                val paramsValuesCombinations = getCombinations(paramsValuesOptions)
                paramsValuesCombinations.forEach { paramsValues ->
                    val paramsValuesMap = paramsValues.mapIndexed { i, value ->
                        property.paramsDecl[i].name to value
                    }.toMap()
                    undefinedPropertiesAndParams.add(property to paramsValuesMap)
                }
            }
            //Убираем требования если определены
            undefinedPropertiesAndParams.removeIf { (property, parametersValueMap) ->
                clazz.definedPropertyValues.get(property.name, parametersValueMap) != null
            }
        }

        for ((property, paramsValueMap) in undefinedPropertiesAndParams) {
            results.checkKnown(
                get(property.name, paramsValueMap) != null,
                "${owner.description} does not define a value for ${property.description} with params $paramsValueMap"
            )
        }
    }

    override val size: Int
        get() = map.size

    override fun containsElement(element: Any?): Boolean {
        if (element !is PropertyValueStatement<*>) return false
        return getExisting(element) == element
    }

    override fun containsAll(elements: Collection<PropertyValueStatement<Owner>>) = elements.all { contains(it) }
    override fun isEmpty() = map.isEmpty()
}

typealias ClassPropertyValueStatements = PropertyValueStatements<ClassDef>
typealias ObjectPropertyValueStatements = PropertyValueStatements<ObjectDef>
