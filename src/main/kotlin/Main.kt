import its.model.DomainSolvingModel
import its.model.nodes.xml.DecisionTreeXMLWriter
import java.io.FileWriter


fun main(){
    val dir = "D:\\MEGA\\IT\\Projects\\Git\\Indev\\render-meaning-tree\\domain"
    val model = DomainSolvingModel(
        dir,
        buildMethod = DomainSolvingModel.BuildMethod.LOQI
    ).validate()
    FileWriter("D:\\MEGA\\IT\\Projects\\Git\\Indev\\render-meaning-tree\\domain\\test.xml").use { writer ->
        DecisionTreeXMLWriter.writeDecisionTreeToXml(model.decisionTree, writer)
    }
}