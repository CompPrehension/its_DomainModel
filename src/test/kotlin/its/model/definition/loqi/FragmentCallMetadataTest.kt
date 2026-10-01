package its.model.definition.loqi

import its.model.nodes.BranchAggregationNode
import its.model.nodes.BranchResult
import its.model.nodes.BranchResultNode
import its.model.nodes.DecisionTreeElement
import its.model.nodes.DecisionTreeNode
import its.model.nodes.QuestionNode
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FragmentCallMetadataTest {

    private fun tree(loqi: String) = TreeLoqiBuilder.buildTree(StringReader(loqi))

    private fun DecisionTreeElement.conclusions(): List<BranchResultNode> =
        if (this is BranchResultNode) listOf(this) else linkedElements.flatMap { it.conclusions() }

    private fun DecisionTreeNode.conclusion(result: BranchResult) = conclusions().single { it.value == result }

    private val operandTypeHypothesis = """
        fragment operandTypeHyp(A: item) {
            ask (A.sold) out false else {
                conclude: error [ hypothesis = "operand_type" ; RU.reason = "Тип берётся у операнда." ; ];
            };
            conclude: null
        }
    """

    /** Один фрагмент гипотезы под разными развилками получает навык и объяснение от места вызова. */
    @Test
    fun callMetadataFillsFragmentConclusions() {
        // Act.
        val aggregation = tree("""
            tpg T(X: item) {
                agg and {
                    _ -> { fragment:operandTypeHyp(X) [ skill = "division" ; RU.explanation = "деление" ; ] };
                    _ -> { fragment:operandTypeHyp(X) [ skill = "addition" ; RU.explanation = "сложение" ; ] };
                    correct -> { conclude: correct };
                }
            }
        """ + operandTypeHypothesis).mainBranch.start as BranchAggregationNode
        val (division, addition) = aggregation.thoughtBranches.map { it.start.conclusion(BranchResult.ERROR) }

        // Assert.
        assertEquals(listOf("division", "деление"), listOf(division.metadata["skill"], division.metadata["RU", "explanation"]))
        assertEquals(listOf("addition", "сложение"), listOf(addition.metadata["skill"], addition.metadata["RU", "explanation"]))
        assertEquals("division", aggregation.thoughtBranches[0].start.conclusion(BranchResult.NULL).metadata["skill"])
    }

    /**
     * Свойство, заданное и в выводе фрагмента, и у вызова, — ошибка сборки, даже если локализации разные:
     * ни одно из значений не теряется молча.
     */
    @Test
    fun propertySetInFragmentAndAtCallIsRejected() {
        // Act & Assert.
        val exception = assertFailsWith<LoqiDomainBuildException> {
            tree("""
                tpg T(X: item) {
                    fragment:operandTypeHyp(X) [ EN.reason = "The type comes from an operand." ; ]
                }
            """ + operandTypeHypothesis)
        }
        assertContains(exception.message!!, "Metadata property `reason` is set both in fragment `operandTypeHyp` and at its call")
    }

    /** Вывод, подставленный местом вызова вместо вывода фрагмента, метаданные вызова не получает. */
    @Test
    fun redirectedConclusionKeepsItsOwnMetadata() {
        // Act.
        val error = tree("""
            tpg T(X: item) {
                fragment:operandTypeHyp(X) {
                    error -> { conclude: error [ note = "redirect" ; ] };
                } [ skill = "division" ; ]
            }
        """ + operandTypeHypothesis).mainBranch.start.conclusion(BranchResult.ERROR)

        // Assert.
        assertEquals("redirect", error.metadata["note"])
        assertNull(error.metadata["skill"])
    }

    /** Метаданные у вызова процедуры, а не фрагмента, — ошибка сборки, а не молча потерянные данные. */
    @Test
    fun callMetadataIsRejectedForProcedures() {
        // Act & Assert.
        assertFailsWith<LoqiDomainBuildException> {
            tree("""
                tpg T(X: item) {
                    debug:assert(X.sold, "msg") [ skill = "s" ; ];
                    conclude: correct
                }
            """)
        }
    }

    /**
     * Шаблоны в выводах встроенного фрагмента ссылаются на фактические переменные, а не на параметры фрагмента;
     * остальной текст шаблона, в том числе контекстная переменная `$$A`, не меняется.
     */
    @Test
    fun fragmentTemplatesUseActualVariables() {
        // Act.
        val error = tree("""
            tpg T(X: item) {
                fragment:explained(X)
            }
            fragment explained(A: item) {
                conclude: error [ RU.explanation = "Объект ${'$'}{A} продан: ${'$'}{A.sold ? 'да' : 'нет'}, коротко ${'$'}A, контекст ${'$'}${'$'}A." ; ]
            }
        """).mainBranch.start.conclusion(BranchResult.ERROR)

        // Assert.
        assertEquals(
            "Объект ${'$'}{X} продан: ${'$'}{X.sold ? 'да' : 'нет'}, коротко ${'$'}X, контекст ${'$'}${'$'}A.",
            error.metadata["RU", "explanation"]
        )
    }

    /** Шаблон фрагмента, который не разбирается как выражение LOQI, — ошибка сборки, а не сбой при показе текста. */
    @Test
    fun invalidFragmentTemplateIsRejected() {
        // Act & Assert.
        assertFailsWith<LoqiDomainBuildException> {
            tree("""
                tpg T(X: item) {
                    fragment:explained(X)
                }
                fragment explained(A: item) {
                    conclude: error [ RU.explanation = "Объект ${'$'}{A.}" ; ]
                }
            """)
        }
    }

    /** Шаблон без параметров фрагмента остаётся ровно таким, как написан. */
    @Test
    fun templateWithoutFragmentParametersIsKeptVerbatim() {
        // Act.
        val error = tree("""
            tpg T(X: item) {
                fragment:explained(X)
            }
            fragment explained(A: item) {
                conclude: error [ RU.explanation = "Итог ${'$'}{${'$'}branchResult == BranchResult:ERROR ? 'не' : ''}верен." ; ]
            }
        """).mainBranch.start.conclusion(BranchResult.ERROR)

        // Assert.
        assertEquals("Итог ${'$'}{${'$'}branchResult == BranchResult:ERROR ? 'не' : ''}верен.", error.metadata["RU", "explanation"])
    }

    /** Шаблон в метаданных вызова написан в области места вызова и тоже переименовывается при вложенном встраивании. */
    @Test
    fun callMetadataTemplatesUseCallerScope() {
        // Act.
        val correct = tree("""
            tpg T(X: item) {
                fragment:outer(X)
            }
            fragment outer(B: item) {
                fragment:inner(B) [ RU.explanation = "про ${'$'}{B}" ; ]
            }
            fragment inner(C: item) {
                conclude: correct
            }
        """).mainBranch.start.conclusion(BranchResult.CORRECT)

        // Assert.
        assertEquals("про ${'$'}{X}", correct.metadata["RU", "explanation"])
    }

    private val twoChecks = """
        tpg T(X: item, Y: item) {
            agg and {
                _ -> { fragment:check(X) };
                _ -> { fragment:check(Y) };
                correct -> { conclude: correct };
            }
        }
        fragment check(A: item) {
            ask (A.sold) {
                true -> { conclude: correct };
                false -> { conclude: error };
            } as soldCheck
        }
        meta for soldCheck [ RU.question = "Продан ли ${'$'}{A}?" ; ]
    """

    private fun DecisionTreeElement.checkQuestions() =
        (this as BranchAggregationNode).thoughtBranches.map { (it.start as QuestionNode).metadata["RU", "question"] }

    /** Копии одного фрагмента с разными метаданными сохраняют их после записи в LOQI и повторного разбора. */
    @Test
    fun inlinedCopiesKeepTheirMetadataAfterRoundTrip() {
        // Act.
        val text = StringWriter().also { TreeLoqiWriter.writeTree(tree(twoChecks), it, "T") }.toString()

        // Assert.
        assertEquals(listOf("Продан ли ${'$'}{X}?", "Продан ли ${'$'}{Y}?"), tree(text).mainBranch.start.checkQuestions())
    }

    /** `meta for` по узлу фрагмента переименовывает шаблон отдельно для каждого места вызова. */
    @Test
    fun metaForTemplatesFollowEachInlinedCopy() {
        // Act.
        val questions = tree(twoChecks).mainBranch.start.checkQuestions()

        // Assert.
        assertEquals(listOf("Продан ли ${'$'}{X}?", "Продан ли ${'$'}{Y}?"), questions)
    }
}
