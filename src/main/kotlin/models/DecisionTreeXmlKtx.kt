package models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlElement

// Корневой элемент
@Serializable
@XmlSerialName("DecisionTree")
data class DecisionTreeXmlKtx(
    @XmlElement(true) val InputVariables: InputVariablesXmlKtx,
    @XmlElement(true) val ThoughtBranch: ThoughtBranchXmlKtx
)

// InputVariables
@Serializable
@XmlSerialName("InputVariables")
data class InputVariablesXmlKtx(
    @XmlElement(true) val DecisionTreeVarDecl: List<SimpleVariableXmlKtx> = emptyList(),
    @XmlElement(true) val AdditionalVarDecl: List<AdditionalVariableXmlKtx> = emptyList()
)

// Простая переменная
@Serializable
@XmlSerialName("DecisionTreeVarDecl")
data class SimpleVariableXmlKtx(
    @XmlSerialName("name", "", "")
    val name: String,

    @XmlSerialName("type", "", "")
    val type: String
)

// Переменная с выражением
@Serializable
@XmlSerialName("AdditionalVarDecl")
data class AdditionalVariableXmlKtx(
    @XmlSerialName("name", "", "")
    val name: String,

    @XmlSerialName("type", "", "")
    val type: String,

    @XmlElement(true)
    val Expression: ExpressionXmlKtx
)

// Переменная дерева решений
@Serializable
@XmlSerialName("DecisionTreeVar")
data class DecisionTreeVarXmlKtx(
    @XmlSerialName("name", "", "")
    val name: String
)

// Выражение (может содержать вложенные элементы)
@Serializable
@XmlSerialName("Expression")
data class ExpressionXmlKtx(
    @XmlElement(true) val Variable: VariableXmlKtx? = null,
    @XmlElement(true) val DecisionTreeVar: DecisionTreeVarXmlKtx? = null
)

// Переменная в выражении
@Serializable
@XmlSerialName("Variable")
data class VariableXmlKtx(
    @XmlSerialName("name", "", "")
    val name: String
)

// ThoughtBranch
@Serializable
@XmlSerialName("ThoughtBranch")
data class ThoughtBranchXmlKtx(
    @XmlSerialName("_alias", "", "")
    val _alias: String? = null,

    @XmlElement(true)
    val QuestionNode: QuestionNodeXmlKtx? = null
)

// QuestionNode
@Serializable
@XmlSerialName("QuestionNode")
data class QuestionNodeXmlKtx(
    @XmlSerialName("_alias", "", "")
    val _alias: String? = null,

    @XmlElement(true)
    val Expression: ExpressionXmlKtx,

    @XmlElement(true)
    val Outcome: List<OutcomeXmlKtx>
)

// outcome
@Serializable
@XmlSerialName("Outcome")
data class OutcomeXmlKtx(
    @XmlSerialName("value", "", "")
    val value: String,

    @XmlElement(true)
    val BranchResultNode: BranchResultNodeXmlKtx
)

// узел с результатом
@Serializable
@XmlSerialName("BranchResultNode")
data class BranchResultNodeXmlKtx(
    @XmlSerialName("value", "", "")
    val value: String,

    @XmlSerialName("_skill", "", "")
    val _skill: String? = null
)