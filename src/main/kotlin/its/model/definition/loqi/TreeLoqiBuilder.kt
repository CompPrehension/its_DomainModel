package its.model.definition.loqi

import its.model.TypedVariable
import its.model.ValueTuple
import its.model.definition.*
import its.model.definition.loqi.LoqiGrammarParser.*
import its.model.definition.loqi.LoqiStringUtils.extractEscapes
import its.model.definition.loqi.tree.FragmentDef
import its.model.definition.procedures.*
import its.model.definition.types.*
import its.model.expressions.Operator
import its.model.expressions.literals.DecisionTreeVarLiteral
import its.model.expressions.literals.ValueLiteral
import its.model.nodes.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTree
import java.io.Reader
import java.net.URL
import java.util.*

class TreeLoqiBuilder(
    private var decisionTree : DecisionTree?,
    private val procedureRegistry: ProcedureRegistry = BuiltinProcedureRegistry,
) : LoqiGrammarBaseVisitor<DecisionTreeElement>() {

    private val outMap: MutableMap<DecisionTreeElement, Any> = mutableMapOf()
    private val aliases: MutableMap<String, MutableSet<DecisionTreeElement>> = mutableMapOf()
    private val fragments: MutableMap<String, RegisteredFragment> = mutableMapOf()
    private val fragmentInliningStack = ArrayDeque<FragmentInliningContext>()
    private val fragmentCallStack = ArrayDeque<String>()

    // TODO: needs more refactoring, more debugging

    data class BranchInfo<T>(val out: T?, val bodyBranches: List<ThoughtBranch>, val outcomes: Outcomes<T>)

    private data class BuiltStatement(
        val start: DecisionTreeNode,
        val tail: DecisionTreeNode,
    )

    private data class BuiltThoughtBranch(
        val branch: ThoughtBranch,
        val tail: DecisionTreeNode,
    )

    private data class RegisteredFragment(
        val definition: FragmentDef,
        val body: ThoughtBranchContext,
    )

    private data class FragmentInliningContext(
        val fragmentName: String,
        val variableMapping: Map<String, String>,
    )

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
        return ctx.accept(OperatorLoqiBuilder(procedureRegistry, currentDecisionTreeVarNameResolver()))
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
        return visitCallStmtAsBuiltStatement(ctx).start
    }

    private fun visitCallStmtAsBuiltStatement(ctx: LoqiGrammarParser.CallStmtContext): BuiltStatement {
        resolveFragment(ctx.namespaceResolution())?.let { fragment ->
            return inlineFragment(fragment, ctx)
        }

        val procedure = resolveProcedure(ctx.namespaceResolution())
            ?: throw DomainUseException("Procedure `${ctx.namespaceResolution().text}` not found")
        val args = buildCallArgs(ctx.callArgs())
        return procedure.callNode(args, DummyNode()).asBuiltStatement()
    }

    override fun visitThoughtBranch(ctx: LoqiGrammarParser.ThoughtBranchContext): ThoughtBranch {
        return buildThoughtBranch(ctx).branch
    }

    private fun buildThoughtBranch(
        ctx: LoqiGrammarParser.ThoughtBranchContext,
        leaveTailOpen: Boolean = false,
    ): BuiltThoughtBranch {
        if (ctx.stmts().stmt().isEmpty()) {
            throw LoqiDomainBuildException(ctx.stmts().start.line, "Thought branch is empty")
        }
        val stmts = ctx.stmts().stmt();
        val first = buildStmt(stmts[0])
        var prev = first.tail
        for (i in 1 until stmts.size) {
            val newStmt = buildStmt(stmts[i]);
            domainOpAt(stmts[i].start.line) {
                connectToNextStatement(prev, newStmt.start)
            }
            prev = newStmt.tail;
        }

        if (!leaveTailOpen) {
            domainOpAt(ctx.stop?.line ?: ctx.start.line) {
                closeOutRedirect(prev)
            }
        }
        return BuiltThoughtBranch(
            ThoughtBranch(first.start).also {
                domainOpAt(ctx.start.line) {
                    checkResultReachability(it)
                }
            },
            prev
        )
    }

    override fun visitStmt(ctx: LoqiGrammarParser.StmtContext): DecisionTreeNode {
        return buildStmt(ctx).start
    }

    private fun buildStmt(ctx: LoqiGrammarParser.StmtContext): BuiltStatement {
        val child = ctx.getChild(0)

        val result = if (child is LoqiGrammarParser.ConcludeBranchResultContext) {
            domainOpAt(ctx.start.line) { visitConcludeBranchResult(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.BranchAggregationContext) {
            domainOpAt(ctx.start.line) { visitBranchAggregation(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.CycleAggregationContext) {
            domainOpAt(ctx.start.line) { visitCycleAggregation(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.WhileCycleContext) {
            domainOpAt(ctx.start.line) { visitWhileCycle(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.FindActionContext) {
            domainOpAt(ctx.start.line) { visitFindAction(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.QuestionContext) {
            domainOpAt(ctx.start.line) { visitQuestion(child).asBuiltStatement() }
        } else if (child is LoqiGrammarParser.CallStmtContext) {
            domainOpAt(ctx.start.line) { visitCallStmtAsBuiltStatement(child) }
        } else {
            throw ThisShouldNotHappen()
        }

        if (ctx.AS() != null && ctx.id() != null) {
            if (ctx.id().text !in aliases) {
                aliases[ctx.id().text] = HashSet();
            }
            aliases[ctx.id().text]?.add(result.start);
        }
        return result
    }

    private fun DecisionTreeNode.asBuiltStatement(): BuiltStatement {
        return BuiltStatement(this, this)
    }

    private fun isInliningFragment(): Boolean {
        return !fragmentInliningStack.isEmpty()
    }

    private fun connectToNextStatement(prev: DecisionTreeNode, next: DecisionTreeNode) {
        if (prev is ProcedureCallNode && prev.next is DummyNode) {
            prev.next = next
        } else if (prev in outMap && prev is LinkNode<*>) {
            val outcome = (prev as LinkNode<Any>).outcomes.filter { value -> value.key == outMap[prev] }
            if (outcome.count() != 1) {
                throw ThisShouldNotHappen()
            } else {
                prev.outcomes.remove(outcome[0])
                prev.outcomes.add(Outcome(outMap[prev] as Any, next)
                    .also {checkResultReachability(it)}
                    .also {
                        val alias = findAlias(outcome[0])
                        aliases[alias]?.remove(outcome[0])
                        aliases[alias]?.add(it)
                    }
                )
            }
        } else {
            throw LoqiDomainBuildException("Statement can't be reached")
        }
    }

    private fun closeOutRedirect(prev: DecisionTreeNode) {
        if (prev in outMap && prev is LinkNode<*>) {
            val outcome = (prev as LinkNode<Any>).outcomes.filter { value -> value.key == outMap[prev] }
            if (outcome.count() != 1) {
                throw ThisShouldNotHappen()
            } else {
                prev.outcomes.remove(outcome[0])
                if (prev !is BranchAggregationNode) checkResultReachability(outcome[0], true) // так как Branch Aggregation Node может завершать ветвь
            }
        }
    }

    override fun visitConcludeBranchResult(ctx: LoqiGrammarParser.ConcludeBranchResultContext): DecisionTreeNode {
        val result : DecisionTreeNode
        var actionExp: Operator? = null;
        if (ctx.exp() != null) {
            actionExp = visitExp(ctx.exp());
        }
        if (ctx.outcomeType() != null) {
            result = BranchResultNode(parseBranchResult(ctx.outcomeType().text), actionExp);
            result.fillMetadata(ctx.metadataSection())
        } else {
            val call = visitCallStmt(ctx.callStmt());
            if (call !is ProcedureCallNode) {
                throw LoqiDomainBuildException(ctx.start.line, "Fragments cannot be used in conclude redirects")
            }
            result = BranchResultRedirectingNode(call.asExpr(), actionExp);
        }
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
            Outcomes(branches.outcomes)
        ).also {
            if (branches.out != null) {
                outMap[it] = branches.out as Any;
            }
        }
    }

    fun visitBranchResultOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<BranchResult> {
        return Outcomes(list.map { res ->
            Outcome(
                parseBranchResult(res.exp(), res.outcomeType()) as BranchResult,
                visitThoughtBranch(res.thoughtBranch()).start
            ).also { metaAliasForBranch(res, it) }
        }.toMutableList())
    }

    fun visitExprOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<Any> {
        return Outcomes(list.map { res ->
            val exp = if (res.exp() != null) visitExp(res.exp()).unwrap() else visitAndObtainBool(res.exp(), res.outcomeType())
            if (exp == null) {
                throw ThisShouldNotHappen()
            }
            Outcome(exp,
                visitThoughtBranch(res.thoughtBranch()).start).also { metaAliasForBranch(res, it)}
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

    fun visitExpressionBranches(ctx: LoqiGrammarParser.ExpBranchesContext, allowOnlyResultOut: Boolean = false): BranchInfo<*> {
        if (ctx.branches() == null) {
            val exp = visitExp(ctx.exp()).unwrap();
            if (exp !is Boolean) {
                throw LoqiDomainBuildException("Out with else is supported with boolean results")
            }
            val opposite = !exp;

            return BranchInfo(exp, listOf(), Outcomes(mutableListOf(
                Outcome(opposite, visitThoughtBranch(ctx.thoughtBranch()).start),
                Outcome(exp, DummyNode()).also {metaAliasForOut(ctx.out(), it)},
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
            allowOnlyResultOut && !outBranch.isEmpty() &&
            parseBranchResult(outBranch[0].exp(), outBranch[0].outcomeType()) == null
        ) {
            // если требуется обязательно делать out как boolean/result
            throw LoqiDomainBuildException("You can redirect only one resulting (boolean or branch result) outcome branch");
        }

        val outcomeOut = if (outBranch.isEmpty()) null else {
            if (outBranch[0].exp() == null) {
                visitAndObtainBool(outBranch[0].exp(), outBranch[0].outcomeType())
            } else {
                visitExp(outBranch[0].exp()).unwrap()
            }
        }

        if (outcomeOut != null && outcomes.filter { value -> value.key == outcomeOut}.none()) {
            outcomes.add(Outcome(outcomeOut, DummyNode()).also{metaAliasForOut(ctx.out(), it)});
        }
        
        outcomes.forEach {checkResultReachability(it)}
        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    fun visitAggregationBranches(ctx: LoqiGrammarParser.AggBranchesContext, defaultOut: BranchResult? = null):
            BranchInfo<BranchResult> {

        if (ctx.branches() == null) {
            val outcomeType = parseBranchResult(ctx.outcomeType().text)

            return BranchInfo(outcomeType, listOf(visitThoughtBranch(ctx.thoughtBranch())),
                Outcomes(listOf(Outcome(outcomeType, DummyNode()).also{metaAliasForOut(ctx.out(), it)}))
            )
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
            outcomes.add(Outcome(outcomeOut, DummyNode()).also{
                if (!outBranch.isEmpty()) metaAliasForBranch(outBranch[0], it)
            });
        }

        outcomes.forEach {checkResultReachability(it)}
        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    fun visitAbstractBranches(list: List<LoqiGrammarParser.BranchContext>): List<ThoughtBranch> {
        return list.map { branch ->
            val expr = branch.exp()?.let { visitExp(it) }

            if (expr is DecisionTreeVarLiteral) {
                val result = visitThoughtBranch(branch.thoughtBranch()).also { metaAliasForBranch(branch, it)}
                return@map result
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
            resolveDecisionTreeVarName(ctx.id().getName())
        )
    }

    fun visitAndGetTypedVar(ctx: LoqiGrammarParser.TypedVarLinearContext): TypedVariable {
        return TypedVariable(
            ctx.type().text,
            resolveDecisionTreeVarName(ctx.id().getName())
        )
    }

    fun visitTupleQuestion(ctx: LoqiGrammarParser.QuestionContext): TupleQuestionNode {
        val questions: List<TupleQuestionNode.TupleQuestionPart> = ctx.exp().map {
            TupleQuestionNode.TupleQuestionPart(visitExp(it), listOf())
        }
        val branches = Outcomes(ctx.tupleBranch().map {
            val tuple = parseTuple(it.tuple())
            val thoughtBranch = visitThoughtBranch(it.thoughtBranch())
            Outcome(tuple, thoughtBranch.start).also { obj -> metaAliasForBranch(it, obj)}
        })
        if (branches.size != questions.size) {
            throw LoqiDomainBuildException("Branch size doesn't match with questions in TupleQuestionNode")
        }
        branches.forEach { checkResultReachability(it) }
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

        val trivExpr = if (ctx.exp(1) != null) visitExp(ctx.exp(1)) else null;
        var isSwitch = !ctx.getTokens(SWITCH).isEmpty();
        return QuestionNode(expr, branches.outcomes as Outcomes<Any>, isSwitch,trivExpr).also {
            if (branches.out != null) outMap[it] = branches.out;
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
                    decl.type().text,
                    resolveDecisionTreeVarName(decl.id().getName()),
                ), visitExp(decl.exp()))
            }
        } ?: emptyList()

        val branches = if (ctx.expBranches() == null) {
            BranchInfo(true, listOf(), Outcomes(mutableListOf(
                Outcome(true, DummyNode())
            )))
        } else {
            visitExpressionBranches(ctx.expBranches(), true)
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
        ctx.treeDeclHelpers().forEach { helper ->
            helper.fragmentDef()?.let { registerFragment(it) }
        }

        val tree = visitTreeDecl(ctx.treeDecl());
        val helpers = ctx.treeDeclHelpers();
        helpers.forEach { helper ->
            helper.metaDecl()?.let { applyMetadataDecl(it) }
        }
        return tree
    }

    private fun resolveProcedure(id: LoqiGrammarParser.NamespaceResolutionContext): CallableProcedureDef? {
        val resolutions = id.ID().map { it.text.removeSurrounding("`") }
        if (resolutions.isEmpty()) {
            return null
        }
        return procedureRegistry.resolve(resolutions.dropLast(1), resolutions.last())
    }

    private fun resolveFragment(id: LoqiGrammarParser.NamespaceResolutionContext): RegisteredFragment? {
        val resolutions = id.ID().map { it.text.removeSurrounding("`") }
        if (resolutions.isEmpty()) {
            return null
        }
        if (resolutions.dropLast(1) != ProcedureNamespaces.FRAGMENT.getScopeParts()) {
            return null
        }
        return fragments[resolutions.last()]
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
            resolveDecisionTreeVarName(variable.id().getName()),
        )
        } ?: emptyList()

        val varAssignments = ctx?.treeVarDecls()?.treeVarDecl()?.filter{variable ->
            variable.exp() != null
        }?.map { variable -> domainOpAt(ctx.start.line) { DecisionTreeVarAssignment(TypedVariable(
            variable.type().text,
            resolveDecisionTreeVarName(variable.id().getName()),
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

    private fun registerFragment(ctx: FragmentDefContext) {
        val name = ctx.id().getName()
        if (fragments.containsKey(name)) {
            throw LoqiDomainBuildException(ctx.start.line, "Fragment `$name` is already declared")
        }

        val arguments = ctx.treeVarDecls()?.treeVarDecl()?.map { arg ->
            if (arg.exp() != null) {
                throw LoqiDomainBuildException(arg.start.line, "Fragment argument `${arg.id().getName()}` cannot have an initializer")
            }
            ProcedureArgument(arg.id().getName(), arg.type().getType())
        } ?: emptyList()

        fragments[name] = RegisteredFragment(
            FragmentDef(name, arguments),
            ctx.thoughtBranch()
        )
    }

    private fun inlineFragment(fragment: RegisteredFragment, callCtx: CallStmtContext): BuiltStatement {
        val fragmentName = fragment.definition.qualifiedName
        if (fragmentName in fragmentCallStack) {
            val cycle = (fragmentCallStack + fragmentName).joinToString(" -> ")
            throw LoqiDomainBuildException(callCtx.start.line, "Recursive fragment expansion detected: $cycle")
        }

        val actualArguments = buildCallArgs(callCtx.callArgs())
        validateFragmentArguments(fragment, actualArguments, callCtx)
        val variableMapping = fragment.definition.arguments.zip(actualArguments).associate { (formal, actual) ->
            formal.name to (actual as DecisionTreeVarLiteral).name
        }

        fragmentCallStack.addLast(fragmentName)
        fragmentInliningStack.addLast(FragmentInliningContext(fragmentName, variableMapping))
        try {
            val branch = buildThoughtBranch(fragment.body, leaveTailOpen = true)
            return BuiltStatement(branch.branch.start, branch.tail)
        } finally {
            fragmentInliningStack.removeLast()
            fragmentCallStack.removeLast()
        }
    }

    private fun validateFragmentArguments(
        fragment: RegisteredFragment,
        actualArguments: List<Operator>,
        callCtx: CallStmtContext,
    ) {
        val expectedCount = fragment.definition.arguments.size
        if (actualArguments.size != expectedCount) {
            throw LoqiDomainBuildException(
                callCtx.start.line,
                "Argument size mismatch for fragment `${fragment.definition.qualifiedName}` (${actualArguments.size} != $expectedCount)"
            )
        }
        actualArguments.forEachIndexed { index, arg ->
            if (arg !is DecisionTreeVarLiteral) {
                val expected = fragment.definition.arguments[index]
                throw LoqiDomainBuildException(
                    callCtx.start.line,
                    "Fragment argument `${expected.name}` must be a decision tree variable literal"
                )
            }
        }
    }

    private fun buildCallArgs(ctx: CallArgsContext?): List<Operator> {
        return ctx?.exp()?.map { visitExp(it) } ?: emptyList()
    }

    private fun currentDecisionTreeVarNameResolver(): DecisionTreeVarNameResolver {
        return DecisionTreeVarNameResolver { name -> resolveDecisionTreeVarName(name) }
    }

    private fun resolveDecisionTreeVarName(name: String): String {
        val iterator = fragmentInliningStack.descendingIterator()
        while (iterator.hasNext()) {
            val resolved = iterator.next().variableMapping[name]
            if (resolved != null) {
                return resolved
            }
        }
        return name
    }

    private fun TypeContext.getType(): Type<*> {
        if (intType() != null) return IntegerType(intType().intRange()?.getRange() ?: AnyNumber)
        if (doubleType() != null) return DoubleType(doubleType().doubleRange()?.getRange() ?: AnyNumber)
        if (BOOL_TYPE() != null) return BooleanType
        if (STRING_TYPE() != null) return StringType
        if (id() != null) return EnumType(id().getName())
        throw ThisShouldNotHappen()
    }

    private fun IntRangeContext.getRange(): Range {
        return if (intList() != null) {
            DiscreteRange(intList().INTEGER().map { it.text.toDouble() }.toSet())
        } else {
            val start =
                if (intRangeStart().INTEGER() != null) intRangeStart().INTEGER().text.toDouble()
                else Double.NEGATIVE_INFINITY
            val end =
                if (INTEGER() != null) INTEGER().text.toDouble()
                else Double.POSITIVE_INFINITY

            if (start.isInfinite() && end.isInfinite()) AnyNumber
            else ContinuousRange(start to end)
        }
    }

    private fun DoubleRangeContext.getRange(): Range {
        return if (doubleList() != null) {
            DiscreteRange(doubleList().DOUBLE().map { it.text.toDouble() }.toSet())
        } else {
            val start =
                if (doubleRangeStart().DOUBLE() != null) doubleRangeStart().DOUBLE().text.toDouble()
                else Double.NEGATIVE_INFINITY
            val end =
                if (DOUBLE() != null) DOUBLE().text.toDouble()
                else Double.POSITIVE_INFINITY

            if (start.isInfinite() && end.isInfinite()) AnyNumber
            else ContinuousRange(start to end)
        }
    }

    private fun checkResultReachability(node: DecisionTreeNode, strict: Boolean): Boolean {
        if (isInliningFragment()) {
            return true
        }
        val visiting = HashSet<DecisionTreeNode>()
        val ok = checkReachabilityInternal(node, visiting, strict)
        if (!ok) {
            throw LoqiDomainBuildException(
                "Decision tree path does not end with BranchResultNode starting from ${node.description}"
            )
        }
        return true
    }

    private fun checkResultReachability(branch: ThoughtBranch, strict: Boolean = false): Boolean {
        if (isInliningFragment()) {
            return true
        }
        val visiting = HashSet<DecisionTreeNode>()
        val ok = checkReachabilityInternal(branch.start, visiting, strict)
        if (!ok) {
            throw LoqiDomainBuildException(
                "ThoughtBranch does not end with BranchResultNode starting from ${branch.start.description}"
            )
        }
        return true
    }

    private fun <V> checkResultReachability(outcome: Outcome<V>, strict: Boolean = false): Boolean {
        if (isInliningFragment()) {
            return true
        }
        val visiting = HashSet<DecisionTreeNode>()
        val ok = checkReachabilityInternal(outcome.node, visiting, strict)
        if (!ok) {
            throw LoqiDomainBuildException(
                "Outcome (key=${outcome.key}) does not end with BranchResultNode starting from ${outcome.node.description}"
            )
        }
        return true
    }

    private fun checkReachabilityInternal(node: DecisionTreeNode, visiting: MutableSet<DecisionTreeNode>, strict: Boolean): Boolean {
        if (node is BranchResultNode || (node is DummyNode && !strict)) {
            return true
        }
        if (!visiting.add(node)) {
            return false
        }

        try {
            when (node) {
                is CycleAggregationNode -> {
                    if (!checkReachabilityInternal(node.thoughtBranch.start, visiting, strict)) {
                        throw LoqiDomainBuildException("ThoughtBranch in ${node.description} does not end with BranchResultNode")
                    }
                }
                is BranchAggregationNode -> {
                    node.thoughtBranches.forEachIndexed { index, branch ->
                        if (!checkReachabilityInternal(branch.start, visiting, strict)) {
                            throw LoqiDomainBuildException(
                                "ThoughtBranch[$index] in ${node.description} does not end with BranchResultNode"
                            )
                        }
                    }
                }
                is WhileCycleNode -> {
                    if (!checkReachabilityInternal(node.thoughtBranch.start, visiting, strict)) {
                        throw LoqiDomainBuildException("ThoughtBranch in ${node.description} does not end with BranchResultNode")
                    }
                }

                else -> {}
            }

            if (node is LinkNode<*>) {
                node.outcomes.forEachIndexed { index, outcome ->
                    if (!checkReachabilityInternal(outcome.node, visiting, strict)) {
                        throw LoqiDomainBuildException(
                            "Outcome[$index] (key=${outcome.key}) in ${node.description} does not end with BranchResultNode"
                        )
                    }
                }
                return true
            }

            return false
        } finally {
            visiting.remove(node)
        }
    }

    private fun metaAliasForBranch(branch: LoqiGrammarParser.BranchContext, result: DecisionTreeElement) {
        if (branch.arrow().ID() != null) {
            if (branch.arrow().ID().text !in aliases) {
                aliases[branch.arrow().ID().text] = HashSet();
            }
            aliases[branch.arrow().ID().text]?.add(result)
        }
    }

    private fun metaAliasForBranch(branch: LoqiGrammarParser.TupleBranchContext, result: DecisionTreeElement) {
        if (branch.arrow().ID() != null) {
            if (branch.arrow().ID().text !in aliases) {
                aliases[branch.arrow().ID().text] = HashSet();
            }
            aliases[branch.arrow().ID().text]?.add(result)
        }
    }

    private fun metaAliasForOut(out: LoqiGrammarParser.OutContext?, result: DecisionTreeElement) {
        if (out?.ID() != null) {
            if (out.ID().text !in aliases) {
                aliases[out.ID().text] = HashSet();
            }
            aliases[out.ID().text]?.add(result)
        }
    }

    private fun findAlias(element: DecisionTreeElement): String? {
        return aliases.entries
            .firstOrNull { element in it.value }
            ?.key
    }

}
