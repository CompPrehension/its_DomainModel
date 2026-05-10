package its.model.definition.procedures

import its.model.definition.types.AnyType

class DebugPointDef() : CallableProcedureDef(
    "print",
    listOf(ProcedureArgument("comment", AnyType)),
    namespace = ProcedureNamespaces.DEBUG,
    scopeCapture = false,
)
