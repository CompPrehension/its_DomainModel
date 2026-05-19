package its.model.definition.procedures

import its.model.definition.types.AnyType
import its.model.definition.types.StringType

class DebugDumpPointDef() : CallableProcedureDef(
    "dump",
    listOf(ProcedureArgument("comment", StringType)),
    namespace = ProcedureNamespaces.DEBUG,
    returnType = AnyType,
    scopeCapture = true,
)
