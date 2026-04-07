package its.model.definition.loqi.tree

import its.model.definition.types.StringType

class DebugDumpPointDef(): CallableProcedureDef(
    "dump",
    listOf(ProcedureArgument("comment", StringType)),
    scopeCapture = true, null
)