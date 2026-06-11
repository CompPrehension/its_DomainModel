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
    private val debugMeta: Boolean = false,
) : LoqiGrammarBaseVisitor<DecisionTreeElement>() {

    /*
     * Для statement с `... -> out` запоминаем ключи outcomes, которые нужно
     * пересоединить к следующему statement при сборке окружающей ThoughtBranch.
     */
    private val outMap: MutableMap<DecisionTreeElement, List<Any>> = mutableMapOf()
    private val aliases: MutableMap<String, MutableSet<DecisionTreeElement>> = mutableMapOf()
    private val fragments: MutableMap<String, RegisteredFragment> = mutableMapOf()

    /*
     * Встраивание фрагментов должно быть рекурсивно, но рекурсивные вызовы
     * фрагментов запрещены. Стек также нужен для подстановки переменных
     * фрагмента в переменные места вызова.
     */
    private val fragmentInliningStack = ArrayDeque<FragmentInliningContext>()
    private val fragmentCallStack = ArrayDeque<String>()

    // TODO: needs more refactoring, more debugging

    /*
     * Результат разбора блока веток:
     * - `out`: ключи outcomes, которые продолжаются в следующий statement;
     * - `bodyBranches`: анонимные подветви для aggregation;
     * - `outcomes`: уже собранные переходы по ключам.
     */
    data class BranchInfo<T>(val out: List<T>, val bodyBranches: List<ThoughtBranch>, val outcomes: Outcomes<T>)

    /*
     * Statement не всегда равен одному узлу. Например, встроенный фрагмент
     * имеет стартовый узел, хвост для линейной сборки и открытые выходы `out`.
     */
    private data class BuiltStatement(
        var start: DecisionTreeNode,
        val tail: DecisionTreeNode,
        var openFragmentExits: List<OpenFragmentExit> = emptyList(),
    )

    /*
     * У ThoughtBranch публично есть только `start`, но билдеру нужен хвост
     * последнего statement, чтобы пришивать следующие statement.
     */
    private data class BuiltThoughtBranch(
        val branch: ThoughtBranch,
        val tail: DecisionTreeNode,
    )

    /*
     * Тело фрагмента храним как parse tree и пересобираем на каждый вызов:
     * каждое встраивание должно получить свежие экземпляры узлов.
     */
    private data class RegisteredFragment(
        val definition: FragmentDef,
        val body: ThoughtBranchContext,
    )

    private data class FragmentInliningContext(
        val fragmentName: String,
        val variableMapping: Map<String, String>,
    )

    /*
     * Перенаправления при вызове фрагмента:
     * - `replacements`: conclude(result) сразу заменяется новой веткой;
     * - `outResults`: conclude(result) удаляется и соединяется со следующим statement.
     */
    private data class FragmentCallRedirects(
        val replacements: Map<BranchResult, DecisionTreeNode>,
        val outResults: Set<BranchResult>,
    )

    /*
     * Отложенная точка замены для fragment `... -> out`.
     * Lambda знает, как позже заменить конкретный терминальный узел или outcome.
     */
    private data class OpenFragmentExit(
        val result: BranchResult,
        val replace: (DecisionTreeNode) -> Unit,
    )

    /*
     * Пока строится ветка-замена для фрагмента, conclude с тем же результатом
     * тоже должны заменяться. Стек распространяет текущий контекст на вложенные фрагменты.
     */
    private val fragmentResultReplacementStack = ArrayDeque<Map<BranchResult, DecisionTreeNode>>()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun buildTree(file: URL, debugMeta: Boolean = false): DecisionTree {
            file.openStream().buffered().reader().use { reader ->
                return buildTree(reader, debugMeta)
            }
        }

        @JvmStatic
        @JvmOverloads
        fun buildTree(reader: Reader, debugMeta: Boolean = false): DecisionTree {
            val lexer = LoqiGrammarLexer(CharStreams.fromReader(reader))
            val tokens = CommonTokenStream(lexer)
            val parser = LoqiGrammarParser(tokens)

            val errorListener = SyntaxErrorListener()
            parser.addErrorListener(errorListener)

            val tree: ParseTree = parser.fullTreeDecl()
            errorListener.getSyntaxErrors().firstOrNull()?.exception?.apply { throw this }

            val builder = TreeLoqiBuilder(null, debugMeta = debugMeta)
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

        if (ctx.callRedirBranches() != null) {
            throw LoqiDomainBuildException(ctx.start.line, "Call result redirection is supported only for fragments")
        }

        val procedure = resolveProcedure(ctx.namespaceResolution())
            ?: throw DomainUseException("Procedure `${ctx.namespaceResolution().text}` not found")
        val args = buildCallArgs(ctx.callArgs())
        return procedure.callNode(args, DummyNode())
            .withDebugLine(ctx.start.line)
            .asBuiltStatement()
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
        var prev = first
        for (i in 1 until stmts.size) {
            val newStmt = buildStmt(stmts[i]);
            domainOpAt(stmts[i].start.line) {
                connectToNextStatement(prev, newStmt.start)
            }
            prev = newStmt;
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
            prev.tail
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
            domainOpAt(ctx.start.line) {
                val node = if (!child.getTokens(LoqiGrammarParser.TUPLE).isEmpty()) {
                    visitTupleQuestion(child)
                } else {
                    visitQuestion(child)
                }
                node.asBuiltStatement()
            }
        } else if (child is LoqiGrammarParser.CallStmtContext) {
            domainOpAt(ctx.start.line) { visitCallStmtAsBuiltStatement(child) }
        } else {
            throw ThisShouldNotHappen()
        }

        if (ctx.AS() != null && ctx.id() != null) {
            registerAlias(ctx.id().getName(), result.start)
        }
        return result
    }

    private fun DecisionTreeNode.asBuiltStatement(): BuiltStatement {
        return BuiltStatement(this, this)
    }

    private fun isInliningFragment(): Boolean {
        return !fragmentInliningStack.isEmpty()
    }

    private fun currentFragmentResultReplacement(result: BranchResult): DecisionTreeNode? {
        val iterator = fragmentResultReplacementStack.descendingIterator()
        while (iterator.hasNext()) {
            val replacement = iterator.next()[result]
            if (replacement != null) {
                return replacement
            }
        }
        return null
    }

    /*
     * Здесь соединяется линейная цепочка statement.
     * Обычный call заканчивается DummyNode, ветвящиеся узлы держат dummy outcomes
     * в `outMap`, а fragment `out` приносит свои lambdas замены.
     */
    private fun connectToNextStatement(prev: BuiltStatement, next: DecisionTreeNode) {
        if (prev.openFragmentExits.isNotEmpty()) {
            /*
             * Фрагмент мог оставить несколько терминальных conclude, которые надо
             * продолжить одним и тем же следующим узлом.
             */
            prev.openFragmentExits.forEach { exit ->
                exit.replace(next)
            }
            checkResultReachability(next, false)
            return
        }

        val prevTail = prev.tail
        if (prevTail is ProcedureCallNode && prevTail.next is DummyNode) {
            prevTail.next = next
        } else if (prevTail in outMap && prevTail is LinkNode<*>) {
            val outKeys = outMap[prevTail] ?: throw ThisShouldNotHappen()
            val outcomes = (prevTail as LinkNode<Any>).outcomes.filter { value -> value.key in outKeys }
            if (outcomes.count() != outKeys.count()) {
                throw ThisShouldNotHappen()
            } else {
                /*
                 * `a, b -> out` превращается в несколько outcomes, которые ведут
                 * в один следующий statement. Сохраняем ключи, меняем только target.
                 */
                outcomes.forEach { outcome ->
                    prevTail.outcomes.remove(outcome)
                    prevTail.outcomes.add(Outcome(outcome.key, next)
                        .also {checkResultReachability(it)}
                        .also {
                            val alias = findAlias(outcome)
                            aliases[alias]?.remove(outcome)
                            aliases[alias]?.add(it)
                        }
                    )
                }
            }
        } else {
            throw LoqiDomainBuildException("Statement can't be reached")
        }
    }

    /*
     * В конце ThoughtBranch обычные `out` outcomes удаляются.
     * Если тут остался fragment `out`, значит `fragmentCall() out X`
     * некуда продолжать, и это ошибка сборки.
     */
    private fun closeOutRedirect(prev: BuiltStatement) {
        if (prev.openFragmentExits.isNotEmpty()) {
            val results = prev.openFragmentExits.map { it.result }.distinct().joinToString(", ")
            throw LoqiDomainBuildException("Fragment call redirects `$results` to out, but there is no following statement")
        }

        val prevTail = prev.tail
        if (prevTail in outMap && prevTail is LinkNode<*>) {
            val outKeys = outMap[prevTail] ?: throw ThisShouldNotHappen()
            val outcomes = (prevTail as LinkNode<Any>).outcomes.filter { value -> value.key in outKeys }
            if (outcomes.count() != outKeys.count()) {
                throw ThisShouldNotHappen()
            } else {
                outcomes.forEach { outcome ->
                    prevTail.outcomes.remove(outcome)
                    /*
                     * Outcomes с `-> out` указывают на DummyNode до стыковки со следующим statement.
                     * При закрытии ветви они снимаются, поэтому strict-проверка здесь неуместна.
                     */
                    if (prevTail !is BranchAggregationNode) checkResultReachability(outcome, false)
                }
            }
        }
    }

    /*
     * При `true -> { ... }` у вызова фрагмента подходящие conclude заменяются
     * прямо во время построения тела фрагмента. Так aliases и вложенные вызовы
     * сразу получают актуальные узлы.
     */
    override fun visitConcludeBranchResult(ctx: LoqiGrammarParser.ConcludeBranchResultContext): DecisionTreeNode {
        val result : DecisionTreeNode
        var actionExp: Operator? = null;
        if (ctx.exp() != null) {
            actionExp = visitExp(ctx.exp());
        }
        if (ctx.outcomeType() != null) {
            val branchResult = parseBranchResult(ctx.outcomeType().text)
            val replacement = currentFragmentResultReplacement(branchResult)
            if (replacement != null) {
                return replacement
            }
            result = BranchResultNode(branchResult, actionExp);
            result.fillMetadata(ctx.metadataSection())
        } else {
            val call = visitCallStmt(ctx.callStmt());
            if (call !is ProcedureCallNode) {
                throw LoqiDomainBuildException(ctx.start.line, "Fragments cannot be used in conclude redirects")
            }
            result = BranchResultRedirectingNode(call.asExpr(), actionExp);
        }
        result.addDebugLine(ctx.start.line)
        if (ctx.id() != null) {
            registerAlias(ctx.id().getName(), result)
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

    private fun parseBranchResults(ctx: LoqiGrammarParser.ExpListContext?): List<BranchResult>? {
        if (ctx == null) {
            return null
        }
        val results = ctx.exp().map { parseBranchResult(it, null) }
        if (results.any { it == null }) {
            return null
        }
        return results.filterNotNull()
    }

    private fun parseBranchResult(ctx: LoqiGrammarParser.OutcomeTypeContext): BranchResult {
        return parseBranchResult(ctx.text)
    }

    private fun buildExpList(ctx: LoqiGrammarParser.ExpListContext): List<Any> {
        return ctx.exp().map { visitExp(it).unwrap() }
    }

    private fun <T> checkNoDuplicateOutcomeBranches(outcomes: List<Outcome<T>>) {
        val seen = HashSet<T>()
        outcomes.forEach { outcome ->
            if (!seen.add(outcome.key)) {
                throw LoqiDomainBuildException("Duplicate outcome branch for `${outcome.key}`")
            }
        }
    }

    /*
     * Ветки выражений используют значения question/find как ключи.
     * `true` и `false` неоднозначны в grammar, поэтому outcomeTypeList здесь
     * намеренно приводится к Boolean, а не к BranchResult.
     */
    private fun isExpressionOutcomeBranch(ctx: LoqiGrammarParser.BranchContext): Boolean {
        return ctx.thoughtBranch() != null &&
            (
                ctx.outcomeTypeList() != null ||
                    (
                        ctx.expList() != null &&
                            (
                                ctx.expList().exp().size != 1 ||
                                    visitAndObtainBool(ctx.expList().exp()[0], null) != null ||
                                    visitExp(ctx.expList().exp()[0]) !is DecisionTreeVarLiteral
                            )
                    )
            )
    }

    /*
     * Aggregation/cycle branches используют BranchResult-ключи.
     * Boolean literals в expList принимаются как алиасы CORRECT/ERROR,
     * чтобы старые LOQI-формы продолжали работать.
     */
    private fun isBranchResultOutcomeBranch(ctx: LoqiGrammarParser.BranchContext): Boolean {
        return ctx.thoughtBranch() != null &&
            (
                ctx.outcomeTypeList() != null ||
                    (ctx.expList() != null && parseBranchResults(ctx.expList()) != null)
            )
    }

    override fun visitWhileCycle(ctx: LoqiGrammarParser.WhileCycleContext): WhileCycleNode {
        val branches = visitAggregationBranches(ctx.aggBranches(), BranchResult.NULL)

        if (branches.bodyBranches.count() > 1 || branches.bodyBranches.count() == 0) {
            throw LoqiDomainBuildException("Cycle must have only one required branch")
        }

        return WhileCycleNode(visitExp(ctx.exp()),
            branches.bodyBranches[0],
            Outcomes(branches.outcomes)
        ).withDebugLine(ctx.start.line).also {
            if (branches.out.isNotEmpty()) {
                outMap[it] = branches.out.map { out -> out as Any };
            }
        }
    }

    fun visitBranchResultOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<BranchResult> {
        val outcomes = list.flatMap { res ->
            val keys = if (res.outcomeTypeList() != null) {
                res.outcomeTypeList().outcomeType().map { parseBranchResult(it) }
            } else {
                parseBranchResults(res.expList()) ?: throw ThisShouldNotHappen()
            }
            keys.map { key ->
                Outcome(key, visitThoughtBranch(res.thoughtBranch()).start)
                    .also { metaAliasForBranch(res, it) }
            }
        }.toMutableList()
        checkNoDuplicateOutcomeBranches(outcomes)
        return Outcomes(outcomes)
    }

    fun visitExprOutcomes(list: List<LoqiGrammarParser.BranchContext>): Outcomes<Any> {
        val outcomes = list.flatMap { res ->
            val keys = if (res.outcomeTypeList() != null) {
                res.outcomeTypeList().outcomeType().map { outcomeType ->
                    visitAndObtainBool(null, outcomeType)
                        ?: throw LoqiDomainBuildException(
                            outcomeType.start.line,
                            "Expression branch outcome `${outcomeType.text}` cannot be converted to boolean"
                        )
                }
            } else {
                buildExpList(res.expList())
            }
            keys.map { key ->
                Outcome(key, visitThoughtBranch(res.thoughtBranch()).start)
                    .also { metaAliasForBranch(res, it)}
            }
        }.toMutableList()
        checkNoDuplicateOutcomeBranches(outcomes)
        return Outcomes(outcomes)
    }

    /*
     * Нужно для корректного определения outcomeType у exp в случае:
     * в expression-режиме `true/false` должны иметь Boolean.
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

    /*
     * Достает ключи для BranchResult-блоков.
     * Возвращает null, если expList нельзя трактовать как BranchResult.
     */
    private fun getBranchResultKeys(ctx: LoqiGrammarParser.BranchContext): List<BranchResult>? {
        if (ctx.outcomeTypeList() != null) {
            return ctx.outcomeTypeList().outcomeType().map { parseBranchResult(it) }
        }
        return parseBranchResults(ctx.expList())
    }

    /*
     * Достает ключи для expression-блоков.
     * outcomeTypeList здесь допустим только как true/false -> Boolean.
     */
    private fun getExpressionKeys(ctx: LoqiGrammarParser.BranchContext): List<Any> {
        if (ctx.outcomeTypeList() != null) {
            return ctx.outcomeTypeList().outcomeType().map { outcomeType ->
                visitAndObtainBool(null, outcomeType)
                    ?: throw LoqiDomainBuildException(
                        outcomeType.start.line,
                        "Expression branch outcome `${outcomeType.text}` cannot be converted to boolean"
                    )
            }
        }
        return buildExpList(ctx.expList())
    }

    /*
     * Собирает блоки веток для QuestionNode и FindActionNode.
     * Несколько ключей могут вести в копии одной ветки или быть помечены как out.
     */
    fun visitExpressionBranches(ctx: LoqiGrammarParser.ExpBranchesContext, allowOnlyResultOut: Boolean = false): BranchInfo<*> {
        if (ctx.branches() == null) {
            val exp = visitExp(ctx.exp()).unwrap();
            if (exp !is Boolean) {
                throw LoqiDomainBuildException("Out with else is supported with boolean results")
            }
            val opposite = !exp;

            return BranchInfo(listOf(exp), listOf(), Outcomes(mutableListOf(
                Outcome(opposite, visitThoughtBranch(ctx.thoughtBranch()).start),
                Outcome(exp, DummyNode()).also {metaAliasForOut(ctx.out(), it)},
            )))
        }

        val outcomes = visitExprOutcomes(ctx.branches().branch().filter { b ->
            isExpressionOutcomeBranch(b)
        })

        val thoughtBranches = visitAbstractBranches(ctx.branches().branch().filter { b ->
            !isExpressionOutcomeBranch(b) &&
                b.expList() != null &&
                b.expList().exp().size == 1 &&
                visitExp(b.expList().exp()[0]) is DecisionTreeVarLiteral &&
                b.thoughtBranch() != null
        })

        val outBranch = ctx.branches().branch().filter { branchContext ->
            branchContext.thoughtBranch() == null
        };

        if (
            allowOnlyResultOut &&
            outBranch.isNotEmpty() &&
            outBranch.any { branch -> branch.outcomeTypeList() == null && parseBranchResults(branch.expList()) == null }
        ) {
            /*
             * findAction умеет продолжаться только по результатным boolean-веткам,
             * поэтому произвольные expression keys здесь запрещены.
             */
            throw LoqiDomainBuildException("You can redirect only one resulting (boolean or branch result) outcome branch");
        }

        val outcomeOut = outBranch.flatMap { branch -> getExpressionKeys(branch) }

        /*
         * `out` не может дублировать уже построенную явную ветку:
         * иначе один ключ получил бы два разных перехода.
         */
        val duplicateOut = outcomeOut.firstOrNull { out -> outcomes.any { value -> value.key == out } }
        if (duplicateOut != null) {
            throw LoqiDomainBuildException("Duplicate outcome branch for `$duplicateOut`")
        }

        outcomeOut.forEach { out ->
            outcomes.add(Outcome(out, DummyNode()).also{metaAliasForOut(ctx.out(), it)});
        }
        
        checkNoDuplicateOutcomeBranches(outcomes)
        outcomes.forEach {checkResultReachability(it)}
        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    /*
     * Собирает блоки веток для aggregation nodes.
     * Ключи здесь являются BranchResult, а defaultOut поддерживает компактные
     * формы вроде `{ body } out true`.
     */
    fun visitAggregationBranches(ctx: LoqiGrammarParser.AggBranchesContext, defaultOut: BranchResult? = null):
            BranchInfo<BranchResult> {

        if (ctx.branches() == null) {
            val outcomeType = parseBranchResult(ctx.outcomeType().text)

            return BranchInfo(listOf(outcomeType), listOf(visitThoughtBranch(ctx.thoughtBranch())),
                Outcomes(listOf(Outcome(outcomeType, DummyNode()).also{metaAliasForOut(ctx.out(), it)}))
            )
        }

        val outcomes = visitBranchResultOutcomes(ctx.branches().branch().filter { b ->
            isBranchResultOutcomeBranch(b)
        })

        val thoughtBranches = visitAbstractBranches(ctx.branches().branch().filter { b ->
            b.thoughtBranch() != null && !isBranchResultOutcomeBranch(b)
        })

        val outBranch = ctx.branches().branch().filter { branchContext ->
            branchContext.thoughtBranch() == null
        };

        if (
            !outBranch.isEmpty() &&
            outBranch.any { branch -> getBranchResultKeys(branch) == null }) {
            throw LoqiDomainBuildException("You can redirect only one outcome branch, not thought branch");
        }

        var outcomeOut = if (outBranch.isEmpty()) {
            defaultOut?.let { listOf(it) } ?: emptyList()
        } else {
            outBranch.flatMap { branch -> getBranchResultKeys(branch) ?: throw ThisShouldNotHappen() }
        }

        if (defaultOut != null && outcomeOut == listOf(defaultOut) && outcomes.any { value -> value.key == defaultOut }) {
            outcomeOut = emptyList()
        }

        val duplicateOut = outcomeOut.firstOrNull { out -> outcomes.any { value -> value.key == out } }
        if (duplicateOut != null) {
            throw LoqiDomainBuildException("Duplicate outcome branch for `$duplicateOut`")
        }

        outcomeOut.forEach { out ->
            outcomes.add(Outcome(out, DummyNode()).also{
                if (!outBranch.isEmpty()) metaAliasForBranch(outBranch[0], it)
            });
        }

        checkNoDuplicateOutcomeBranches(outcomes)
        outcomes.forEach {checkResultReachability(it)}
        return BranchInfo(outcomeOut, thoughtBranches, outcomes)
    }

    fun visitAbstractBranches(list: List<LoqiGrammarParser.BranchContext>): List<ThoughtBranch> {
        return list.map { branch ->
            val expr = branch.expList()
                ?.takeIf { it.exp().size == 1 }
                ?.let { visitExp(it.exp()[0]) }

            // Идентификатор здесь только синтаксический маркер ветки, не meta-id alias.
            if (expr is DecisionTreeVarLiteral) {
                val result = visitThoughtBranch(branch.thoughtBranch())
                metaAliasForBranch(branch, result)
                return@map result
            } else {
                throw LoqiDomainBuildException("Thought branches must have any identifier (for example, `_`) as expression")
            }
        }
    }

    private fun registerAlias(name: String, owner: DecisionTreeElement) {
        if (name == "_") return
        if (name !in aliases) {
            aliases[name] = HashSet()
        }
        aliases[name]?.add(owner)
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

        return BranchAggregationNode(agg, branches.bodyBranches, branches.outcomes).withDebugLine(ctx.start.line).also {
            if (branches.out.isNotEmpty()) {
                outMap[it] = branches.out.map { out -> out as Any };
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
            branches.bodyBranches[0], branches.outcomes).withDebugLine(ctx.start.line).also {
                if (branches.out.isNotEmpty()) {
                    outMap[it] = branches.out.map { out -> out as Any };
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
        if (questions.size < 2) {
            throw LoqiDomainBuildException("TupleQuestionNode must contain at least two question parts")
        }
        branches.forEach { checkResultReachability(it) }
        return TupleQuestionNode(questions, branches).withDebugLine(ctx.start.line)
    }

    fun parseTuple(ctx: LoqiGrammarParser.TupleContext): ValueTuple {
        return ValueTuple(ctx.exp().map {visitExp(it).unwrap()})
    }

    override fun visitQuestion(ctx: LoqiGrammarParser.QuestionContext): QuestionNode {
        val expr = visitExp(ctx.exp(0));
        val branches = visitExpressionBranches(ctx.expBranches())

        if (!branches.bodyBranches.isEmpty()) {
            throw LoqiDomainBuildException("Question cannot have thought branches")
        }

        val trivExpr = if (ctx.exp(1) != null) visitExp(ctx.exp(1)) else null;
        var isSwitch = !ctx.getTokens(SWITCH).isEmpty();
        return QuestionNode(expr, branches.outcomes as Outcomes<Any>, isSwitch,trivExpr).withDebugLine(ctx.start.line).also {
            if (branches.out.isNotEmpty()) outMap[it] = branches.out.map { out -> out as Any };
        }
    }

    override fun visitFindAction(ctx: LoqiGrammarParser.FindActionContext): FindActionNode {
        val variable = visitAndGetTypedVar(ctx.typedVar());
        val expr = visitExp(ctx.exp());

        val decls = ctx.treeVarDecls()?.treeVarDecl()?.map { visitTreeVarAssignment(it) } ?: emptyList()

        val branches = if (ctx.expBranches() == null) {
            BranchInfo(listOf(true), listOf(), Outcomes(mutableListOf(
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
            listOf(), decls, boolOutcomes).withDebugLine(ctx.start.line).also {
                if (branches.out.isNotEmpty()) {
                    outMap[it] = branches.out.map { out -> out as Any }
                } else if (!boolOutcomes.containsKey(true)) {
                    outMap[it] = listOf(true)
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
        val id = meta.id().getName()
        if (id in aliases) {
            for (node in aliases[id]!!) {
                node.fillMetadata(meta.metadataSection())
            }
        } else if (meta.metadataSection().metadataPropertyDecl().any { it.id().last().getName() == "condition" }) {
            // FindErrorCategory metadata (meta for + condition) — not reconstructed into AST yet
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

    private fun visitTreeVarAssignment(decl: LoqiGrammarParser.TreeVarDeclContext): DecisionTreeVarAssignment {
        if (decl.exp() == null) {
            throw LoqiDomainBuildException("Variable assignments must have value")
        }
        return DecisionTreeVarAssignment(
            TypedVariable(decl.type().text, resolveDecisionTreeVarName(decl.id().getName())),
            visitExp(decl.exp()),
        )
    }

    private fun MetaOwner.fillMetadata(ctx: MetadataSectionContext?) {
        metadata.fill(ctx)
    }

    private fun DecisionTreeNode.addDebugLine(line: Int) {
        if (debugMeta) {
            metadata.add("line", line)
        }
    }

    private fun <T : DecisionTreeNode> T.withDebugLine(line: Int): T {
        addDebugLine(line)
        return this
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

    /*
     * Встраивание значит "построить тело фрагмента здесь", а не переиспользовать
     * готовое поддерево. Так metadata, aliases и последующие перенаправления
     * остаются локальными для места вызова.
     */
    private fun inlineFragment(fragment: RegisteredFragment, callCtx: CallStmtContext): BuiltStatement {
        val fragmentName = fragment.definition.qualifiedName
        if (fragmentName in fragmentCallStack) {
            val cycle = (fragmentCallStack + fragmentName).joinToString(" -> ")
            throw LoqiDomainBuildException(callCtx.start.line, "Recursive fragment expansion detected: $cycle")
        }

        val redirects = buildFragmentCallRedirects(callCtx.callRedirBranches())
        val actualArguments = buildCallArgs(callCtx.callArgs())
        validateFragmentArguments(fragment, actualArguments, callCtx)
        val variableMapping = fragment.definition.arguments.zip(actualArguments).associate { (formal, actual) ->
            formal.name to (actual as DecisionTreeVarLiteral).name
        }

        fragmentCallStack.addLast(fragmentName)
        fragmentInliningStack.addLast(FragmentInliningContext(fragmentName, variableMapping))
        fragmentResultReplacementStack.addLast(redirects.replacements)
        try {
            val branch = buildThoughtBranch(fragment.body, leaveTailOpen = true)
            val statement = BuiltStatement(branch.branch.start, branch.tail)
            statement.openFragmentExits = collectOpenFragmentExits(statement, redirects.outResults, callCtx.start.line)
            return statement
        } finally {
            fragmentResultReplacementStack.removeLast()
            fragmentInliningStack.removeLast()
            fragmentCallStack.removeLast()
        }
    }

    /*
     * Разбирает redirect после вызова фрагмента:
     * `out true, false` оставляет подходящие conclude открытыми;
     * `{ true -> { ... }; false -> out; }` заменяет или открывает каждый result.
     */
    private fun buildFragmentCallRedirects(ctx: CallRedirBranchesContext?): FragmentCallRedirects {
        if (ctx == null) {
            return FragmentCallRedirects(emptyMap(), emptySet())
        }

        if (ctx.outcomeTypeList() != null) {
            return FragmentCallRedirects(
                emptyMap(),
                ctx.outcomeTypeList().outcomeType().map { parseBranchResult(it) }.toSet()
            )
        }

        val replacements = mutableMapOf<BranchResult, DecisionTreeNode>()
        val outResults = mutableSetOf<BranchResult>()
        ctx.branches().branch().forEach { branch ->
            if (branch.expList() != null) {
                throw LoqiDomainBuildException(branch.start.line, "Fragment result redirection must use branch result outcomes")
            }
            val results = if (branch.outcomeTypeList() != null) {
                branch.outcomeTypeList().outcomeType().map { parseBranchResult(it) }
            } else {
                throw ThisShouldNotHappen()
            }

            results.forEach { result ->
                if (result in replacements || result in outResults) {
                    throw LoqiDomainBuildException(branch.start.line, "Duplicate fragment result redirection for `$result`")
                }
                val replacement = branch.thoughtBranch()?.let { visitThoughtBranch(it).start }
                if (replacement == null) {
                    outResults.add(result)
                } else {
                    replacements[result] = replacement
                }
            }
        }
        return FragmentCallRedirects(replacements, outResults)
    }

    /*
     * Ищет терминальные BranchResultNode, которые нужно удалить из-за `-> out`.
     * Храним lambdas замены, потому что узел может быть корнем встроенного
     * statement или child-узлом outcome; эти случаи пересоединяются по-разному.
     */
    private fun collectOpenFragmentExits(
        statement: BuiltStatement,
        outResults: Set<BranchResult>,
        line: Int,
    ): List<OpenFragmentExit> {
        if (outResults.isEmpty()) {
            return emptyList()
        }

        val exits = mutableListOf<OpenFragmentExit>()
        val seen = HashSet<DecisionTreeNode>()
        lateinit var visitNode: (DecisionTreeNode, ((BranchResultNode) -> Unit)?) -> Unit

        fun addRootExit(node: BranchResultNode) {
            /*
             * Если сам start фрагмента является conclude, меняем start
             * BuiltStatement. ThoughtBranch.start остается неизменяемым.
             */
            exits.add(OpenFragmentExit(node.value) { replacement ->
                replaceAlias(node, replacement)
                statement.start = replacement
            })
        }

        fun replaceOutcome(parent: LinkNode<*>, outcome: Outcome<*>, replacement: DecisionTreeNode) {
            /*
             * Outcome immutable по target, поэтому создаем новый Outcome
             * с прежним ключом и новым узлом.
             */
            val typedParent = parent as LinkNode<Any>
            val typedOutcome = outcome as Outcome<Any>
            val newOutcome = Outcome(typedOutcome.key, replacement)
            typedParent.outcomes.remove(typedOutcome)
            typedParent.outcomes.add(newOutcome)
            replaceAlias(typedOutcome, newOutcome)
            replaceAlias(typedOutcome.node, replacement)
        }

        fun addOutcomeExit(parent: LinkNode<*>, outcome: Outcome<*>, node: BranchResultNode) {
            exits.add(OpenFragmentExit(node.value) { replacement ->
                replaceOutcome(parent, outcome, replacement)
            })
        }

        fun visitLinkNodeOutcomes(node: LinkNode<*>) {
            node.outcomes.toList().forEach { outcome ->
                val child = outcome.node
                if (child is BranchResultNode && child.value in outResults) {
                    addOutcomeExit(node, outcome, child)
                } else {
                    visitNode(child, null)
                }
            }
        }

        fun visitNestedThoughtBranch(branch: ThoughtBranch) {
            val start = branch.start
            if (start is BranchResultNode && start.value in outResults) {
                /*
                 * У вложенной ThoughtBranch нельзя заменить start без изменения модели,
                 * поэтому такой `-> out` явно запрещаем.
                 */
                throw LoqiDomainBuildException(
                    line,
                    "Cannot redirect `${start.value}` to out when it is the start of a nested thought branch"
                )
            }
            visitNode(start, null)
        }

        visitNode = fun(node: DecisionTreeNode, rootReplace: ((BranchResultNode) -> Unit)?) {
            if (!seen.add(node)) {
                return
            }

            if (node is BranchResultNode) {
                if (node.value in outResults) {
                    rootReplace?.invoke(node)
                        ?: throw LoqiDomainBuildException(
                            line,
                            "Cannot redirect `${node.value}` to out when it is the start of a nested thought branch"
                        )
                }
                return
            }

            when (node) {
                is CycleAggregationNode -> visitNestedThoughtBranch(node.thoughtBranch)
                is BranchAggregationNode -> node.thoughtBranches.forEach { visitNestedThoughtBranch(it) }
                is WhileCycleNode -> visitNestedThoughtBranch(node.thoughtBranch)
                else -> {}
            }

            if (node is LinkNode<*>) {
                visitLinkNodeOutcomes(node)
            }
        }

        visitNode(statement.start, ::addRootExit)
        if (exits.isEmpty()) {
            throw LoqiDomainBuildException(line, "Fragment call has out redirection, but no matching conclude was found")
        }
        return exits
    }

    /*
     * Аргументы фрагмента - это переменные дерева, захваченные из места вызова.
     * Поэтому actual argument должен быть variable literal, а не произвольным expression.
     */
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

    /*
     * Имена переменных внутри фрагмента ищутся сначала в самом внутреннем
     * активном вызове фрагмента, потом во внешних, потом остаются как есть.
     */
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

    private fun replaceAlias(oldElement: DecisionTreeElement, newElement: DecisionTreeElement) {
        aliases.values.forEach { elements ->
            if (oldElement in elements) {
                elements.remove(oldElement)
                elements.add(newElement)
            }
        }
    }

}
