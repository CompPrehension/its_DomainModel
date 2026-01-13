package its.model.definition.loqi.tree

import its.model.definition.types.StringType

class DebugPointDef(): CallableProcedureDef(
    "comment",
    listOf(ProcedureArgument("comment", StringType)),
    scopeCapture = false
)