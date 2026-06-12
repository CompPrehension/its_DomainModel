package its.model.definition.loqi

import its.model.definition.loqi.tree.LambdaDef

data class RegisteredLambda(
    val definition: LambdaDef,
    val body: LoqiGrammarParser.ExpContext,
)
