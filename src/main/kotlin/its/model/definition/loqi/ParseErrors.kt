package its.model.definition.loqi

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/**
 * Слушатель для ошибок при парсинге LOQI
 * TODO незавершенное
 */
class SyntaxErrorListener() : BaseErrorListener() {
    private val syntaxErrors: MutableList<SyntaxError> = ArrayList()
    fun getSyntaxErrors(): List<SyntaxError> {
        return syntaxErrors
    }

    fun throwIfAny() {
        val firstError = syntaxErrors.firstOrNull() ?: return
        throw firstError.toException()
    }

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int, charPositionInLine: Int,
        msg: String, e: RecognitionException?
    ) {
        syntaxErrors.add(SyntaxError(recognizer, offendingSymbol, line, charPositionInLine, msg, e))
    }

    override fun toString(): String {
        return syntaxErrors.joinToString("\n")
    }
}

class SyntaxError internal constructor(
    val recognizer: Recognizer<*, *>?,
    val offendingSymbol: Any?,
    val line: Int,
    val charPositionInLine: Int,
    val message: String,
    val exception: RecognitionException?
) {
    fun toException(): LoqiDomainBuildException {
        val formattedMessage = "Syntax error at $line:$charPositionInLine: $message"
        return exception?.let { LoqiDomainBuildException(line, formattedMessage, it) }
            ?: LoqiDomainBuildException(line, formattedMessage)
    }
}
