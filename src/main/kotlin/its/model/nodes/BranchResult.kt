package its.model.nodes

import its.model.definition.EnumValueRef
import its.model.definition.types.OptionalBool

/**
 * Результат проверки ответа студента по одной ветви рассуждений
 */
enum class BranchResult {
    /**
     * Ответ корректен
     */
    CORRECT,

    /**
     * Допущена ошибка
     */
    ERROR,

    /**
     * Невозможно определить корректность ответа
     */
    NULL;

    fun toOptionalBool(): EnumValueRef {
        return when (this) {
            CORRECT -> OptionalBool.Values.True;
            ERROR -> OptionalBool.Values.False;
            NULL -> OptionalBool.Values.Null;
        }
    }

    companion object {
        fun fromOptionalBool(bl: EnumValueRef): BranchResult {
            assert(bl.enumName == "OptionalBool"
            ) { "Only optional bool is acceptable" };
            return when (bl) {
                OptionalBool.Values.True -> CORRECT;
                OptionalBool.Values.False -> ERROR;
                OptionalBool.Values.Null -> NULL;
                else -> NULL;
            }
        }
    }
}