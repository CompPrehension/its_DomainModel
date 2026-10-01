package its.model.definition.loqi

import its.model.definition.loqi.LoqiGrammarParser.TreeVarContext
import its.model.definition.loqi.LoqiStringUtils.toLoqiName
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree

/**
 * Текстовые шаблоны в метаданных дерева: интерполяции JavaStringTemplating с LOQI-выражениями внутри
 */
internal object MetadataTemplates {

    /**
     * Заменить в шаблоне [template] ссылки на переменные дерева по [renaming]; остальной текст не меняется.
     *
     * Интерполяции выделяются так же, как в JavaStringTemplating: `${...}` по балансу фигурных скобок
     * (кавычки не учитываются), `$name` и `$$name` (контекстная переменная, не переменная дерева).
     */
    fun renameTreeVariables(template: String, renaming: Map<String, String>): String {
        if (renaming.all { (from, to) -> from == to }) return template
        val result = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            if (template[i] != '$') {
                result.append(template[i++])
            } else if (template.startsWith("\${", i)) {
                val end = interpolationEnd(template, i + 2)
                result.append("\${").append(renameInExpression(template.substring(i + 2, end), renaming)).append('}')
                i = end + 1
            } else {
                val isContextVariable = template.startsWith("$$", i)
                val nameStart = if (isContextVariable) i + 2 else i + 1
                val nameEnd = identifierEnd(template, nameStart)
                if (nameEnd == nameStart) {
                    result.append(template[i++])
                    continue
                }
                val name = template.substring(nameStart, nameEnd)
                result.append(template, i, nameStart).append(if (isContextVariable) name else renaming[name] ?: name)
                i = nameEnd
            }
        }
        return result.toString()
    }

    private fun interpolationEnd(template: String, contentStart: Int): Int {
        var depth = 1
        for (index in contentStart until template.length) {
            when (template[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        throw LoqiDomainBuildException("Unclosed interpolation in metadata template: $template")
    }

    private fun identifierEnd(template: String, start: Int): Int {
        var end = start
        while (end < template.length && template[end].isIdentifierChar(isFirst = end == start)) end++
        return end
    }

    private fun Char.isIdentifierChar(isFirst: Boolean) =
        this in 'a'..'z' || this in 'A'..'Z' || this == '_' || (!isFirst && this in '0'..'9')

    private fun renameInExpression(expression: String, renaming: Map<String, String>): String {
        val lexer = LoqiGrammarLexer(CharStreams.fromString(expression))
        val parser = LoqiGrammarParser(CommonTokenStream(lexer))
        val errorListener = SyntaxErrorListener()
        lexer.removeErrorListeners()
        parser.removeErrorListeners()
        lexer.addErrorListener(errorListener)
        parser.addErrorListener(errorListener)
        val tree = parser.fullExp()
        errorListener.getSyntaxErrors().firstOrNull()?.let {
            throw LoqiDomainBuildException("Metadata template interpolation `\${$expression}` is not a LOQI expression: ${it.message}")
        }

        val renamed = StringBuilder(expression)
        treeVariableTokens(tree).sortedByDescending { it.startIndex }.forEach { token ->
            val actual = renaming[token.text.removeSurrounding("`")] ?: return@forEach
            renamed.replace(token.startIndex, token.stopIndex + 1, actual.toLoqiName())
        }
        return renamed.toString()
    }

    private fun treeVariableTokens(tree: ParseTree): List<Token> {
        if (tree is TreeVarContext) return listOf(tree.ID().symbol)
        return (0 until tree.childCount).flatMap { treeVariableTokens(tree.getChild(it)) }
    }
}
