package its.model.definition.loqi

import its.model.ValueTuple
import its.model.definition.EnumValueRef
import its.model.definition.MetaData
import its.model.definition.MetaOwner
import its.model.definition.MetadataPropertyValue
import its.model.definition.loqi.LoqiStringUtils.insertEscapes
import its.model.definition.loqi.LoqiStringUtils.isSimpleLoqiName
import its.model.definition.loqi.LoqiStringUtils.toLoqiName
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
        if (hasNonBooleanOutcomes(node)) {
            writeEqualityChainQuestion(node)
            return
        }
        val trueBr = node.outcomes.find { it.key == true }
        val falseBr = node.outcomes.find { it.key == false }
        if (!node.isSwitch && node.trivialityExpr == null && trueBr != null && falseBr != null
            && trueBr.node !is DummyNode && falseBr.node !is DummyNode && node.outcomes.size == 2
        ) {
            writeBooleanAskOutElse(node, prefix, trueBr, falseBr)
            return
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

  private fun hasNonBooleanOutcomes(node: QuestionNode): Boolean =
        node.outcomes.any { it.key !is Boolean }

    /** Non-boolean outcomes use chained equality asks because `N -> out` is invalid in LOQI. */
    private fun writeEqualityChainQuestion(node: QuestionNode) {
        val outcomes = node.outcomes.toList()
        for (i in outcomes.indices) {
            val outcome = outcomes[i]
            val isLast = i == outcomes.lastIndex
            val eqExpr = "( ${node.expr.loqiCompact()} == ${formatOutcomeKey(outcome.key as Any)} )"
            writer.write("ask ( $eqExpr ) {")
            writer.newLine()
            writer.indent()
            if (isLast) {
                writer.writeln("true -> out;")
                if (outcomes.size > 1) {
                    writer.write("false -> {")
                    writer.newLine()
                    writer.indent()
                    writer.writeln("conclude: null;")
                    writer.unindent()
                    writer.writeln("};")
                }
            } else {
                writer.write("true -> {")
                writer.newLine()
                writer.indent()
                writeSubtree(outcome.node)
                writer.unindent()
                writer.writeln("};")
                writer.writeln("false -> out;")
            }
            writer.unindent()
            writer.write("}")
            if (i == 0) {
                finishStmtAlias(node.metadata)
            } else {
                writer.writeln(";")
            }
            if (isLast) {
                writeSubtree(outcome.node)
            }
        }
    }

    /** LOQI requires one `out` branch per question; chain the redirected outcome after the ask. */
    private fun writeAskWithChainedOut(node: QuestionNode, prefix: String) {
        val outOutcome = node.outcomes.maxByOrNull { subtreeWeight(it.node) }!!
        writer.write(prefix)
        writer.writeln(" {")
        writer.indent()
        for (outcome in node.outcomes) {
            if (outcome == outOutcome) {
                writer.writeln("${formatOutcomeKey(outcome.key)} -> out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
        writer.unindent()
        finishStmt(node.metadata)
        writeSubtree(outOutcome.node)
    }

    private fun writeBooleanAskOutElse(
        node: QuestionNode,
        prefix: String,
        trueBr: Outcome<*>,
        falseBr: Outcome<*>,
    ) {
        writer.write(prefix)
        writer.write(" out true else {")
        writer.newLine()
        writer.indent()
        writeSubtree(falseBr.node)
        writer.unindent()
        writer.write("}")
        finishStmtAlias(node.metadata)
        writeSubtree(trueBr.node)
    }

    private fun writeQuestionOutcomes(node: QuestionNode) {
        for (outcome in node.outcomes) {
            if (outcome.node is DummyNode) {
                writer.writeln("${formatOutcomeKey(outcome.key)} -> out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
    }

    private fun writeQuestionOutcomeBranch(outcome: Outcome<*>) {
        writer.write("${formatOutcomeKey(outcome.key as Any)} -> {")
        writer.newLine()
        writer.indent()
        writeSubtree(outcome.node)
        writer.unindent()
        writer.writeln("};")
    }

    private fun subtreeWeight(node: DecisionTreeNode): Int = when (node) {
        is BranchResultNode -> 1
        is QuestionNode -> 5
        is FindActionNode -> 20
        is BranchAggregationNode -> 30
        is CycleAggregationNode -> 40
        else -> 10
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
        writer.write(" = ${node.varAssignment.valueExpr.loqiCompact()}")
        writer.writeln(" {")
        writer.indent()
        for (outcome in node.outcomes) {
            if (outcome.node is DummyNode) {
                writer.writeln("${formatOutcomeKey(outcome.key)} -> out;")
            } else {
                writeQuestionOutcomeBranch(outcome)
            }
        }
        if (node.outcomes.any { it.key == true } && node.outcomes.none { it.key == false }) {
            writer.writeln("false -> out;")
        }
        writer.unindent()
        finishStmt(node.metadata)
        node.errorCategories.forEach { err ->
            val alias = err.metadata.loqiLinkAlias() ?: "findError_${err.priority}"
            queueMetaFor(alias, err.metadata, err.selectorExpr)
        }
    }

    override fun process(node: BranchAggregationNode) {
        queueNodeMeta(node)
        writer.write("agg ${node.aggregationMethod.name.lowercase()}")
        writer.writeln(" {")
        writer.indent()
        for (branch in node.thoughtBranches) {
            queueNodeMeta(branch)
            val label = branchLabel(branch.metadata)
            writer.writeln("$label -> {")
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
        writer.write(
            "cycle ${node.aggregationMethod.name.lowercase()} ( ${node.selectorExpr.loqiCompact()} ) " +
                "with ${node.variable.className.toLoqiName()} ${node.variable.varName.toLoqiName()}"
        )
        writer.writeln(" {")
        writer.indent()
        queueNodeMeta(node.thoughtBranch)
        writer.writeln("${branchLabel(node.thoughtBranch.metadata)} -> {")
        writer.indent()
        writeSubtree(node.thoughtBranch.start)
        writer.unindent()
        writer.writeln("};")
        writeBranchResultOutcomes(node.outcomes)
        writer.unindent()
        finishStmt(node.metadata)
        node.errorCategories.forEach { err ->
            val alias = err.metadata.loqiLinkAlias() ?: "findError_${err.priority}"
            queueMetaFor(alias, err.metadata, err.selectorExpr)
        }
    }

    override fun process(node: WhileCycleNode) {
        queueNodeMeta(node)
        writer.write("while ( ${node.conditionExpr.loqiCompact()} )")
        writer.writeln(" {")
        writer.indent()
        queueNodeMeta(node.thoughtBranch)
        writer.writeln("${branchLabel(node.thoughtBranch.metadata)} -> {")
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
        when (node.parts.size) {
            2 -> writeBinaryTupleQuestion(node)
            else -> throw UnsupportedOperationException(
                "TupleQuestionNode with ${node.parts.size} parts is not supported by TreeLoqiWriter"
            )
        }
    }

    /** Expands 2-part tuple question into nested boolean asks (ask tuple header parsing is fragile). */
    private fun writeBinaryTupleQuestion(node: TupleQuestionNode) {
        val e1 = node.parts[0].expr.loqiCompact()
        val e2 = node.parts[1].expr.loqiCompact()
        val tt = findTupleOutcome(node.outcomes, true, true)
            ?: throw IllegalStateException("Missing tuple outcome for (true; true)")
        val tf = findTupleOutcome(node.outcomes, true, false)
            ?: throw IllegalStateException("Missing tuple outcome for (true; false)")
        val ft = findTupleOutcome(node.outcomes, false, true)
            ?: throw IllegalStateException("Missing tuple outcome for (false; true)")
        val ff = findTupleOutcome(node.outcomes, false, false)
            ?: throw IllegalStateException("Missing tuple outcome for (false; false)")

        writer.write("ask ( $e1 ) out true else {")
        writer.newLine()
        writer.indent()
        writeBooleanPairAsk(e2, ft, ff)
        writer.unindent()
        writer.write("}")
        finishStmtAlias(node.metadata)
        writeBooleanPairAsk(e2, tt, tf)
    }

    private fun writeBooleanPairAsk(expr: String, trueNode: DecisionTreeNode, falseNode: DecisionTreeNode) {
        writer.write("ask ( $expr ) out true else {")
        writer.newLine()
        writer.indent()
        writeSubtree(falseNode)
        writer.unindent()
        writer.writeln("};")
        writeSubtree(trueNode)
    }

    private fun findTupleOutcome(outcomes: Outcomes<ValueTuple>, v1: Boolean, v2: Boolean): DecisionTreeNode? {
        return outcomes.find { outcome ->
            outcome.key.size >= 2 && outcome.key[0] == v1 && outcome.key[1] == v2
        }?.node
    }

    override fun process(node: ProcedureCallNode) {
        throw UnsupportedOperationException("ProcedureCallNode is not supported by TreeLoqiWriter")
    }

    private fun writeBranchResultOutcomes(outcomes: Outcomes<BranchResult>) {
        for (outcome in outcomes) {
            if (outcome.node is DummyNode) {
                writer.writeln("${formatBranchResult(outcome.key)} -> out;")
            } else {
                writer.write("${formatBranchResult(outcome.key)} -> {")
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

    private fun branchLabel(metadata: MetaData): String {
        return metadata.loqiLinkAlias()?.toLoqiName() ?: "_"
    }

    private fun queueNodeMeta(owner: MetaOwner) {
        val linkAlias = owner.metadata.loqiLinkAlias() ?: return
        val copy = MetaData()
        copy.addAll(owner.metadata)
        pendingMetaFor.add(linkAlias to copy)
    }

    private fun writeConcludeMetadata(metadata: MetaData) {
        val concludeKeys = setOf("skill", "explanation", "error", "law", "muted", "TEMPLATING_ID", "errorNode")
        val filtered = metadata.entries.filter { (_, name, _) ->
            name in concludeKeys || name.endsWith("explanation")
        }
        if (filtered.isEmpty()) return
        writer.write(" ")
        writer.writeln("[")
        writer.indent()
        for ((locCode, propertyName, value) in filtered.sortedWith(
            compareBy<MetadataPropertyValue> { it.locCode ?: "" }.thenBy { it.propertyName }
        )) {
            if (locCode != null) writer.write("${locCode.toLoqiName()}.")
            writer.writeln("${propertyName.toLoqiName()} = ${value.toLoqiLiteral()};")
        }
        writer.unindent()
        writer.write("]")
    }

    private fun queueMetaFor(alias: String, metadata: MetaData, condition: Operator) {
        val copy = MetaData()
        copy.addAll(metadata)
        copy.add("condition", condition.loqiCompact())
        pendingMetaFor.add(alias to copy)
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

    /** LOQI-safe alias for `as` / `meta for` / branch labels; human-readable XML aliases fall back to n{TEMPLATING_ID}. */
    private fun MetaData.loqiLinkAlias(): String? {
        val alias = getString("alias")
        if (!alias.isNullOrBlank() && alias.none { it.isWhitespace() }) return alias
        val tid = getString("TEMPLATING_ID") ?: return null
        return "n$tid"
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

    private fun formatBranchResult(result: BranchResult): String = when (result) {
        BranchResult.CORRECT -> "correct"
        BranchResult.ERROR -> "error"
        BranchResult.NULL -> "null"
    }

    private fun formatOutcomeKey(key: Any): String = when (key) {
        is BranchResult -> formatBranchResult(key)
        is Boolean -> if (key) "true" else "false"
        is ValueTuple -> formatTupleKey(key)
        is String -> key.toLoqiStringLiteral()
        is Number -> key.toString()
        is EnumValueRef -> "${key.enumName.toLoqiName()}:${key.valueName.toLoqiName()}"
        else -> key.toString()
    }

    private fun formatTupleKey(tuple: ValueTuple): String {
        return "(" + tuple.joinToString("; ") { el ->
            when (el) {
                null -> "*"
                is Boolean -> if (el) "true" else "false"
                is String -> el.toLoqiStringLiteral()
                is EnumValueRef -> "${el.enumName.toLoqiName()}:${el.valueName.toLoqiName()}"
                else -> el.toString()
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
