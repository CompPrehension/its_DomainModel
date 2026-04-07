package its.model.definition.procedures

import its.model.definition.types.StringType

class DebugPointDef(): CallableProcedureDef(
    "point",
    listOf(ProcedureArgument("comment", StringType)),
    scopeCapture = false, null
)