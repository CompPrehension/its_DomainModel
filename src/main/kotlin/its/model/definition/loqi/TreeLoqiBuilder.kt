package its.model.definition.loqi

import its.model.TypedVariable
import its.model.ValueTuple
import its.model.definition.DomainUseException
import its.model.definition.loqi.tree.CallableProcedureDef
import its.model.definition.EnumValueRef
import its.model.definition.loqi.tree.GlobalNamespace
import its.model.definition.MetaData
import its.model.definition.MetaOwner
import its.model.definition.loqi.tree.Namespace
import its.model.definition.ThisShouldNotHappen
import its.model.definition.loqi.LoqiGrammarParser.EnumValueRefContext
import its.model.definition.loqi.LoqiGrammarParser.ID
import its.model.definition.loqi.LoqiGrammarParser.IdContext
import its.model.definition.loqi.LoqiGrammarParser.MetadataSectionContext
import its.model.definition.loqi.LoqiGrammarParser.SWITCH
import its.model.definition.loqi.LoqiGrammarParser.TUPLE
import its.model.definition.loqi.LoqiGrammarParser.TreeDeclContext
import its.model.definition.loqi.LoqiGrammarParser.ValueContext
import its.model.definition.loqi.LoqiStringUtils.extractEscapes
import its.model.definition.loqi.tree.AssertPointDef
import its.model.definition.loqi.tree.ProcedureArgument
import its.model.definition.loqi.tree.DebugDumpPointDef
import its.model.definition.loqi.tree.DebugPointDef
import its.model.definition.types.BooleanType
import its.model.definition.types.DoubleType
import its.model.definition.types.EnumType
import its.model.definition.types.IntegerType
import its.model.definition.types.StringType
import its.model.definition.types.TypeAndValue
import its.model.expressions.Operator
import its.model.expressions.literals.BooleanLiteral
import its.model.expressions.literals.DecisionTreeVarLiteral
import its.model.expressions.literals.ValueLiteral
import its.model.nodes.AggregationMethod
import its.model.nodes.BranchAggregationNode
import its.model.nodes.BranchResult
import its.model.nodes.BranchResultNode
import its.model.nodes.CycleAggregationNode
import its.model.nodes.DecisionTree
import its.model.nodes.DecisionTreeElement
import its.model.nodes.DecisionTreeNode
import its.model.nodes.DecisionTreeVarAssignment
import its.model.nodes.FindActionNode
import its.model.nodes.LinkNode
import its.model.nodes.DummyNode
import its.model.nodes.Outcome
import its.model.nodes.Outcomes
import its.model.nodes.ProcedureCallNode
import its.model.nodes.QuestionNode
import its.model.nodes.ThoughtBranch
import its.model.nodes.TupleQuestionNode
import its.model.nodes.WhileCycleNode
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTree
import java.io.Reader
import java.net.URL
import kotlin.collections.mutableListOf

