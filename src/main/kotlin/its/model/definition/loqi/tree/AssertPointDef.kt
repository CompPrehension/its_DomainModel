package its.model.definition.loqi.tree

import its.model.definition.types.BooleanType
import its.model.definition.types.StringType

class AssertPointDef(): CallableProcedureDef("assert",
    listOf(ProcedureArgument("condition", BooleanType),
                        ProcedureArgument("message", StringType)),
                scopeCapture = false, null
)