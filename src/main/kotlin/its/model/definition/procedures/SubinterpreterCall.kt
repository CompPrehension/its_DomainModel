package its.model.definition.procedures

import its.model.definition.types.OptionalBool
import its.model.definition.types.StringType


class SubinterpreterCall() : CallableProcedureDef(
    "subcall",
    listOf(ProcedureArgument("tree_name", StringType)),
    OptionalBool.Type,
    varArgs = true
) {
}

class MutableSubinterpreterCall() : CallableProcedureDef(
    "subcall_mut",
    listOf(ProcedureArgument("tree_name", StringType)),
    OptionalBool.Type,
    varArgs = true
) {
}
