package its.model.definition.loqi.tree

import its.model.definition.types.StringType

class DebugPointDef(): CallableProcedureDef(
    "print",
    listOf(ProcedureArgument("comment", StringType)),
    scopeCapture = false
)