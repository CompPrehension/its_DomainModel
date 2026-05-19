package its.model.definition.procedures

import its.model.definition.types.AnyType
import its.model.definition.types.ExpressionType

class DebugTraceDef() : CallableProcedureDef(
    "trace",
    listOf(ProcedureArgument("obj", ExpressionType)),
    namespace = ProcedureNamespaces.DEBUG,
    returnType = AnyType,
    scopeCapture = true,
    ensureMutable = true,
)
