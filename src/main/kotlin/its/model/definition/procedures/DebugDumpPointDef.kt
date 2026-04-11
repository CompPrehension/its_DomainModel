package its.model.definition.procedures

import its.model.definition.types.StringType

class DebugDumpPointDef() : CallableProcedureDef(
    "dump",
    listOf(ProcedureArgument("comment", StringType)),
    namespace = ProcedureNamespaces.DEBUG,
    scopeCapture = true,
)
