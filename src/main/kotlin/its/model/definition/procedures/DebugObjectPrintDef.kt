package its.model.definition.procedures

import its.model.definition.types.ObjectType

class DebugObjectPrintDef() : CallableProcedureDef(
    "printObject",
    listOf(ProcedureArgument("obj", ObjectType.untyped())),
    namespace = ProcedureNamespaces.DEBUG,
    ensureMutable = true,
    scopeCapture = false,
)
