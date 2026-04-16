package its.model.definition.loqi

import its.model.ObjectDefBlueprint
import its.model.ObjectPropertyValueBlueprint
import its.model.RelationshipLinkBlueprint
import its.model.TypedVariable
import its.model.definition.*
import its.model.definition.loqi.LoqiStringUtils.extractEscapes
import its.model.definition.loqi.OperatorLoqiBuilder.Companion.buildExp
import its.model.definition.procedures.BuiltinProcedureRegistry
import its.model.definition.procedures.CallableProcedureDef
import its.model.definition.procedures.ProcedureRegistry
import its.model.expressions.Operator
import its.model.expressions.literals.*
import its.model.expressions.operators.*
import its.model.expressions.utils.NamedParamsValuesExprList
import its.model.expressions.utils.OrderedParamsValuesExprList
import its.model.expressions.utils.ParamsValuesExprList
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.Reader
import java.io.StringReader

/**
 * Построение дерева выражений [Operator] на основе текстовой записи
 */
class OperatorLoqiBuilder(
    private val procedureRegistry: ProcedureRegistry = BuiltinProcedureRegistry,
    private val decisionTreeVarNameResolver: DecisionTreeVarNameResolver = DecisionTreeVarNameResolver.IDENTITY,
) : LoqiGrammarBaseVisitor<Operator>() {

    companion object {
        /**
         * Построить дерево выражений
         *
         * @param reader источник
         * @return построенное дерево выражений
         */
        @JvmStatic
        fun buildExp(reader: Reader): Operator {
            val lexer = LoqiGrammarLexer(CharStreams.fromReader(reader))
            val tokens = CommonTokenStream(lexer)
            val parser = LoqiGrammarParser(tokens)

            val errorListener = SyntaxErrorListener()
            parser.addErrorListener(errorListener)

            val tree: ParseTree = parser.fullExp().exp()
            errorListener.getSyntaxErrors().firstOrNull()?.exception?.apply { throw this }

            val builder = OperatorLoqiBuilder()
            return tree.accept(builder)
        }

        /**
         * @see buildExp
         */
        @JvmStatic
        fun buildExp(string: String): Operator {
            return buildExp(StringReader(string))
        }
    }

    private fun getParamsValues(ctx: LoqiGrammarParser.ParamsValuesExprContext?): ParamsValuesExprList {
        if (ctx == null) return ParamsValuesExprList.EMPTY
        return when (ctx) {
            is LoqiGrammarParser.NamedParamsValuesExprContext ->
                NamedParamsValuesExprList(ctx.namedParamValueExpr().associate {
                    it.ID().getName() to visit(it.exp())
                })

            is LoqiGrammarParser.OrderedParamsValuesExprContext ->
                OrderedParamsValuesExprList(ctx.exp().map { visit(it) })

            else -> throw ThisShouldNotHappen()
        }
    }

    private fun getDomainParamsValues(ctx: LoqiGrammarParser.ParamsValuesContext?): its.model.definition.ParamsValues {
        if (ctx == null) return NamedParamsValues(mapOf())
        return when (ctx) {
            is LoqiGrammarParser.NamedParamsValuesContext ->
                NamedParamsValues(ctx.namedParamValue().associate {
                    it.id().getName() to it.value().getValue()
                })

            is LoqiGrammarParser.OrderedParamsValuesContext ->
                OrderedParamsValues(ctx.value().map { it.getValue() })

            else -> throw ThisShouldNotHappen()
        }
    }

    override fun visitAssignExp(ctx: LoqiGrammarParser.AssignExpContext): Operator {
        val left = visit(ctx.exp(0))
        val right = visit(ctx.exp(1))
        if (left is DecisionTreeVarLiteral) {
            return AssignDecisionTreeVar(left.name, right)
        } else if (left is GetPropertyValue) {
            return AssignProperty(left.objectExpr, left.propertyName, left.paramsValues, right)
        }
        throw ThisShouldNotHappen()
    }

    override fun visitGetProperty(ctx: LoqiGrammarParser.GetPropertyContext): Operator {
        return GetPropertyValue(visit(ctx.exp()), ctx.ID().getName(), getParamsValues(ctx.paramsValuesExpr()))
    }

    override fun visitAndExp(ctx: LoqiGrammarParser.AndExpContext): Operator {
        return LogicalAnd(visit(ctx.exp(0)), visit(ctx.exp(1)))
    }

    override fun visitThreewayCompareExp(ctx: LoqiGrammarParser.ThreewayCompareExpContext): Operator {
        return Compare(visit(ctx.exp(0)), visit(ctx.exp(1)))
    }

    override fun visitGetClassExp(ctx: LoqiGrammarParser.GetClassExpContext): Operator {
        return GetClass(visit(ctx.exp()))
    }

    override fun visitValueLiteral(ctx: LoqiGrammarParser.ValueLiteralContext): Operator {
        return ctx.value().getValueLiteral()
    }

    override fun visitObjLiteral(ctx: LoqiGrammarParser.ObjLiteralContext): Operator {
        return ObjectLiteral(ctx.ID().getName())
    }

    override fun visitClassLiteral(ctx: LoqiGrammarParser.ClassLiteralContext): Operator {
        return ClassLiteral(ctx.ID().getName())
    }

    override fun visitCastExp(ctx: LoqiGrammarParser.CastExpContext): Operator {
        return Cast(visit(ctx.exp(0)), visit(ctx.exp(1)))
    }

    override fun visitExistQuantifierExp(ctx: LoqiGrammarParser.ExistQuantifierExpContext): Operator {
        val (selector, condition) =
            if (ctx.exp().size == 2)
                visit(ctx.exp(0)) to visit(ctx.exp(1))
            else
                null to visit(ctx.exp(0))
        return ExistenceQuantifier(
            TypedVariable(ctx.ID(0).getName(), ctx.ID(1).getName()),
            selector,
            condition
        )
    }

    override fun visitOrExp(ctx: LoqiGrammarParser.OrExpContext): Operator {
        return LogicalOr(visit(ctx.exp(0)), visit(ctx.exp(1)))
    }

    override fun visitGetRelationshipParamExp(ctx: LoqiGrammarParser.GetRelationshipParamExpContext): Operator {
        return GetRelationshipParamValue(
            visit(ctx.exp(0)),
            ctx.ID(0).getName(),
            getParamsValues(ctx.paramsValuesExpr()),
            ctx.exp().drop(1).map { visit(it) },
            ctx.ID(1).getName(),
        )
    }

    override fun visitCheckRelationshipExp(ctx: LoqiGrammarParser.CheckRelationshipExpContext): Operator {
        return CheckRelationship(
            visit(ctx.exp(0)),
            ctx.ID().getName(),
            getParamsValues(ctx.paramsValuesExpr()),
            ctx.exp().drop(1).map { visit(it) }
        )
    }

    override fun visitAddRelationshipExp(ctx: LoqiGrammarParser.AddRelationshipExpContext): Operator {
        return AddRelationshipLink(
            visit(ctx.exp(0)),
            ctx.ID().getName(),
            getParamsValues(ctx.paramsValuesExpr()),
            ctx.exp().drop(1).map { visit(it) }
        )
    }

    override fun visitRemoveRelationshipExp(ctx: LoqiGrammarParser.RemoveRelationshipExpContext): Operator {
        return RemoveRelationshipLink(
            visit(ctx.exp(0)),
            ctx.ID().getName(),
            getParamsValues(ctx.paramsValuesExpr()),
            ctx.exp().drop(1).map { visit(it) }
        )
    }

    override fun visitAddNewObjectExp(ctx: LoqiGrammarParser.AddNewObjectExpContext): Operator {
        val objectDef = ObjectDefBlueprint(ctx.id().getName())

        ctx.dynamicObjStatement().forEach { objStatement ->
            if (objStatement.dynamicPropertyValueStatement() != null) {
                val propertyStatement = objStatement.dynamicPropertyValueStatement()
                objectDef.properties.add(
                    ObjectPropertyValueBlueprint(
                        propertyStatement.id().getName(),
                        visit(propertyStatement.exp()),
                        getDomainParamsValues(propertyStatement.paramsValues()),
                    )
                )
            } else if (objStatement.dynamicRelationshipLinkStatement() != null) {
                val linkStatement = objStatement.dynamicRelationshipLinkStatement()
                objectDef.relationships.add(
                    RelationshipLinkBlueprint(
                        linkStatement.id().getName(),
                        linkStatement.exp().map { visit(it) },
                        getDomainParamsValues(linkStatement.paramsValues()),
                    )
                )
            } else {
                throw ThisShouldNotHappen()
            }
        }

        return AddNewObject(objectDef)
    }

    override fun visitNotExp(ctx: LoqiGrammarParser.NotExpContext): Operator {
        return LogicalNot(visit(ctx.exp()))
    }

    override fun visitIsExp(ctx: LoqiGrammarParser.IsExpContext): Operator {
        return CheckClass(visit(ctx.exp(0)), visit(ctx.exp(1)))
    }

    override fun visitTreeVar(ctx: LoqiGrammarParser.TreeVarContext): Operator {
        return DecisionTreeVarLiteral(decisionTreeVarNameResolver.resolve(ctx.ID().getName()))
    }

    override fun visitCompareExp(ctx: LoqiGrammarParser.CompareExpContext): Operator {
        val operator = if (ctx.GREATER() != null) {
            CompareWithComparisonOperator.ComparisonOperator.Greater
        } else if (ctx.GTE() != null) {
            CompareWithComparisonOperator.ComparisonOperator.GreaterEqual
        } else if (ctx.LESS() != null) {
            CompareWithComparisonOperator.ComparisonOperator.Less
        } else {
            CompareWithComparisonOperator.ComparisonOperator.LessEqual
        }
        return CompareWithComparisonOperator(visit(ctx.exp(0)), operator, visit(ctx.exp(1)))
    }

    override fun visitBlockExp(ctx: LoqiGrammarParser.BlockExpContext): Operator {
        return Block(ctx.exp().map { visit(it) })
    }

    override fun visitEqualityExp(ctx: LoqiGrammarParser.EqualityExpContext): Operator {
        val operator = if (ctx.EQ() != null) {
            CompareWithComparisonOperator.ComparisonOperator.Equal
        } else {
            CompareWithComparisonOperator.ComparisonOperator.NotEqual
        }
        return CompareWithComparisonOperator(visit(ctx.exp(0)), operator, visit(ctx.exp(1)))
    }

    override fun visitFindByConditionExp(ctx: LoqiGrammarParser.FindByConditionExpContext): Operator {
        return GetByCondition(TypedVariable(ctx.ID(0).getName(), ctx.ID(1).getName()), visit(ctx.exp()))
    }

    override fun visitTernaryIfExp(ctx: LoqiGrammarParser.TernaryIfExpContext): Operator {
        return IfThen(visit(ctx.exp(0)), visit(ctx.exp(1)), visit(ctx.exp(2)))
    }

    override fun visitIfExp(ctx: LoqiGrammarParser.IfExpContext): Operator {
        return IfThen(visit(ctx.exp(0)), visit(ctx.exp(1)), ctx.exp(2)?.let { visit(it) })
    }

    override fun visitForAllQuantifierExp(ctx: LoqiGrammarParser.ForAllQuantifierExpContext): Operator {
        val (selector, condition) =
            if (ctx.exp().size == 2)
                visit(ctx.exp(0)) to visit(ctx.exp(1))
            else
                null to visit(ctx.exp(0))
        return ForAllQuantifier(
            TypedVariable(ctx.ID(0).getName(), ctx.ID(1).getName()),
            selector,
            condition
        )
    }

    override fun visitFindExtremeExp(ctx: LoqiGrammarParser.FindExtremeExpContext): Operator {
        return GetExtreme(
            ctx.ID(1).getName(),
            ctx.ID(2).getName(),
            visit(ctx.exp(1)),
            ctx.ID(0).getName(),
            visit(ctx.exp(0))
        )
    }

    override fun visitVariable(ctx: LoqiGrammarParser.VariableContext): Operator {
        return VariableLiteral(ctx.ID().getName())
    }

    override fun visitParenthesisExp(ctx: LoqiGrammarParser.ParenthesisExpContext): Operator {
        return visit(ctx.exp())
    }

    override fun visitGetByRelationship(ctx: LoqiGrammarParser.GetByRelationshipContext): Operator {
        return GetByRelationship(visit(ctx.exp()), ctx.ID().getName(), getParamsValues(ctx.paramsValuesExpr()))
    }

    override fun visitCallExpr(ctx: LoqiGrammarParser.CallExprContext): Operator {
        val procedure = resolveCallProcedure(ctx.namespaceResolution())
            ?: throw DomainUseException("Procedure `${ctx.namespaceResolution().text}` not found")
        val args = ctx.callArgs()?.exp()?.map { visit(it) } ?: emptyList()
        return CallProcedure(procedure, args)
    }

    private fun resolveCallProcedure(ctx: LoqiGrammarParser.NamespaceResolutionContext): CallableProcedureDef? {
        val parts = ctx.ID().map { it.getName() }
        if (parts.isEmpty()) {
            return null
        }
        return procedureRegistry.resolve(parts.dropLast(1), parts.last())
    }

    private fun LoqiGrammarParser.IdContext.getName(): String {
        return ID().text.removeSurrounding("`")
    }

    private fun TerminalNode.getName(): String {
        return text.removeSurrounding("`")
    }

    private fun LoqiGrammarParser.ValueContext.getValue(): Any {
        return getValueLiteral().value
    }

    private fun LoqiGrammarParser.ValueContext.getValueLiteral(): ValueLiteral<*, *> {
        if (INTEGER() != null) return IntegerLiteral(INTEGER().text.toInt())
        if (DOUBLE() != null) return DoubleLiteral(DOUBLE().text.toDouble())
        if (BOOLEAN() != null) return BooleanLiteral(BOOLEAN().text.toBoolean())
        if (STRING() != null) return StringLiteral(STRING().text.extract())
        if (enumValueRef() != null) {
            val enumValue = enumValueRef().getRef()
            return EnumLiteral(enumValue)
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

    private fun LoqiGrammarParser.EnumValueRefContext.getRef() =
        EnumValueRef(id(0).ID().getName(), id(1).ID().getName())
}
