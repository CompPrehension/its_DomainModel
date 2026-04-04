import models.*
import its.model.nodes.xml.DecisionTreeXMLBuilder
import its.model.nodes.xml.DecisionTreeXMLWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class DecisionTreeXmlKtxTest {

    @Test
    fun `Round-trip with kotlinx-serialization`() {
        val originalXml = this::class.java.getResource("/tree.xml")!!.readText().trim()

        // 1. Десериализация старым способом
        val originalTree = DecisionTreeXMLBuilder.fromXMLString(originalXml)

        // 2. Преобразование в XML-модель
        val ourModel = XmlKtxSerializer.fromDomain(originalTree)

        // 3. Сериализация обратно в XML
        val serializedXml = XmlKtxSerializer.serialize(ourModel).trim()

        // 4. Десериализация нового XML старым способом (проверка совместимости)
        val treeFromSerialized = DecisionTreeXMLBuilder.fromXMLString(serializedXml)

        // 5. Сравнение объектов
        assertEquals(
            originalTree.toString().trim(),
            treeFromSerialized.toString().trim(),
            "Дерево, преобразованное через ktx, должно совпадать с исходным"
        )

        // 6. Сравнение XML
        val oldSerialized = DecisionTreeXMLWriter.decisionTreeToXmlString(originalTree).trim()
        val newSerialized = XmlKtxSerializer.serialize(ourModel).trim()

        assertEquals(
            canon(oldSerialized),
            canon(newSerialized),
            "XML, сгенерированный старым и новым способом, должен быть эквивалентен"
        )
    }

    @Test
    fun `Parse original XML with kotlinx-serialization`() {
        val originalXml = this::class.java.getResource("/tree.xml")!!.readText().trim()

        // 1. Парсим с помощью ktx
        val parsedModel = XmlKtxSerializer.deserialize(originalXml)

        // 2. Преобразуем в дерево
        val tree = XmlKtxSerializer.toDomain(parsedModel)

        // 3. Сериализуем старым способом для сравнения
        val legacySerialized = DecisionTreeXMLWriter.decisionTreeToXmlString(tree).trim()

        assertEquals(
            canon(originalXml),
            canon(legacySerialized),
            "Дерево, полученное через ktx и снова сериализованное legacy-способом, должно совпадать с оригиналом"
        )
    }

    // Каноническая форма XML
    private fun canon(xml: String): String {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        dbf.isIgnoringComments = true
        dbf.isIgnoringElementContentWhitespace = true

        val doc = dbf.newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))

        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty("omit-xml-declaration", "yes")
        transformer.setOutputProperty("indent", "no")

        val sw = java.io.StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(sw))

        return sw.toString()
            .replace(">\\s+<".toRegex(), "><")
            .trim()
    }
}