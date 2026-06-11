package its.model

import its.model.Utils.plus
import its.model.definition.DomainModel
import its.model.definition.compat.DomainDictionariesRDFBuilder
import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.DomainLoqiWriter
import its.model.definition.loqi.TreeLoqiBuilder
import its.model.nodes.DecisionTree
import its.model.nodes.xml.DecisionTreeXMLBuilder
import its.model.nodes.xml.DecisionTreeXMLWriter
import java.io.File
import java.net.URL

/**
 * Решение задач в предметной области
 * @param domainModel описание предметной области (домена), общее для всех ситуаций применения
 * @param tagsData части предметной области, специфичные для отдельных ситуаций применения (т.н. теги)
 * @param decisionTrees деревья решений, описывающие решение задач в предметной области
 */
class DomainSolvingModel(
    val domainModel: DomainModel,
    val tagsData: Map<String, DomainModel>,
    val decisionTrees: Map<String, DecisionTree>,
) {

    enum class BuildMethod {
        LOQI,
        DICT_RDF,
    }

    /**
     * Построить модель на основе данных, взятых из директории [directoryUrl]
     *
     * Если [buildMethod] == [BuildMethod.DICT_RDF], то:
     * - Данные модели домена [domainModel] читаются из .csv словарей и .ttl данных (подробнее см. [DomainDictionariesRDFBuilder])
     * - [tagsData] не заполняется
     *
     * Иначе (если [buildMethod] == [BuildMethod.LOQI])
     * - Данные модели домена [domainModel] читаются из файла '`domain.loqi`' (подробнее см. [DomainLoqiBuilder])
     * - Данные тегов [tagsData] аналогично читаются из файлов вида '`tag_<имя тега>.loqi`'
     *
     * Деревья решений в любом случае читаются из XML файлов вида '`tree_<имя дерева>.xml`' (подробнее см. [DecisionTreeXMLBuilder])
     */
    constructor(
        directoryURL: URL,
        buildMethod: BuildMethod = BuildMethod.LOQI,
        includeDebugMeta: Boolean = false,
    ) : this(
        collectDomain(directoryURL, buildMethod),
        collectTags(directoryURL, buildMethod),
        collectTrees(directoryURL, includeDebugMeta)
    )

    constructor(
        directoryPath: String,
        buildMethod: BuildMethod = BuildMethod.LOQI,
        includeDebugMeta: Boolean = false,
    ) : this(File(directoryPath).toURI().toURL(), buildMethod, includeDebugMeta)


    constructor(
        domainModel: DomainModel,
        decisionTreeDirectoryURL: URL,
        includeDebugMeta: Boolean = false,
    ) : this(domainModel, emptyMap(), collectTrees(decisionTreeDirectoryURL, includeDebugMeta))

    constructor(
        domainModel: DomainModel,
        decisionTreeDirectoryPath: String,
        includeDebugMeta: Boolean = false,
    ) : this(domainModel, File(decisionTreeDirectoryPath).toURI().toURL(), includeDebugMeta)


    companion object {
       private fun normalizeTreeName(fileName: String): String {
            return when (fileName) {
                "tree", "main" -> ""
                else -> fileName
                    .removePrefix("tree_")
                    .removePrefix("tpg_")
            }
        }

        /**
         * Построить домен на основе файлов в директории
         */
        @JvmStatic
        fun collectDomain(directoryURL: URL, buildMethod: BuildMethod = BuildMethod.LOQI): DomainModel {
            return when (buildMethod) {
                BuildMethod.LOQI -> DomainLoqiBuilder.buildDomain(
                    (directoryURL + "domain.loqi").openStream().bufferedReader()
                )

                BuildMethod.DICT_RDF -> DomainDictionariesRDFBuilder.buildDomain(directoryURL)
            }
        }

        @JvmStatic
        fun collectTags(directoryURL: URL, buildMethod: BuildMethod = BuildMethod.LOQI): Map<String, DomainModel> {
            if (buildMethod != BuildMethod.LOQI)
                return emptyMap()
            return DirectoryScanUtils.findFilesMatching(directoryURL, Regex("tag_(\\S+)\\.loqi"))
                .map { (fileUrl, regexMatch) ->
                    val (name) = regexMatch.destructured
                    name to DomainLoqiBuilder.buildDomain(fileUrl.openStream().bufferedReader())
                }
                .toMap()
        }

        /**
         * Построить набор деревьев решений на основе файлов в директории
         */
        @JvmStatic
        @JvmOverloads
        fun collectTrees(directoryURL: URL, includeDebugMeta: Boolean = false): Map<String, DecisionTree> {
            val xmlMap = DirectoryScanUtils.findFilesMatching(directoryURL, Regex("((?:tree|tpg)_\\S+|tree)\\.xml"))
                .map { (fileUrl, regexMatch) ->
                    val (fileName) = regexMatch.destructured
                    val name = normalizeTreeName(fileName)
                    name to DecisionTreeXMLBuilder.fromXMLFile(fileUrl.toURI().toString())
                }
                .toMap()
            val loqiMap = DirectoryScanUtils.findFilesMatching(directoryURL, Regex("((?:tree|tpg)_\\S+|tree)\\.loqi"))
                .map { (fileUrl, regexMatch) ->
                    val (fileName) = regexMatch.destructured
                    val name = normalizeTreeName(fileName)
                    name to TreeLoqiBuilder.buildTree(fileUrl, includeDebugMeta)
                }
                .toMap()
            val tpgMap = DirectoryScanUtils.findFilesMatching(directoryURL, Regex("(\\S+)\\.tpg"))
                .map { (fileUrl, regexMatch) ->
                    val (fileName) = regexMatch.destructured
                    val name = normalizeTreeName(fileName)
                    name to TreeLoqiBuilder.buildTree(fileUrl, includeDebugMeta)
                }
                .toMap()
            return xmlMap + loqiMap + tpgMap
        }

        /**
         * Записать полную информацию о модели [model] в директорию [directoryPath] так,
         * что при чтении с помощью конструктора, принимающего [directoryPath], модель сможет быть восстановлена.
         *
         * *Обратите внимание, что для записи информации о моделях домена используется [DomainLoqiWriter],
         * т.е. читать такую запись нужно будет с помощью [BuildMethod.LOQI]*
         */
        @JvmStatic
        fun writeModelToDirectory(model: DomainSolvingModel, directoryPath: String) {
            File(directoryPath).mkdir()
            DomainLoqiWriter.saveDomain(
                model.domainModel,
                File(directoryPath, "domain.loqi").bufferedWriter()
            )
            model.tagsData.forEach { (tagName, tagModel) ->
                DomainLoqiWriter.saveDomain(
                    tagModel,
                    File(directoryPath, "tag_$tagName.loqi").bufferedWriter()
                )
            }
            model.decisionTrees.forEach { (treeName, decisionTree) ->
                val treeFileName = if(treeName.isEmpty()) "" else "_$treeName"
                DecisionTreeXMLWriter.writeDecisionTreeToXml(
                    decisionTree,
                    File(directoryPath, "tree$treeFileName.xml").bufferedWriter()
                )
            }
        }

    }


    /**
     * Валидация модели с выкидыванием исключений
     * @return this
     */
    fun validate(): DomainSolvingModel {
        domainModel.validateAndThrow()
        tagsData.keys.forEach { tagName ->
            val mergedTagDomain = getMergedTagDomain(tagName)
            mergedTagDomain.validateAndThrow() //Деревья должны корректно работать со всеми теговыми моделями
            decisionTrees.values.forEach { it.validate(mergedTagDomain) }
        }
        if(tagsData.isEmpty()){
            decisionTrees.values.forEach { it.validate(domainModel) }
        }
        return this
    }

    /**
     * Дерево решений "По умолчанию" - дерево решений без имени
     */
    val decisionTree: DecisionTree
        get() {
            require(decisionTrees.containsKey("")) { "DomainModel does not have a default decisionTree. Use decisionTree(name) instead." }
            return decisionTrees[""]!!
        }

    /**
     * Получить дерево решений по имени
     */
    fun decisionTree(name: String): DecisionTree {
        require(decisionTrees.containsKey(name)) { "DomainModel does not have a decisionTree named '$name'." }
        return decisionTrees[name]!!
    }

    /**
     * Получить обобщенную модель домена,
     * созданную из [domainModel]-модели и тег-модели из [tagsData] по переданному имени [name]
     */
    fun getMergedTagDomain(name: String): DomainModel {
        require(tagsData.containsKey(name)) { "DomainSolvingModel does not have a tag Domain model named '$name'" }
        return domainModel.copy().apply { addMerge(tagsData[name]!!) }
    }

}
