package its.model.definition.procedures

interface ProcedureRegistry {
    fun resolve(namespaceResolution: List<String>, procedureName: String): CallableProcedureDef?
    fun resolve(className: String): CallableProcedureDef?
}

object BuiltinProcedureRegistry : ProcedureRegistry {
    private val procedures: List<CallableProcedureDef> = listOf(
        AssertPointDef(),
        EvalDef(),
        DebugDumpPointDef(),
        DebugPointDef(),
        DebugObjectPrintDef(),
        SubinterpreterCall(),
        MutableSubinterpreterCall(),
    )

    override fun resolve(namespaceResolution: List<String>, procedureName: String): CallableProcedureDef? {
        return procedures.firstOrNull { procedure ->
            procedure.name == procedureName && procedure.namespace.matches(namespaceResolution)
        }
    }

    override fun resolve(className: String): CallableProcedureDef? {
        return procedures.firstOrNull { procedure ->
            procedure.javaClass.name == className || procedure.javaClass.simpleName == className
        }
    }
}
