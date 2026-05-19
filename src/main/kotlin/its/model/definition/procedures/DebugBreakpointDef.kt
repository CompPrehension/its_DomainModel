package its.model.definition.procedures

import its.model.definition.types.AnyType
import its.model.definition.types.StringType

class DebugBreakpointDef() : CallableProcedureDef(
    "breakpoint",
    listOf(ProcedureArgument("comment", StringType)),
    namespace = ProcedureNamespaces.DEBUG,
    returnType = AnyType,
    scopeCapture = false,
)
