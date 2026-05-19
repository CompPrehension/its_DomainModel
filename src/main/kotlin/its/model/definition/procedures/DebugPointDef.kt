package its.model.definition.procedures

import its.model.definition.types.AnyType

class DebugPointDef() : CallableProcedureDef(
    "print",
    listOf(ProcedureArgument("obj", AnyType)),
    namespace = ProcedureNamespaces.DEBUG,
    returnType = AnyType,
    scopeCapture = false,
)
