package its.model.definition.procedures

import its.model.definition.types.BooleanType
import its.model.definition.types.OptionalBool
import its.model.definition.types.StringType

interface SubinterpreterProcedure {

}

class SubinterpreterCall() : CallableProcedureDef(
    "subcall",
    listOf(ProcedureArgument("tree_name", StringType)),
    OptionalBool.Type,
    varArgs = true,
    ensureMutable = true
), SubinterpreterProcedure {
}

class MutableSubinterpreterCall() : CallableProcedureDef(
    "subcall_mut",
    listOf(ProcedureArgument("tree_name", StringType),
        ProcedureArgument("restore_variables", BooleanType)),
    OptionalBool.Type,
    varArgs = true,
    ensureMutable = true
), SubinterpreterProcedure {
}
