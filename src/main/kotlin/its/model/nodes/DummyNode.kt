package its.model.nodes

import its.model.definition.loqi.LoqiDomainBuildException
import its.model.nodes.visitors.DecisionTreeBehaviour

/**
 * Ничего не делающий, тупиковый узел
 */
class DummyNode() : DecisionTreeNode(), EndingNode {
    override val linkedElements: List<DecisionTreeElement> = listOf()
    // Вспомогательный узел, который не делает ничего.
    // Применяется для служебных целей и парсинга. Не должен использоваться в готовом дереве

    override fun <I> use(behaviour: DecisionTreeBehaviour<I>): I {
        throw LoqiDomainBuildException("Dummy node")
    }
}