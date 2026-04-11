package its.model.definition.loqi

fun interface DecisionTreeVarNameResolver {
    fun resolve(name: String): String

    companion object {
        @JvmField
        val IDENTITY = DecisionTreeVarNameResolver { it }
    }
}
