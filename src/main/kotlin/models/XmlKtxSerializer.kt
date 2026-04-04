package models

import kotlinx.serialization.*
import its.model.nodes.DecisionTree
import its.model.nodes.xml.DecisionTreeXMLBuilder
import its.model.nodes.xml.DecisionTreeXMLWriter
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.XmlDeclMode

object XmlKtxSerializer {
    private val xml = XML {
        indent = 4
        // Не включать объявление XML
        xmlDeclMode = XmlDeclMode.None
        repairNamespaces = false
    }

    // Сериализация из модели в строку
    fun serialize(model: DecisionTreeXmlKtx): String = xml.encodeToString(model)

    // Десериализация из строки в модель
    fun deserialize(xmlString: String): DecisionTreeXmlKtx = xml.decodeFromString(xmlString)

    // Преобразование из модели DecisionTree
    fun fromDomain(tree: DecisionTree): DecisionTreeXmlKtx {
        val legacyXml = DecisionTreeXMLWriter.decisionTreeToXmlString(tree)
        return deserialize(legacyXml)
    }

    // Преобразование в модель DecisionTree
    fun toDomain(xmlModel: DecisionTreeXmlKtx): DecisionTree {
        val xmlString = serialize(xmlModel)
        return DecisionTreeXMLBuilder.fromXMLString(xmlString)
    }
}