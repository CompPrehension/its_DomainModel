package its.model.definition.loqi

import its.model.definition.DomainDefinitionException

/**
 * Ошибка при построении модели в [DomainLoqiBuilder]
 *
 * Оборачивает [DomainDefinitionException] (См. [cause]), предоставляющая информацию о
 * соответствующей строке LOQI
 */
open class LoqiDomainBuildException : DomainDefinitionException {
    var lineIndex = -1
    val lineTrace: List<Int>
    val buildMessage: String

    constructor() : super() {
        lineTrace = emptyList()
        buildMessage = ""
    }

    constructor(message: String) : super(formatMessage(emptyList(), message)) {
        lineTrace = emptyList()
        buildMessage = message
    }

    constructor(message: String, cause: Throwable) : super(
        formatMessage(lineTraceFrom(-1, cause), buildMessageFrom(message, cause)),
        cause,
    ) {
        lineTrace = lineTraceFrom(-1, cause)
        buildMessage = buildMessageFrom(message, cause)
    }

    constructor(line: Int, message: String) : super(formatMessage(lineTraceFrom(line, null), message)) {
        lineIndex = line
        lineTrace = lineTraceFrom(line, null)
        buildMessage = message
    }

    constructor(line: Int, message: String, cause: Throwable) : super(
        formatMessage(lineTraceFrom(line, cause), buildMessageFrom(message, cause)),
        cause,
    ) {
        lineIndex = line
        lineTrace = lineTraceFrom(line, cause)
        buildMessage = buildMessageFrom(message, cause)
    }

    companion object {
        private fun buildMessageFrom(message: String, cause: Throwable): String {
            return (cause as? LoqiDomainBuildException)?.buildMessage ?: message
        }

        private fun lineTraceFrom(line: Int, cause: Throwable?): List<Int> {
            val currentLine = if (line >= 0) listOf(line) else emptyList()
            val causeLines = (cause as? LoqiDomainBuildException)?.lineTrace ?: emptyList()
            return currentLine + causeLines
        }

        private fun formatMessage(lines: List<Int>, message: String): String {
            if (lines.isEmpty()) {
                return "Error on LOQI model build: $message"
            }
            return "Error on LOQI model build (line chain ${lines.joinToString(" -> ")}): $message"
        }
    }
}
