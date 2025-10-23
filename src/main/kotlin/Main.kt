import its.model.DomainSolvingModel
import its.model.nodes.xml.DecisionTreeXMLWriter
import java.io.FileWriter
import kotlin.io.path.Path

fun main(){
    val dir = "D:\\MEGA\\IT\\Projects\\Git\\Indev\\render-meaning-tree\\domain"
    val model = DomainSolvingModel(
        dir,
        buildMethod = DomainSolvingModel.BuildMethod.LOQI
    ).validate()
    FileWriter(Path(dir, "test.xml").toString()).use {
        DecisionTreeXMLWriter.writeDecisionTreeToXml(model.decisionTree, it)
    }
}