class TreeLoqiBuilder(
    private var decisionTree : DecisionTree?
) : LoqiGrammarBaseVisitor<DecisionTreeElement>() {

    private val outMap: MutableMap<DecisionTreeElement, Any> = mutableMapOf()
    private val aliases: MutableMap<String, MutableSet<DecisionTreeNode>> = mutableMapOf()
    private val procedures: MutableMap<Namespace, List<CallableProcedureDef>> = mutableMapOf(
        Pair(GlobalNamespace, mutableListOf()),
        Pair(Namespace(GlobalNamespace, "debug"), listOf(AssertPointDef(), DebugDumpPointDef(), DebugPointDef()))
    )

    // TODO: needs more refactoring, more debugging

    data class BranchInfo<T>(val out: T?, val bodyBranches: List<ThoughtBranch>, val outcomes: Outcomes<T>)

    companion object {
        @JvmStatic
        fun buildTree(file: URL): DecisionTree {
            file.openStream().buffered().reader().use { reader ->
                return buildTree(reader)
            }
        }

        @JvmStatic
        fun buildTree(reader: Reader): DecisionTree {
            val lexer = LoqiGrammarLexer(CharStreams.fromReader(reader))
            val tokens = CommonTokenStream(lexer)
            val parser = LoqiGrammarParser(tokens)

            val errorListener = SyntaxErrorListener()
            parser.addErrorListener(errorListener)

            val tree: ParseTree = parser.fullTreeDecl()
            errorListener.getSyntaxErrors().firstOrNull()?.exception?.apply { throw this }

            val builder = TreeLoqiBuilder(null)
            tree.accept(builder)

            val dt = builder.decisionTree ?: throw LoqiDomainBuildException(-1, "Decision tree is not present")
            return dt
        }
    }

    private fun visitExp(ctx: LoqiGrammarParser.ExpContext): Operator {
        return ctx.accept(OperatorLoqiBuilder())
    }

    private fun Operator.unwrap(): Any {
        if (this is ValueLiteral<*, *>) {
            return this.value
        }
        return this
    }

    private fun branchResultToBoolean(res: BranchResult): Boolean? {
        return when (res) {
            BranchResult.CORRECT -> true
            BranchResult.ERROR -> false
            BranchResult.NULL -> null
        }
    }

    override fun visitCallStmt(ctx: LoqiGrammarParser.CallStmtContext): DecisionTreeNode {
        val procedure = resolveCallNamespace(ctx.namespaceResolution())
        if (procedure == null) {
            throw DomainUseException("Procedure `${ctx.namespaceResolution().text}` not found")
        } else {
            var args = if (ctx.callArgs() != null && !ctx.callArgs().isEmpty) {
                ctx.callArgs().exp().map {
                    visitExp(it)
                }.toList()
            } else {
                listOf()
            }
            return procedure.callNode(args, DummyNode())
        }
    }

    override fun visitThoughtBranch(ctx: LoqiGrammarParser.ThoughtBranchContext): ThoughtBranch {
        if (ctx.stmts().stmt().isEmpty()) {
            throw LoqiDomainBuildException(ctx.stmts().start.line, "Thought branch is empty")
        }
        val stmts = ctx.stmts().stmt();
        val first = visitStmt(stmts[0])
        var prev = first
        for (i in 1 until stmts.size) {
            val newStmt = visitStmt(stmts[i]);
            if (prev is ProcedureCallNode && prev.next is DummyNode) {
                prev.next = newStmt
            } else if (prev in outMap && prev is LinkNode<*>) {
                val outcome = (prev as LinkNode<Any>).outcomes.filter { value -> value.key == outMap[prev] }
                if (outcome.count() != 1) {
                    throw ThisShouldNotHappen()
                } else {
                    prev.outcomes.remove(outcome[0])
                    prev.outcomes.add(Outcome(outMap[prev] as Any, newStmt))
                }
            } else {
                throw LoqiDomainBuildException("Statement can't be reached")
            }
            prev = newStmt;
        }

        if (prev in outMap && prev is LinkNode<*>) {
            val outcome = (prev as LinkNode<Any>).outcomes.filter { value -> value.key == outMap[prev] }
            if (outcome.count() != 1) {
                throw ThisShouldNotHappen()
            } else {
                prev.outcomes.remove(outcome[0])
            }
        }

        return ThoughtBranch(first);
    }

    override fun visitStmt(ctx: LoqiGrammarParser.StmtContext): DecisionTreeNode {
        val child = ctx.getChild(0)

        val result = if (child is LoqiGrammarParser.ConcludeBranchResultContext) {
            domainOpAt(ctx.start.line) { visitConcludeBranchResult(child) }
        } else if (child is LoqiGrammarParser.BranchAggregationContext) {
            domainOpAt(ctx.start.line) { visitBranchAggregation(child) }
        } else if (child is LoqiGrammarParser.CycleAggregationContext) {
            domainOpAt(ctx.start.line) { visitCycleAggregation(child) }
        } else if (child is LoqiGrammarParser.WhileCycleContext) {
            domainOpAt(ctx.start.line) { visitWhileCycle(child) }
        } else if (child is LoqiGrammarParser.FindActionContext) {
            domainOpAt(ctx.start.line) { visitFindAction(child) }
        } else if (child is LoqiGrammarParser.QuestionContext) {
            domainOpAt(ctx.start.line) { visitQuestion(child) }
        } else if (child is LoqiGrammarParser.CallStmtContext) {
            domainOpAt(ctx.start.line) { visitCallStmt(child) }
        } else {
            throw ThisShouldNotHappen()
        }

        if (ctx.AS() != null && ctx.id() != null) {
            if (ctx.id().text !in aliases) {
                aliases[ctx.id().text] = HashSet();
            }
            aliases[ctx.id().text]?.add(result);
        }
        return result
    }

    override fun visitConcludeBranchResult(ctx: LoqiGrammarParser.ConcludeBranchResultContext): BranchResultNode {
        val result : BranchResultNode
        var actionExp: Operator? = null;
        if (ctx.exp() != null) {
            actionExp = visitExp(ctx.exp());
        }
        result = BranchResultNode(parseBranchResult(ctx.outcomeType().text), actionExp);
        result.fillMetadata(ctx.metadataSection())
        if (ctx.id() != null) {
            if (ctx.id().text !in aliases) {
                aliases[ctx.id().text] = HashSet();
            }
            aliases[ctx.id().text]?.add(result);
        }
        return result
    }

    fun parseBranchResult(token: String): BranchResult {
        return if (token.lowercase() == "true" || token.lowercase() == "correct") {
            BranchResult.CORRECT
        } else if (token.lowercase() == "false" || token.lowercase() == "error") {
            BranchResult.ERROR
        } else {
            BranchResult.NULL
        };
    }

    fun parseBranchResult(expCtx: LoqiGrammarParser.ExpContext?, outcomeTypeCtx: LoqiGrammarParser.OutcomeTypeContext?): BranchResult? {
        if (expCtx != null) {
            val exp = visitExp(expCtx).unwrap()
            if (exp is Boolean) {
                return if (exp) BranchResult.CORRECT else BranchResult.ERROR
            }
        } else if (outcomeTypeCtx != null) {
            return parseBranchResult(outcomeTypeCtx.text)
        }
        return null;
    }

    override fun visitWhileCycle(ctx: LoqiGrammarParser.WhileCycleContext): WhileCycleNode {
        val branches = visitAggregationBranches(ctx.aggBranches(), BranchResult.NULL)

        if (branches.bodyBranches.count() > 1 || branches.bodyBranches.count() == 0) {
            throw LoqiDomainBuildException("Cycle must have only one required branch")
        }

        return WhileCycleNode(visitExp(ctx.exp()),
            branches.bodyBranches[0],
            Outcomes(listOf())
        ).also {
            if (branches.out != null) {
                outMap[it] = branches.out as Any;
            }
        }
    }

    fun visitBranchResultOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<BranchResult> {
        return Outcomes(list.map { res ->
            Outcome(parseBranchResult(res.exp(), res.outcomeType()) as BranchResult,
                visitThoughtBranch(res.thoughtBranch()).start)
        }.toMutableList())
    }

    fun visitExprOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<Any> {
        return Outcomes(list.map { res ->
            val exp = if (res.exp() != null) visitExp(res.exp()).unwrap() else visitAndObtainBool(res.exp(), res.outcomeType())
            if (exp == null) {
                throw ThisShouldNotHappen()
            }
            Outcome(exp,
                visitThoughtBranch(res.thoughtBranch()).start)
        }.toMutableList())
    }

    /**
     * Этот метод нужен для того, чтобы решить проблему пересечения outcomeType и exp в ветках (чтобы true/false однозначно стал Boolean)
     */
    fun visitAndObtainBool(exp: LoqiGrammarParser.ExpContext?, outcomeTypeCtx: LoqiGrammarParser.OutcomeTypeContext? ): Boolean? {
        if (exp != null) {
            if (visitExp(exp).unwrap() is Boolean) {
                return visitExp(exp).unwrap() as Boolean
            }
        } else if (outcomeTypeCtx != null) {
            val res = parseBranchResult(outcomeTypeCtx.text);
            if (res != BranchResult.NULL) {
                return if (res == BranchResult.CORRECT) true else false
            }
        }
        return null
    }

    fun visitExpressionBranches(ctx: LoqiGrammarParser.ExpBranchesContext): BranchInfo<Any> {
        if (ctx.branches() == null) {
            val exp = visitExp(ctx.exp()).unwrap();
            if (exp !is Boolean) {
                throw LoqiDomainBuildException("Out with else is supported with boolean results")
            }
            val opposite = !exp;

            return BranchInfo(exp, listOf(), Outcomes(mutableListOf(
                Outcome(opposite, visitThoughtBranch(ctx.thoughtBranch()).start),
                Outcome(exp, DummyNode()),
            )))
        }

        val outcomes = visitExprOutcomes(ctx.branches().branch().filter { b ->
            (visitAndObtainBool(b.exp(), b.outcomeType()) != null || visitExp(b.exp()) !is DecisionTreeVarLiteral) && b.thoughtBranch() != null
        })

        val thoughtBranches = visitAbstractBranches(ctx.branches().branch().filter { b ->
            b.exp() != null && visitExp(b.exp()) is DecisionTreeVarLiteral && b.thoughtBranch() != null
        })

        val outBranch = ctx.branches().branch().filter { branchContext ->
            branchContext.thoughtBranch() == null
        };

        if (outBranch.count() > 1) {
            throw LoqiDomainBuildException("You can redirect only one outcome branch");
        } else if (
            !outBranch.isEmpty() &&
            parseBranchResult(outBranch[0].exp(), outBranch[0].outcomeType()) == null
        ) {
            throw LoqiDomainBuildException("You can redirect only one outcome branch, not thought branch");
        }

        val outcomeOut = if (outBranch.isEmpty()) null else {
            if (outBranch[0].exp() == null) {
                visitAndObtainBool(outBranch[0].exp(), outBranch[0].outcomeType())
            } else {
                visitExp(outBranch[0].exp()).unwrap()
            }
        }

        if (outcomeOut != null && outcomes.filter { value -> value.key == outcomeOut}.none()) {
            outcomes.add(Outcome(outcomeOut, DummyNode()));
        }

        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    fun visitAggregationBranches(ctx: LoqiGrammarParser.AggBranchesContext, defaultOut: BranchResult? = null):
            BranchInfo<BranchResult> {

        if (ctx.branches() == null) {
            val outcomeType = parseBranchResult(ctx.outcomeType().text)
            val values = BranchResult.values()

            val outcomeRawList = values.filter { value -> value != outcomeType }.map { value ->
                Outcome(value, visitThoughtBranch(ctx.thoughtBranch()).start) }.toMutableList()
            outcomeRawList.add(Outcome(outcomeType, DummyNode()))

            return BranchInfo(outcomeType, listOf(visitThoughtBranch(ctx.thoughtBranch())), Outcomes(outcomeRawList))
        }

        val outcomes = visitBranchResultOutcomes(ctx.branches().branch().filter { b ->
            parseBranchResult(b.exp(), b.outcomeType()) != null && b.thoughtBranch() != null
        })

        val thoughtBranches = visitAbstractBranches(ctx.branches().branch().filter { b ->
            b.thoughtBranch() != null && parseBranchResult(b.exp(), b.outcomeType()) == null
        })

        val outBranch = ctx.branches().branch().filter { branchContext ->
            branchContext.thoughtBranch() == null
        };

        if (outBranch.count() > 1) {
            throw LoqiDomainBuildException("You can redirect only one outcome branch");
        } else if (
            !outBranch.isEmpty() &&
            parseBranchResult(outBranch[0].exp(), outBranch[0].outcomeType()) == null) {
            throw LoqiDomainBuildException("You can redirect only one outcome branch, not thought branch");
        }

        val outcomeOut = if (outBranch.isEmpty()) defaultOut else parseBranchResult(outBranch[0].outcomeType().text)

        if (outcomeOut != null && outcomes.filter { value -> value.key == outcomeOut}.none()) {
            outcomes.add(Outcome(outcomeOut, DummyNode()));
        }

        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    fun visitAbstractBranches(list: List<LoqiGrammarParser.BranchContext>): List<ThoughtBranch> {
        return list.map { branch ->
            val expr = branch.exp()?.let { visitExp(it) }

            if (expr is DecisionTreeVarLiteral) {
                return@map visitThoughtBranch(branch.thoughtBranch())
            } else {
                throw LoqiDomainBuildException("Thought branches must have any identifier (for example, `_`) as expression")
            }
        }
    }

    fun parseAggregationMethod(token: String): AggregationMethod {
        if (token.lowercase() == "and") {
            return AggregationMethod.AND
        } else if (token.lowercase() == "or") {
            return AggregationMethod.OR
        } else if (token.lowercase() == "mutex") {
            return AggregationMethod.MUTEX
        } else if (token.lowercase() == "hyp") {
            return AggregationMethod.HYP
        }
        throw ThisShouldNotHappen()
    }

    fun getDefaultOut(agg: AggregationMethod): BranchResult {
        return when(agg) {
            AggregationMethod.AND -> BranchResult.CORRECT
            AggregationMethod.OR -> BranchResult.ERROR
            AggregationMethod.MUTEX -> BranchResult.NULL
            AggregationMethod.HYP -> BranchResult.ERROR
        }
    }

    override fun visitBranchAggregation(ctx: LoqiGrammarParser.BranchAggregationContext): BranchAggregationNode {
        val agg = parseAggregationMethod(ctx.aggregation().text)
        val branches = visitAggregationBranches(ctx.aggBranches(), getDefaultOut(agg))

        if (branches.bodyBranches.count() == 0) {
            throw LoqiDomainBuildException("Branch aggregation requires one and more thought branches (not outcomes)")
        }

        return BranchAggregationNode(agg, branches.bodyBranches, branches.outcomes).also {
            if (branches.out != null) {
                outMap[it] = branches.out as Any;
            }
        }
    }

    override fun visitCycleAggregation(ctx: LoqiGrammarParser.CycleAggregationContext): CycleAggregationNode {
        val expr = visitExp(ctx.exp());
        val agg = parseAggregationMethod(ctx.aggregation().text)
        val branches = visitAggregationBranches(ctx.aggBranches(), getDefaultOut(agg))

        if (branches.bodyBranches.count() > 1 || branches.bodyBranches.count() == 0) {
            throw LoqiDomainBuildException("Cycle must have only one required branch")
        }

        val variable = visitAndGetTypedVar(ctx.typedVarLinear());

        return CycleAggregationNode(agg, expr, variable, listOf(),
            branches.bodyBranches[0], branches.outcomes).also {
                if (branches.out != null) {
                    outMap[it] = branches.out as Any;
                }
        }
    }

    fun visitAndGetTypedVar(ctx: LoqiGrammarParser.TypedVarContext): TypedVariable {
        return TypedVariable(
            ctx.type().text,
            ctx.id().text
        )
    }

    fun visitAndGetTypedVar(ctx: LoqiGrammarParser.TypedVarLinearContext): TypedVariable {
        return TypedVariable(
            ctx.type().text,
            ctx.id().text
        )
    }

    fun visitTupleQuestion(ctx: LoqiGrammarParser.QuestionContext): TupleQuestionNode {
        val questions: List<TupleQuestionNode.TupleQuestionPart> = ctx.exp().map {
            TupleQuestionNode.TupleQuestionPart(visitExp(it), listOf())
        }
        val branches = Outcomes(ctx.tupleBranch().map {
            val tuple = parseTuple(it.tuple())
            val thoughtBranch = visitThoughtBranch(it.thoughtBranch())
            Outcome(tuple, thoughtBranch.start)
        })
        if (branches.size != questions.size) {
            throw LoqiDomainBuildException("Branch size doesn't match with questions in TupleQuestionNode")
        }
        return TupleQuestionNode(questions, branches)
    }

    fun parseTuple(ctx: LoqiGrammarParser.TupleContext): ValueTuple {
        return ValueTuple(ctx.exp().map {visitExp(it).unwrap()})
    }

    override fun visitQuestion(ctx: LoqiGrammarParser.QuestionContext): QuestionNode {
        if (!ctx.getTokens(TUPLE).isEmpty()) {
            visitTupleQuestion(ctx);
        }

        val expr = visitExp(ctx.exp(0));
        val branches = visitExpressionBranches(ctx.expBranches())

        if (!branches.bodyBranches.isEmpty()) {
            throw LoqiDomainBuildException("Question cannot have thought branches")
        }

        if (branches.out == null) {
            throw LoqiDomainBuildException("Specify out branch for this question")
        }

        val trivExpr = if (ctx.exp(1) != null) visitExp(ctx.exp(1)) else null;
        var isSwitch = !ctx.getTokens(SWITCH).isEmpty();
        return QuestionNode(expr, branches.outcomes, isSwitch,trivExpr).also {
            outMap[it] = branches.out;
        }
    }

    override fun visitFindAction(ctx: LoqiGrammarParser.FindActionContext): FindActionNode {
        val variable = visitAndGetTypedVar(ctx.typedVar());
        val expr = visitExp(ctx.exp());

        val decls = ctx.treeVarDecls()?.treeVarDecl()?.map {
            decl -> if (decl.exp() == null) {
                throw LoqiDomainBuildException("Variable assignments must have value");
            } else {
                DecisionTreeVarAssignment(TypedVariable(
                    decl.getToken(ID, 1).text,
                    decl.getToken(ID, 0).text,
                ), visitExp(decl.exp()))
            }
        } ?: emptyList()

        val branches = if (ctx.expBranches() == null) {
            BranchInfo(true, listOf(), Outcomes(mutableListOf(
                Outcome(true, DummyNode())
            )))
        } else {
            visitExpressionBranches(ctx.expBranches())
        }

        if (!branches.bodyBranches.isEmpty()) {
            throw LoqiDomainBuildException("Find action cannot have thought branches")
        }

        val boolOutcomes = Outcomes(branches.outcomes.map { outcome ->
            if (outcome.key is Boolean) {
                return@map Outcome(outcome.key, outcome.node)
            }
            throw LoqiDomainBuildException("Find action cannot have non-boolean outcomes")
        })

        return FindActionNode(DecisionTreeVarAssignment(variable, expr),
            listOf(),decls, boolOutcomes).also {
                if (branches.out != null) {
                    outMap[it] = branches.out as Boolean
                } else {
                    outMap[it] = true
                }
        }
    }

    override fun visitFullTreeDecl(ctx: LoqiGrammarParser.FullTreeDeclContext): DecisionTree {
        val tree = visitTreeDecl(ctx.treeDecl());
        val helpers = ctx.treeDeclHelpers();
        helpers.forEach { helper ->
            val child = helper.getChild(0)
            if (child is LoqiGrammarParser.MetaDeclContext) {
                applyMetadataDecl(child);
            }
        }
        return tree
    }

    fun resolveCallNamespace(id: LoqiGrammarParser.NamespaceResolutionContext): CallableProcedureDef? {
        val resolutions = id.ID().map{ i -> i.text}.toMutableList()
        val resolutionSize = resolutions.size
        val procName = resolutions.removeLast()
        if (resolutionSize == 1) {
            return procedures.get(GlobalNamespace)
                    ?.find { proc -> proc.name == resolutions[0] }
        } else {
            for (ns in procedures) {
                val res = ns.key.getScopeResolution().filter { !it.isEmpty() }
                if (res.equals(resolutions)) {
                    for (proc in ns.value) {
                        if (proc.name == procName) {
                            return proc
                        }
                    }
                }
            }
        }
        return null
    }

    fun applyMetadataDecl(meta: LoqiGrammarParser.MetaDeclContext) {
        val id : String = meta.id().text
        if (id in aliases) {
            for (node in aliases[id]!!) {
                node.fillMetadata(meta.metadataSection())
            }
        } else {
            throw LoqiDomainBuildException("Unused metadata with identifier $id detected")
        }
    }

    override fun visitTreeDecl(ctx: TreeDeclContext?): DecisionTree {
        val variables = ctx?.treeVarDecls()?.treeVarDecl()?.filter{variable ->
            variable.exp() == null
        }?.map { variable -> TypedVariable(
            variable.type().text,
            variable.id().text,
        )
        } ?: emptyList()

        val varAssignments = ctx?.treeVarDecls()?.treeVarDecl()?.filter{variable ->
            variable.exp() != null
        }?.map { variable -> domainOpAt(ctx.start.line) { DecisionTreeVarAssignment(TypedVariable(
            variable.type().text,
            variable.id().text,
        ), visitExp(variable.exp()));
        }} ?: emptyList()

        decisionTree = DecisionTree(variables, varAssignments,
            visitThoughtBranch(ctx?.thoughtBranch() ?: throw ThisShouldNotHappen()))
        decisionTree?.fillMetadata(ctx.metadataSection())


        return decisionTree as DecisionTree
    }

    /* -------- Helpers ----------*/

    private fun MetaOwner.fillMetadata(ctx: MetadataSectionContext?) {
        metadata.fill(ctx)
    }

    private fun MetaData.fill(ctx: MetadataSectionContext?) {
        if (ctx == null) return
        for (metadataPropertyDecl in ctx.metadataPropertyDecl()) {
            val locCode =
                if (metadataPropertyDecl.id().size == 2)
                    metadataPropertyDecl.id(0).getName()
                else
                    null
            val propName = metadataPropertyDecl.id().last().getName()

            val value = metadataPropertyDecl.value().getTypeAndValue().value

            this.add(locCode, propName, value)
        }
    }

    private fun IdContext.getName(): String {
        return ID().text.removeSurrounding("`")
    }

    private fun ValueContext.getTypeAndValue(): TypeAndValue<*> {
        if (INTEGER() != null) return TypeAndValue(IntegerType(), INTEGER().text.toInt())
        if (DOUBLE() != null) return TypeAndValue(DoubleType(), DOUBLE().text.toDouble())
        if (BOOLEAN() != null) return TypeAndValue(BooleanType, BOOLEAN().text.toBoolean())
        if (STRING() != null) return TypeAndValue(StringType, STRING().text.extract())
        if (enumValueRef() != null) {
            val enumValue = enumValueRef().getRef()
            return TypeAndValue(EnumType(enumValue.enumName), enumValue)
        }
        throw ThisShouldNotHappen()
    }

    private fun String.extract(): String {
        var out = this
        if (out.startsWith("\"\"\"") || out.startsWith("'''")) {
            out = out.substring(3, out.length - 3)
            out = out.trimIndent()
        } else if (out.startsWith("\"") || out.startsWith("'")) {
            out = out.substring(1, out.length - 1)
        }
        return out.extractEscapes()
    }

    private fun EnumValueRefContext.getRef() = EnumValueRef(id(0).getName(), id(1).getName())

}