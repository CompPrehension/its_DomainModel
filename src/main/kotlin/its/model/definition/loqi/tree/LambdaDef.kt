package its.model.definition.loqi.tree

import its.model.definition.procedures.CallableProcedureDef
import its.model.definition.procedures.Macros
import its.model.definition.procedures.ProcedureArgument
import its.model.definition.procedures.ProcedureNamespaces

class LambdaDef(name: String, arguments: List<ProcedureArgument>) :
    CallableProcedureDef(
        name,
        arguments,
        null,
        scopeCapture = true,
        namespace = ProcedureNamespaces.LAMBDA,
    ), Macros
