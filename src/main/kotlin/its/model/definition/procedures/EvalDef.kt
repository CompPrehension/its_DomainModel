package its.model.definition.procedures

import its.model.definition.types.AnyType

class EvalDef : CallableProcedureDef(
    "eval",
    listOf(ProcedureArgument("expr", AnyType)),
    ensureMutable = true,
    scopeCapture = false,
)
