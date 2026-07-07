package its.model.nodes

/**
 * Обход в глубину всех [DecisionTreeElement], достижимых из [this] (включая сам [this])
 */
private fun DecisionTreeElement.allElements(): Sequence<DecisionTreeElement> = sequence {
    yield(this@allElements)
    linkedElements.forEach { yieldAll(it.allElements()) }
}

/**
 * Все [DecisionTreeNode] дерева решений (узлы вопросов, результатов ветвей, агрегаций и т.д.),
 * без "служебных" элементов вроде [ThoughtBranch]
 */
fun DecisionTree.allNodes(): Sequence<DecisionTreeNode> =
    allElements().filterIsInstance<DecisionTreeNode>()

/**
 * Одна запись метаданных узла: имя свойства, код локализации (null для нелокализованных) и значение
 */
data class DecisionTreeNodeMetadataEntry(
    val name: String,
    val locCode: String?,
    val value: Any,
)

/**
 * Представление узла для отображения/сериализации: имя конкретного типа узла и все его метаданные
 * (включая локализованные и `line`, если она присутствует)
 */
data class DecisionTreeNodeView(
    val nodeType: String,
    val metadata: List<DecisionTreeNodeMetadataEntry>,
)

fun DecisionTreeNode.toView(): DecisionTreeNodeView {
    val entries = metadata.entries
        .map { DecisionTreeNodeMetadataEntry(it.propertyName, it.locCode, it.value) }
        .sortedWith(compareBy({ it.name }, { it.locCode ?: "" }))
    return DecisionTreeNodeView(this::class.simpleName ?: "DecisionTreeNode", entries)
}

/**
 * Представление узла в виде карты, готовой для JSON-сериализации: `nodeType` и массив `metadata`
 * из объектов `{name, locCode, value}`. Саму сериализацию в текст выполняет вызывающий код.
 */
fun DecisionTreeNodeView.toJsonMap(): Map<String, Any?> = mapOf(
    "nodeType" to nodeType,
    "metadata" to metadata.map { entry ->
        mapOf(
            "name" to entry.name,
            "locCode" to entry.locCode,
            "value" to entry.value,
        )
    },
)

/**
 * Человекочитаемое многострочное представление [DecisionTreeNodeView]
 */
fun DecisionTreeNodeView.toHumanString(): String {
    if (metadata.isEmpty()) {
        return nodeType
    }
    return buildString {
        append(nodeType)
        metadata.forEach { entry ->
            val label = if (entry.locCode != null) "${entry.name}[${entry.locCode}]" else entry.name
            append("\n  $label = ${entry.value}")
        }
    }
}

/**
 * [DecisionTreeNode]-узлы среди [this], если он уже является узлом (например, цель redirect у
 * [ProcedureCallNode]), либо среди его собственных [DecisionTreeElement.linkedElements]
 * (разворачивает "служебные" элементы вроде [Outcome]/[ThoughtBranch] на один уровень)
 */
private fun DecisionTreeElement.immediateNodes(): List<DecisionTreeNode> =
    if (this is DecisionTreeNode) listOf(this) else linkedElements.filterIsInstance<DecisionTreeNode>()

/**
 * Непосредственные (глубины 1) дочерние узлы [this] - то есть узлы, до которых можно дойти за один
 * переход по дереву решений (через ветви/исходы или прямые ссылки, как в [ProcedureCallNode])
 */
fun DecisionTreeNode.childNodes(): List<DecisionTreeNode> =
    linkedElements.flatMap { it.immediateNodes() }

/**
 * Дескриптор именованного узла: значения его метаданных `id`, `line` и `skill` (только те, что заданы)
 */
data class DecisionTreeNodeDescriptor(
    val id: Any?,
    val line: Any?,
    val skill: Any?,
)

/**
 * Строит [DecisionTreeNodeDescriptor] из метаданных `id`/`line`/`skill` узла, либо `null`,
 * если ни одно из этих трёх свойств не задано
 */
fun DecisionTreeNode.toDescriptorOrNull(): DecisionTreeNodeDescriptor? {
    val id = metadata["id"]
    val line = metadata["line"]
    val skill = metadata["skill"]
    if (id == null && line == null && skill == null) return null
    return DecisionTreeNodeDescriptor(id, line, skill)
}

/**
 * Сводка о дочерних узлах [this]: общее число непосредственных дочерних узлов и дескрипторы
 * именованных из них (узлы без `id`/`line`/`skill` в дескрипторы не попадают, но учитываются в [total])
 */
data class DecisionTreeNodeChildren(
    val total: Int,
    val descriptors: List<DecisionTreeNodeDescriptor>,
)

fun DecisionTreeNode.childrenSummary(): DecisionTreeNodeChildren {
    val children = childNodes()
    return DecisionTreeNodeChildren(
        total = children.size,
        descriptors = children.mapNotNull { it.toDescriptorOrNull() },
    )
}

fun DecisionTreeNodeDescriptor.toJsonMap(): Map<String, Any?> = buildMap {
    id?.let { put("id", it) }
    line?.let { put("line", it) }
    skill?.let { put("skill", it) }
}

fun DecisionTreeNodeChildren.toJsonMap(): Map<String, Any?> = mapOf(
    "total" to total,
    "descriptors" to descriptors.map { it.toJsonMap() },
)

fun DecisionTreeNodeDescriptor.toHumanString(): String =
    listOfNotNull(
        id?.let { "id=$it" },
        line?.let { "line=$it" },
        skill?.let { "skill=$it" },
    ).joinToString(", ")

fun DecisionTreeNodeChildren.toHumanString(): String = buildString {
    append("Children: $total total")
    descriptors.forEach { descriptor ->
        append("\n  - ${descriptor.toHumanString()}")
    }
}
