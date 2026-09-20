package its.model.definition.loqi

import its.model.ValueTuple
import its.model.definition.EnumValueRef
import its.model.definition.MetaData
import its.model.definition.MetaOwner
import its.model.definition.MetadataPropertyValue
import its.model.definition.loqi.LoqiStringUtils.toLoqiName
import its.model.definition.types.Clazz
import its.model.definition.types.Obj
import its.model.expressions.Operator
import its.model.nodes.*
import its.model.nodes.visitors.DecisionTreeBehaviour
import its.model.nodes.visitors.LinkNodeBehaviour
import java.io.StringWriter
import java.io.Writer

/**
 * Writes a [DecisionTree] as LOQI thought-process graph source.
 * Inverse of [TreeLoqiBuilder] for structure; expressions use [OperatorLoqiWriter].
 */
class TreeLoqiWriter private constructor(
    private val writer: IndentWriter,
    private val treeName: String,
) : LinkNodeBehaviour<Unit>, DecisionTreeBehaviour<Unit> {

    private val pendingMetaFor = mutableListOf<Pair<String, MetaData>>()
    private val syntheticAliases = java.util.IdentityHashMap<MetaData, String>()

    companion object {
        private fun Operator.loqiCompact(): String = description
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([.])"), "$1")
            .replace(Regex(" ([=<>!]=?) "), " $1 ")
            .trim()

        fun getWrittenTree(decisionTree: DecisionTree, treeName: String = "ExprEval"): String {
            val sw = StringWriter()
            writeTree(decisionTree, sw, treeName)
            return sw.toString()
        }

        fun writeTree(decisionTree: DecisionTree, out: Writer, treeName: String = "ExprEval") {
            TreeLoqiWriter(IndentWriter(out), treeName).write(decisionTree)
        }
    }

    private fun write(tree: DecisionTree) {
        writer.write("tpg ${treeName.toLoqiName()}(")
        val inputVars = tree.variables.map { "${it.varName.toLoqiName()}: ${it.className.toLoqiName()}" }
        val implicitVars = tree.implicitVariables.map { decl ->
            "${decl.variable.varName.toLoqiName()}: ${decl.variable.className.toLoqiName()} = ${decl.valueExpr.loqiCompact()}"
        }
        writer.write((inputVars + implicitVars).joinToString(", "))
        writer.writeln(") {")
        writer.indent()
        writeSubtree(tree.mainBranch.start)
        writer.unindent()
        writer.writeln("}")
        tree.mainBranch.metadata.writeMetadataBlock(writer, true)
        flushPendingMetaFor()
    }

    private fun writeSubtree(node: DecisionTreeNode) {
        when (node) {
            is BranchResultNode -> writeBranchResult(node)
            is BranchResultRedirectingNode -> writeBranchResultRedirecting(node)
            is LinkNode<*> -> node.use(this)
            else -> throw UnsupportedOperationException("Unsupported node: ${node::class.simpleName}")
        }
    }

    override fun process(node: BranchResultNode) = writeBranchResult(node)

    override fun process(node: BranchResultRedirectingNode) = writeBranchResultRedirecting(node)

    override fun process(branch: ThoughtBranch) {
        writeSubtree(branch.start)
    }

    override fun process(node: QuestionNode) {
        queueNodeMeta(node)
        val prefix = buildAskPrefix(node)
        // `ask (...) out X else { ... }`: the heavier outcome continues the branch, the other goes into `else`.
        // The form fixes the order "out, then else" and gives the else-outcome no place for an alias,
        // so it is used only when the outcomes already come in that order and the else-outcome carries no metadata.
        if (!node.isSwitch && node.trivialityExpr == null && node.outcomes.size == 2
            && node.outcomes.all { it.key is Boolean && it.node !is DummyNode }
        ) {
            val outBr = heaviest(node.outcomes)
            val elseBr = node.outcomes.first { it !== outBr }
            if (outBr === node.outcomes.first() && elseBr.metadata.loqiLinkAlias() == null) {
                writeBooleanAskOutElse(node, prefix, outBr, elseBr)
                return
            }
        }
        if (node.outcomes.none { it.node is DummyNode } && node.outcomes.isNotEmpty()) {
            writeAskWithChainedOut(node, prefix)
            return
        }
        writer.write(prefix)
        writer.writeln(" {")
        writer.indent()
        writeQuestionOutcomes(node)
        writer.unindent()
        finishStmt(node.metadata)
    }

    private fun buildAskPrefix(node: QuestionNode): String = buildString {
        if (node.isSwitch) append("ask switch ")
        else append("ask ")
        if (node.trivialityExpr != null) {
            append("( ${node.expr.loqiCompact()} ) with trivial [${node.trivialityExpr!!.loqiCompact()}]")
        } else {
            append("( ${node.expr.loqiCompact()} )")
        }
    }

    /**
     * LOQI requires one `out` branch per question; the heaviest subtree is redirected after the ask
     * so that the main line of reasoning stays flat. The choice does not affect the tree:
     * [TreeLoqiBuilder] puts the `out` outcome back at its textual position.
     */
    private fun writeAskWithChainedOut(node: QuestionNode, prefix: String) {
        val outOutcome = heaviest(node.outcomes)
        writer.write(prefix)
        writer.writeln(" {")
        writer.indent()
        for (outcome in node.outcomes) {
            if (outcome == outOutcome) {
                queueNodeMeta(outcome)
                writer.writeln("${formatOutcomeKey(outcome.key)} ${formatArrow(outcome.metadata)} out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
        writer.unindent()
        finishStmt(node.metadata)
        writeSubtree(outOutcome.node)
    }

    /** Outcome with the largest subtree; ties are broken by key, not by position, so the written text is stable across round-trips. */
    private fun heaviest(outcomes: Outcomes<*>): Outcome<*> =
        outcomes.maxWith(compareBy<Outcome<*>> { subtreeWeight(it.node) }.thenBy { it.key.toString() })

    /** Number of elements in the subtree (outcomes and cycle bodies included). */
    private fun subtreeWeight(element: DecisionTreeElement): Int =
        1 + element.linkedElements.sumOf { subtreeWeight(it) }

    private fun writeBooleanAskOutElse(
        node: QuestionNode,
        prefix: String,
        outBr: Outcome<*>,
        elseBr: Outcome<*>,
    ) {
        queueNodeMeta(outBr)
        writer.write(prefix)
        writer.write(" ${formatOut(outBr.metadata)} ${formatOutcomeKey(outBr.key as Any)} else {")
        writer.newLine()
        writer.indent()
        writeSubtree(elseBr.node)
        writer.unindent()
        writer.write("}")
        finishStmtAlias(node.metadata)
        writeSubtree(outBr.node)
    }

    private fun writeQuestionOutcomes(node: QuestionNode) {
        for (outcome in node.outcomes) {
            if (outcome.node is DummyNode) {
                queueNodeMeta(outcome)
                writer.writeln("${formatOutcomeKey(outcome.key)} ${formatArrow(outcome.metadata)} out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
    }

    private fun writeQuestionOutcomeBranch(outcome: Outcome<*>) {
        queueNodeMeta(outcome)
        writer.write("${formatOutcomeKey(outcome.key as Any)} ${formatArrow(outcome.metadata)} {")
        writer.newLine()
        writer.indent()
        writeSubtree(outcome.node)
        writer.unindent()
        writer.writeln("};")
    }

    override fun process(node: FindActionNode) {
        queueNodeMeta(node)
        writer.write("var ${node.varAssignment.variable.varName.toLoqiName()}: ${node.varAssignment.variable.className.toLoqiName()}")
        if (node.secondaryAssignments.isNotEmpty()) {
            writer.write(" with (")
            writer.write(
                node.secondaryAssignments.joinToString(", ") { decl ->
                    "${decl.variable.varName.toLoqiName()}: ${decl.variable.className.toLoqiName()} = ${decl.valueExpr.loqiCompact()}"
                }
            )
            writer.write(")")
        }
        if (node.errorCategories.isNotEmpty()) {
            writer.write(" error (")
            writer.write(
                node.errorCategories.joinToString(", ") { category ->
                    queueNodeMeta(category)
                    buildString {
                        append("${category.priority} : ${category.checkedVariable.className.toLoqiName()}")
                        category.metadata.loqiLinkAlias()?.let { append(" as ${it.toLoqiName()}") }
                        append(" -> ${category.selectorExpr.loqiCompact()}")
                    }
                }
            )
            writer.write(")")
        }
        writer.write(" = ${node.varAssignment.valueExpr.loqiCompact()}")
        writer.writeln(" {")
        writer.indent()
        for (outcome in node.outcomes) {
            if (outcome.node is DummyNode) {
                queueNodeMeta(outcome)
                writer.writeln("${formatOutcomeKey(outcome.key)} ${formatArrow(outcome.metadata)} out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
        if (node.outcomes.any { it.key == true } && node.outcomes.none { it.key == false }) {
            writer.writeln("false -> out;")
        }
        writer.unindent()
        finishStmt(node.metadata)
    }

    override fun process(node: BranchAggregationNode) {
        queueNodeMeta(node)
        writer.write("agg ${node.aggregationMethod.name.lowercase()}")
        writer.writeln(" {")
        writer.indent()
        for (branch in node.thoughtBranches) {
            queueNodeMeta(branch)
            writer.writeln("${branchLabel()} ${formatArrow(branch.metadata)} {")
            writer.indent()
            writeSubtree(branch.start)
            writer.unindent()
            writer.writeln("};")
        }
        writeBranchResultOutcomes(node.outcomes)
        writer.unindent()
        finishStmt(node.metadata)
    }

    override fun process(node: CycleAggregationNode) {
        queueNodeMeta(node)
        writer.write("cycle ${node.aggregationMethod.name.lowercase()} ( ${node.selectorExpr.loqiCompact()} )")
        if (node.errorCategories.isNotEmpty()) {
            writer.write(" error (")
            writer.write(
                node.errorCategories.joinToString(", ") { category ->
                    queueNodeMeta(category)
                    buildString {
                        append("${category.priority} : ${category.checkedVariable.className.toLoqiName()}")
                        category.metadata.loqiLinkAlias()?.let { append(" as ${it.toLoqiName()}") }
                        append(" -> ${category.selectorExpr.loqiCompact()}")
                    }
                }
            )
            writer.write(")")
        }
        writer.write(" with ${node.variable.className.toLoqiName()} ${node.variable.varName.toLoqiName()}")
        writer.writeln(" {")
        writer.indent()
        queueNodeMeta(node.thoughtBranch)
        writer.writeln("${branchLabel()} ${formatArrow(node.thoughtBranch.metadata)} {")
        writer.indent()
        writeSubtree(node.thoughtBranch.start)
        writer.unindent()
        writer.writeln("};")
        writeBranchResultOutcomes(node.outcomes)
        writer.unindent()
        finishStmt(node.metadata)
    }

    override fun process(node: WhileCycleNode) {
        queueNodeMeta(node)
        writer.write("while ( ${node.conditionExpr.loqiCompact()} )")
        writer.writeln(" {")
        writer.indent()
        queueNodeMeta(node.thoughtBranch)
        writer.writeln("${branchLabel()} ${formatArrow(node.thoughtBranch.metadata)} {")
        writer.indent()
        writeSubtree(node.thoughtBranch.start)
        writer.unindent()
        writer.writeln("};")
        writeBranchResultOutcomes(node.outcomes)
        writer.unindent()
        finishStmt(node.metadata)
    }

    override fun processTupleQuestionNode(node: TupleQuestionNode) {
        queueNodeMeta(node)
        writer.write("ask tuple ( ")
        writer.write(node.parts.joinToString("; ") { part ->
            queueNodeMeta(part)
            buildString {
                append(part.expr.loqiCompact())
                if (part.possibleOutcomes.isNotEmpty()) {
                    append(" with [")
                    append(part.possibleOutcomes.joinToString(", ") { outcome ->
                        queueNodeMeta(outcome)
                        buildString {
                            append(formatValue(outcome.value))
                            outcome.metadata.loqiLinkAlias()?.let { append(" as ${it.toLoqiName()}") }
                        }
                    })
                    append("]")
                }
                part.metadata.loqiLinkAlias()?.let { append(" as ${it.toLoqiName()}") }
            }
        })
        writer.writeln(" ) {")
        writer.indent()
        for (outcome in node.outcomes) {
            queueNodeMeta(outcome)
            writer.write("${formatTupleKey(outcome.key)} ${formatArrow(outcome.metadata)} {")
            writer.newLine()
            writer.indent()
            writeSubtree(outcome.node)
            writer.unindent()
            writer.writeln("}")
        }
        writer.unindent()
        writer.write("}")
        finishStmtAlias(node.metadata)
    }

    override fun process(node: ProcedureCallNode) {
        throw UnsupportedOperationException("ProcedureCallNode is not supported by TreeLoqiWriter")
    }

    private fun writeBranchResultOutcomes(outcomes: Outcomes<BranchResult>) {
        for (outcome in outcomes) {
            if (outcome.node is DummyNode) {
                queueNodeMeta(outcome)
                writer.writeln("${formatBranchResult(outcome.key)} -> ${formatOut(outcome.metadata)};")
            } else {
                queueNodeMeta(outcome)
                writer.write("${formatBranchResult(outcome.key)} ${formatArrow(outcome.metadata)} {")
                writer.newLine()
                writer.indent()
                writeSubtree(outcome.node)
                writer.unindent()
                writer.writeln("};")
            }
        }
    }

    private fun writeBranchResult(node: BranchResultNode) {
        writer.write("conclude: ${formatBranchResult(node.value)}")
        if (node.actionExpr != null) {
            writer.write(" with ( ${node.actionExpr!!.loqiCompact()} )")
        }
        writeConcludeMetadata(node.metadata)
        writer.writeln(";")
    }

    private fun writeBranchResultRedirecting(node: BranchResultRedirectingNode) {
        writer.write("conclude: ${node.call.loqiCompact()}")
        if (node.actionExpr != null) {
            writer.write(" with ( ${node.actionExpr!!.loqiCompact()} )")
        }
        writeConcludeMetadata(node.metadata)
        writer.writeln(";")
    }

    private fun finishStmt(metadata: MetaData) {
        writer.write("}")
        finishStmtAlias(metadata)
    }

    private fun finishStmtAlias(metadata: MetaData) {
        val linkAlias = metadata.loqiLinkAlias()
        if (linkAlias != null) {
            writer.writeln(" as ${linkAlias.toLoqiName()};")
        } else {
            writer.writeln(";")
        }
    }

    private fun branchLabel(): String = "_"

    private fun formatArrow(metadata: MetaData): String {
        val linkAlias = metadata.loqiLinkAlias()?.toLoqiName() ?: return "->"
        return "-[$linkAlias]->"
    }

    private fun formatOut(metadata: MetaData): String {
        val linkAlias = metadata.loqiLinkAlias()?.toLoqiName() ?: return "out"
        return "out[$linkAlias]"
    }

    private fun queueNodeMeta(owner: MetaOwner) {
        val linkAlias = owner.metadata.loqiLinkAlias() ?: return
        val copy = MetaData()
        copy.addAll(owner.metadata)
        pendingMetaFor.add(linkAlias to copy)
    }

    private fun writeConcludeMetadata(metadata: MetaData) {
        if (metadata.isEmpty()) return
        writer.write(" ")
        metadata.writeMetadataBlock(writer)
    }

    private fun flushPendingMetaFor() {
        val seen = mutableSetOf<String>()
        for ((alias, metadata) in pendingMetaFor) {
            if (!seen.add(alias)) continue
            writer.newLine()
            writer.write("meta for ${metaForName(alias)} ")
            metadata.writeMetadataBlock(writer, true)
        }
        pendingMetaFor.clear()
    }

    private fun metaForName(alias: String): String = alias.toLoqiName()

    /**
     * LOQI-safe alias for `as`, `meta for`, `-[id]->` and `out[id]` (the latter only in the `ask (...) out[id] ... else` form).
     * Human-readable XML aliases fall back to n{TEMPLATING_ID}; metadata without either still has to reach
     * a `meta for` declaration, so it gets a synthetic handle (the original `alias` value stays inside the metadata).
     */
    private fun MetaData.loqiLinkAlias(): String? {
        val alias = getString("alias")
        if (!alias.isNullOrBlank() && alias.none { it.isWhitespace() }) return alias
        getString("TEMPLATING_ID")?.let { return "n$it" }
        if (isEmpty()) return null
        return syntheticAliases.getOrPut(this) { "_m${syntheticAliases.size + 1}" }
    }

    private fun MetaData.writeMetadataBlock(writer: IndentWriter, terminateMetaDecl: Boolean = false) {
        if (isEmpty()) return
        writer.writeln("[")
        writer.indent()
        for ((locCode, propertyName, value) in entries.sortedWith(
            compareBy<MetadataPropertyValue> { it.locCode ?: "" }.thenBy { it.propertyName }
        )) {
            if (locCode != null) writer.write("${locCode.toLoqiName()}.")
            writer.writeln("${propertyName.toLoqiName()} = ${value.toLoqiLiteral()};")
        }
        writer.unindent()
        if (terminateMetaDecl) writer.writeln("]") else writer.write("]")
    }

    private fun String.toLoqiStringLiteral(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun Any.toLoqiLiteral(): String = when (this) {
        is String -> this.toLoqiStringLiteral()
        is EnumValueRef -> "${enumName.toLoqiName()}:${valueName.toLoqiName()}"
        is Boolean -> if (this) "true" else "false"
        else -> toString()
    }

    private fun formatValue(value: Any): String = when (value) {
        is BranchResult -> formatBranchResult(value)
        is Boolean -> if (value) "true" else "false"
        is String -> value.toLoqiStringLiteral()
        is Number -> value.toString()
        is EnumValueRef -> "${value.enumName.toLoqiName()}:${value.valueName.toLoqiName()}"
        is Clazz -> "class:${value.className.toLoqiName()}"
        is Obj -> "obj:${value.objectName.toLoqiName()}"
        else -> value.toString()
    }

    private fun formatBranchResult(result: BranchResult): String = when (result) {
        BranchResult.CORRECT -> "correct"
        BranchResult.ERROR -> "error"
        BranchResult.NULL -> "null"
    }

    private fun formatOutcomeKey(key: Any): String = when (key) {
        is BranchResult -> formatBranchResult(key)
        is ValueTuple -> formatTupleKey(key)
        else -> formatValue(key)
    }

    private fun formatTupleKey(tuple: ValueTuple): String {
        return "(" + tuple.joinToString("; ") { el ->
            when (el) {
                null -> "*"
                else -> formatValue(el)
            }
        } + ")"
    }

    private class IndentWriter(private val out: Writer) {
        private var indentLevel = 0
        private var atLineStart = true
        private val indentStr = "    "

        fun write(s: String) {
            if (s.isEmpty()) return
            val parts = s.split('\n')
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    out.write("\n")
                    atLineStart = true
                }
                if (part.isNotEmpty()) {
                    if (atLineStart) {
                        repeat(indentLevel) { out.write(indentStr) }
                        atLineStart = false
                    }
                    out.write(part)
                }
            }
        }

        fun writeln(s: String = "") {
            write(s)
            out.write("\n")
            atLineStart = true
        }

        fun newLine() = writeln()

        fun indent() {
            indentLevel++
        }

        fun unindent() {
            indentLevel = (indentLevel - 1).coerceAtLeast(0)
        }
    }
}
