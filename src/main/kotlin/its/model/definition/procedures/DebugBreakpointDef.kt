package its.model.definition.procedures

import its.model.definition.types.StringType

class DebugBreakpointDef() : CallableProcedureDef(
    "breakpoint",
    listOf(ProcedureArgument("comment", StringType)),
    namespace = ProcedureNamespaces.DEBUG,
    scopeCapture = false,
)
