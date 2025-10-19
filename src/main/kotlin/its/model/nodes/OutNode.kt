package its.model.nodes

import its.model.definition.loqi.LoqiDomainBuildException
import its.model.nodes.visitors.DecisionTreeBehaviour

class OutNode(override val linkedElements: List<DecisionTreeElement> = listOf()) : DecisionTreeNode() {
    // Вспомогательный узел для специфики языка loqi

    override fun <I> use(behaviour: DecisionTreeBehaviour<I>): I {
        throw LoqiDomainBuildException("Dummy node")
    }
}