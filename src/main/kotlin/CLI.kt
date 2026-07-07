import its.model.DirectoryScanUtils
import its.model.DomainSolvingModel
import its.model.definition.DomainModel
import its.model.definition.compat.DomainDictionariesRDFBuilder
import its.model.definition.loqi.*
import its.model.definition.rdf.DomainRDFFiller
import its.model.definition.rdf.DomainRDFWriter
import its.model.definition.rdf.RDFUtils
import its.model.nodes.DecisionTree
import its.model.nodes.DecisionTreeNode
import its.model.nodes.allNodes
import its.model.nodes.childrenSummary
import its.model.nodes.toHumanString
import its.model.nodes.toJsonMap
import its.model.nodes.toView
import its.model.nodes.xml.DecisionTreeXMLBuilder
import its.model.nodes.xml.DecisionTreeXMLWriter
import picocli.CommandLine
import picocli.CommandLine.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.reader

@Command(
    name = "domain-cli",
    mixinStandardHelpOptions = true,
    version = ["its_DomainModel CLI"],
    description = ["CLI для валидации доменной модели и работы с LOQI/XML"],
    subcommands = [
        ValidateDomainSolvingModelCommand::class,
        TreeLoqiToXmlCommand::class,
        DecompileTreeCommand::class,
        DiscoverTreeCommand::class,
        DictToLoqiCommand::class,
        ValidateDomainLoqiCommand::class,
        DomainToRdfCommand::class,
        RdfToDomainLoqiCommand::class,
    ],
)
class CLI : Runnable {
    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

@Command(
    name = "validate-dsm",
    mixinStandardHelpOptions = true,
    description = ["Проверяет DomainSolvingModel из директории"],
)
class ValidateDomainSolvingModelCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "MODEL_DIR",
        description = ["Директория с domain.loqi, tag_*.loqi и tree_*.xml/tree_*.loqi"],
    )
    lateinit var modelDir: Path

    @Option(
        names = ["--build-method"],
        description = ["Способ сборки модели: \${COMPLETION-CANDIDATES}"],
        defaultValue = "LOQI",
    )
    lateinit var buildMethod: DomainSolvingModel.BuildMethod

    @Option(
        names = ["--debug"],
        description = ["Включить отладочный режим (не проверять наличие процедур из namespace debug)"],
        defaultValue = "false",
    )
    var debugMode: Boolean = false

    override fun call(): Int {
        DomainSolvingModel(modelDir.toString(), buildMethod).validate(debug = debugMode)
        println("DomainSolvingModel is valid: ${modelDir.toAbsolutePath()}")
        return 0
    }
}

@Command(
    name = "tree-loqi-to-xml",
    mixinStandardHelpOptions = true,
    description = ["Преобразует дерево решений из LOQI в XML"],
)
class TreeLoqiToXmlCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "TREE_LOQI",
        description = ["Путь к файлу дерева решений в формате LOQI"],
    )
    lateinit var treeLoqiFile: Path

    @Option(
        names = ["-o", "--output"],
        paramLabel = "XML_FILE",
        description = ["Куда сохранить XML. Если не указано, XML печатается в stdout"],
    )
    var outputFile: Path? = null

    @Option(
        names = ["--model-dir"],
        paramLabel = "MODEL_DIR",
        description = ["Если указано, дополнительно валидирует дерево на DomainSolvingModel из этой директории"],
    )
    var modelDir: Path? = null

    @Option(
        names = ["--tag"],
        paramLabel = "TAG",
        description = ["Тег доменной модели для валидации дерева"],
    )
    var tag: String? = null

    @Option(
        names = ["--cdata-expressions"],
        description = ["Записывать выражения в XML как CDATA с LOQI-представлением"],
        defaultValue = "false",
    )
    var useCDataExpressions: Boolean = false

    override fun call(): Int {
        val decisionTree = treeLoqiFile.reader().use(TreeLoqiBuilder::buildTree)
        validateTreeIfRequested(decisionTree)

        val previousValue = DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS
        DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS = useCDataExpressions
        try {
            if (outputFile != null) {
                outputFile!!.bufferedWriter().use { writer ->
                    DecisionTreeXMLWriter.writeDecisionTreeToXml(decisionTree, writer)
                }
                println("XML saved to ${outputFile!!.toAbsolutePath()}")
            } else {
                print(DecisionTreeXMLWriter.decisionTreeToXmlString(decisionTree))
            }
        } finally {
            DecisionTreeXMLWriter.SHOULD_USE_CDATA_EXPRESSIONS = previousValue
        }

        return 0
    }

    private fun validateTreeIfRequested(decisionTree: DecisionTree) {
        if (modelDir == null) {
            require(tag == null) { "Option --tag can only be used together with --model-dir" }
            return
        }

        val model = DomainSolvingModel(modelDir!!.toString(), DomainSolvingModel.BuildMethod.LOQI)
        val domain = resolveDomain(model, tag)
        domain.validateAndThrow()
        decisionTree.validate(domain)
        println("DecisionTree is valid for ${modelDir!!.toAbsolutePath()}" + (tag?.let { " (tag=$it)" } ?: ""))
    }
}

@Command(
    name = "decompile-tree",
    mixinStandardHelpOptions = true,
    description = ["Декомпилирует дерево решений из XML в LOQI/TPG"],
)
class DecompileTreeCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "TREE_XML",
        description = ["Путь к XML-файлу дерева решений"],
    )
    lateinit var treeXmlFile: Path

    @Option(
        names = ["-o", "--output"],
        paramLabel = "TPG_FILE",
        description = ["Куда сохранить LOQI/TPG. Если не указано, результат печатается в stdout"],
    )
    var outputFile: Path? = null

    @Option(
        names = ["--tree-name"],
        paramLabel = "NAME",
        description = ["Имя дерева в TPG-заголовке"],
        defaultValue = "ExprEval",
    )
    lateinit var treeName: String

    override fun call(): Int {
        System.err.println(EXPERIMENTAL_DECOMPILE_WARNING)
        val decisionTree = DecisionTreeXMLBuilder.fromXMLFile(treeXmlFile.toUri().toString())

        if (outputFile != null) {
            outputFile!!.bufferedWriter().use { writer ->
                TreeLoqiWriter.writeTree(decisionTree, writer, treeName)
            }
            println("TPG saved to ${outputFile!!.toAbsolutePath()}")
        } else {
            System.out.writer().use { writer ->
                TreeLoqiWriter.writeTree(decisionTree, writer, treeName)
            }
        }

        return 0
    }
}

@Command(
    name = "discover-tree",
    mixinStandardHelpOptions = true,
    description = ["Ищет узлы дерева решений по метаданным (LOQI/TPG или XML)"],
)
class DiscoverTreeCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "TREE_FILE",
        description = ["Путь к дереву решений: LOQI/TPG (.loqi/.tpg) или XML (.xml)"],
    )
    lateinit var treeFile: Path

    @Option(
        names = ["-m", "--meta"],
        paramLabel = "KEY=VALUE",
        description = [
            "Критерий поиска по метаданным узла (можно указать несколько раз). " +
                "Несколько критериев объединяются через И, если не указан --union. " +
                "Если среди критериев указан line, дерево из LOQI/TPG строится с debug-метаданными, " +
                "чтобы line стал доступен для поиска",
        ],
    )
    var metaCriteria: List<String> = emptyList()

    @Option(
        names = ["--union"],
        description = ["Объединять несколько критериев поиска через ИЛИ вместо И"],
        defaultValue = "false",
    )
    var union: Boolean = false

    @Option(
        names = ["--limit"],
        paramLabel = "LIMIT",
        description = ["Максимальное число выводимых узлов. Если не указано, выводятся все найденные узлы"],
    )
    var limit: Int? = null

    @Option(
        names = ["--debug"],
        description = [
            "Включить debug-метаданные (line) при построении дерева из LOQI/TPG, " +
                "даже если line не используется как критерий поиска",
        ],
        defaultValue = "false",
    )
    var debug: Boolean = false

    @Option(
        names = ["--children"],
        description = [
            "Показать непосредственные (глубины 1) дочерние узлы каждого найденного узла: " +
                "общее число и дескрипторы именованных из них (по id/line/skill)",
        ],
        defaultValue = "false",
    )
    var showChildren: Boolean = false

    @Option(
        names = ["--format"],
        paramLabel = "FORMAT",
        description = ["Формат вывода: human или jsonl"],
        defaultValue = "human",
    )
    lateinit var outputFormat: String

    override fun call(): Int {
        require(outputFormat.equals("human", ignoreCase = true) || outputFormat.equals("jsonl", ignoreCase = true)) {
            "Unsupported output format '$outputFormat'. Expected: human or jsonl"
        }
        require(metaCriteria.isNotEmpty()) { "At least one --meta KEY=VALUE criterion is required" }
        limit?.let { require(it >= 0) { "Limit must be non-negative" } }

        val criteria = metaCriteria.map(::parseMetaCriterion)
        val debugMeta = debug || criteria.any { (key, _) -> key == "line" }

        val decisionTree = loadDecisionTreeForDiscovery(treeFile, debugMeta)
        val matches = decisionTree.allNodes()
            .filter { it.matchesMetaCriteria(criteria, union) }
            .toList()
        val shown = limit?.let { matches.take(it) } ?: matches

        if (isJsonl()) {
            printJsonLine(
                mapOf(
                    "type" to "summary",
                    "found" to matches.size,
                    "shown" to shown.size,
                )
            )
            shown.forEach { node ->
                val childrenFields = if (showChildren) mapOf("children" to node.childrenSummary().toJsonMap()) else emptyMap()
                printJsonLine(mapOf("type" to "node") + node.toView().toJsonMap() + childrenFields)
            }
        } else {
            if (matches.isEmpty()) {
                println("No nodes found matching the given criteria")
            } else {
                val suffix = if (shown.size < matches.size) ", showing ${shown.size}" else ""
                println("Found ${matches.size} node(s)$suffix")
                shown.forEachIndexed { index, node ->
                    println()
                    println("#${index + 1} ${node.toView().toHumanString()}")
                    if (showChildren) {
                        println(node.childrenSummary().toHumanString().prependIndent("  "))
                    }
                }
            }
        }

        return 0
    }

    private fun isJsonl(): Boolean = outputFormat.equals("jsonl", ignoreCase = true)
}

@Command(
    name = "dict-to-loqi",
    mixinStandardHelpOptions = true,
    description = ["Собирает домен из словарей CSV + domain.ttl и сохраняет его в LOQI-директорию"],
)
class DictToLoqiCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "MODEL_DIR",
        description = ["Директория с enums.csv, classes.csv, properties.csv, relationships.csv и domain.ttl"],
    )
    lateinit var modelDir: Path

    @Parameters(
        index = "1",
        paramLabel = "OUTPUT_DIR",
        description = ["Директория, куда будет сохранён domain.loqi и скопированы tree-файлы"],
    )
    lateinit var outputDir: Path

    @Option(
        names = ["--separate-metadata"],
        description = ["При записи LOQI вынести metadata в отдельные секции"],
        defaultValue = "false",
    )
    var separateMetadata: Boolean = false

    @Option(
        names = ["--separate-class-property-values"],
        description = ["При записи LOQI вынести значения свойств классов в отдельные секции"],
        defaultValue = "false",
    )
    var separateClassPropertyValues: Boolean = false

    override fun call(): Int {
        val domain = DomainDictionariesRDFBuilder.buildDomain(modelDir.toString())
        outputDir.createDirectories()
        val loqiWriteOptions = buildSet {
            if (separateMetadata) add(LoqiWriteOptions.SEPARATE_METADATA)
            if (separateClassPropertyValues) add(LoqiWriteOptions.SEPARATE_CLASS_PROPERTY_VALUES)
        }

        val domainLoqiPath = outputDir.resolve("domain.loqi")
        domainLoqiPath.bufferedWriter().use { writer ->
            DomainLoqiWriter.saveDomain(domain, writer, loqiWriteOptions)
        }

        val copiedTrees = copyDecisionTreeFiles(modelDir, outputDir)
        println("LOQI saved to ${domainLoqiPath.toAbsolutePath()}")
        println("Copied $copiedTrees tree file(s) to ${outputDir.toAbsolutePath()}")

        return 0
    }
}

@Command(
    name = "validate-domain-loqi",
    mixinStandardHelpOptions = true,
    description = ["Проверяет произвольный domain LOQI в контексте DomainSolvingModel и опционального тега"],
)
class ValidateDomainLoqiCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "DOMAIN_LOQI",
        description = ["Путь к произвольному domain LOQI файлу"],
    )
    lateinit var domainLoqiFile: Path

    @Parameters(
        index = "1",
        paramLabel = "MODEL_DIR",
        description = ["Директория DomainSolvingModel"],
    )
    lateinit var modelDir: Path

    @Option(
        names = ["--tag"],
        paramLabel = "TAG",
        description = ["Тег из DomainSolvingModel, который нужно учесть при объединении"],
    )
    var tag: String? = null

    @Option(
        names = ["--print-merged-loqi"],
        description = ["Печатать итоговую объединённую модель в формате LOQI"],
        defaultValue = "false",
    )
    var printMergedLoqi: Boolean = false

    override fun call(): Int {
        val model = DomainSolvingModel(modelDir.toString(), DomainSolvingModel.BuildMethod.LOQI)
        val extraDomain = domainLoqiFile.reader().use(DomainLoqiBuilder::buildDomain)

        val mergedDomain = resolveDomain(model, tag).copy().apply {
            addMerge(extraDomain)
        }
        mergedDomain.validateAndThrow()

        println(
            "Merged domain is valid for ${modelDir.toAbsolutePath()} using ${domainLoqiFile.toAbsolutePath()}" +
                (tag?.let { " (tag=$it)" } ?: "")
        )

        if (printMergedLoqi) {
            DomainLoqiWriter.saveDomain(mergedDomain, System.out.writer())
            println()
        }

        return 0
    }
}

@Command(
    name = "domain-to-rdf",
    mixinStandardHelpOptions = true,
    description = ["Собирает конкретный домен из DomainSolvingModel и записывает его в RDF TTL"],
)
class DomainToRdfCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "MODEL_DIR",
        description = ["Директория DomainSolvingModel"],
    )
    lateinit var modelDir: Path

    @Option(
        names = ["--build-method"],
        description = ["Способ сборки DomainSolvingModel: \${COMPLETION-CANDIDATES}"],
        defaultValue = "LOQI",
    )
    lateinit var buildMethod: DomainSolvingModel.BuildMethod

    @Option(
        names = ["--tag"],
        paramLabel = "TAG",
        description = ["Тег из DomainSolvingModel, который нужно учесть при объединении"],
    )
    var tag: String? = null

    @Option(
        names = ["--domain-loqi"],
        paramLabel = "DOMAIN_LOQI",
        description = ["Дополнительный domain LOQI файл, который нужно объединить с базовым доменом"],
    )
    var domainLoqiFile: Path? = null

    @Option(
        names = ["-o", "--output"],
        paramLabel = "TTL_FILE",
        description = ["Куда сохранить TTL. Если не указано, TTL печатается в stdout"],
    )
    var outputFile: Path? = null

    @Option(
        names = ["--base-prefix"],
        paramLabel = "PREFIX",
        description = ["Базовый RDF prefix для создаваемых ресурсов"],
        defaultValue = RDFUtils.POAS_PREF,
    )
    lateinit var basePrefix: String

    @Option(
        names = ["--old-nary-compat"],
        description = ["Использовать старое совместимое представление n-арных отношений"],
        defaultValue = "false",
    )
    var useOldNaryCompat: Boolean = false

    override fun call(): Int {
        val model = DomainSolvingModel(modelDir.toString(), buildMethod)
        val domain = resolveConcreteDomain(model, tag, domainLoqiFile)
        domain.validateAndThrow()

        val rdfOptions = buildSet {
            if (useOldNaryCompat) add(DomainRDFWriter.Option.NARY_RELATIONSHIPS_OLD_COMPAT)
        }

        if (outputFile != null) {
            outputFile!!.bufferedWriter().use { writer ->
                DomainRDFWriter.saveDomain(domain, writer, basePrefix, rdfOptions)
            }
            println("RDF saved to ${outputFile!!.toAbsolutePath()}")
        } else {
            System.out.writer().use { writer ->
                DomainRDFWriter.saveDomain(domain, writer, basePrefix, rdfOptions)
            }
        }

        return 0
    }
}

@Command(
    name = "rdf-to-domain-loqi",
    mixinStandardHelpOptions = true,
    description = ["Заполняет конкретный домен из DomainSolvingModel данными из RDF TTL и сохраняет в LOQI"],
)
class RdfToDomainLoqiCommand : Callable<Int> {

    @Parameters(
        index = "0",
        paramLabel = "MODEL_DIR",
        description = ["Директория DomainSolvingModel"],
    )
    lateinit var modelDir: Path

    @Parameters(
        index = "1",
        paramLabel = "RDF_TTL",
        description = ["Путь к RDF Turtle файлу"],
    )
    lateinit var rdfTtlFile: Path

    @Option(
        names = ["--build-method"],
        description = ["Способ сборки DomainSolvingModel: \${COMPLETION-CANDIDATES}"],
        defaultValue = "LOQI",
    )
    lateinit var buildMethod: DomainSolvingModel.BuildMethod

    @Option(
        names = ["--tag"],
        paramLabel = "TAG",
        description = ["Тег из DomainSolvingModel, который нужно учесть при объединении"],
    )
    var tag: String? = null

    @Option(
        names = ["--domain-loqi"],
        paramLabel = "DOMAIN_LOQI",
        description = ["Дополнительный domain LOQI файл, который нужно объединить с базовым доменом до заполнения RDF"],
    )
    var domainLoqiFile: Path? = null

    @Option(
        names = ["-o", "--output"],
        paramLabel = "DOMAIN_LOQI",
        description = ["Куда сохранить LOQI. Если не указано, LOQI печатается в stdout"],
    )
    var outputFile: Path? = null

    @Option(
        names = ["--base-prefix"],
        paramLabel = "PREFIX",
        description = ["Базовый RDF prefix. Если не указан, будет взят из TTL или значение по умолчанию"],
    )
    var basePrefix: String? = null

    @Option(
        names = ["--old-nary-compat"],
        description = ["Использовать старое совместимое представление n-арных отношений"],
        defaultValue = "false",
    )
    var useOldNaryCompat: Boolean = false

    @Option(
        names = ["--throw-invalid-meta"],
        description = ["Падать, если предполагаемые метаданные в RDF не являются literal-значениями"],
        defaultValue = "false",
    )
    var throwInvalidMeta: Boolean = false

    @Option(
        names = ["--separate-metadata"],
        description = ["При записи LOQI вынести metadata в отдельные секции"],
        defaultValue = "false",
    )
    var separateMetadata: Boolean = false

    @Option(
        names = ["--separate-class-property-values"],
        description = ["При записи LOQI вынести значения свойств классов в отдельные секции"],
        defaultValue = "false",
    )
    var separateClassPropertyValues: Boolean = false

    override fun call(): Int {
        val model = DomainSolvingModel(modelDir.toString(), buildMethod)
        val domain = resolveConcreteDomain(model, tag, domainLoqiFile)

        val rdfFillOptions = buildSet {
            if (useOldNaryCompat) add(DomainRDFFiller.Option.NARY_RELATIONSHIPS_OLD_COMPAT)
            if (throwInvalidMeta) add(DomainRDFFiller.Option.THROW_INVALID_META)
        }
        DomainRDFFiller.fillDomain(domain, rdfTtlFile.toString(), rdfFillOptions, basePrefix)

        val loqiWriteOptions = buildSet {
            if (separateMetadata) add(LoqiWriteOptions.SEPARATE_METADATA)
            if (separateClassPropertyValues) add(LoqiWriteOptions.SEPARATE_CLASS_PROPERTY_VALUES)
        }

        if (outputFile != null) {
            outputFile!!.bufferedWriter().use { writer ->
                DomainLoqiWriter.saveDomain(domain, writer, loqiWriteOptions)
            }
            println("LOQI saved to ${outputFile!!.toAbsolutePath()}")
        } else {
            System.out.writer().use { writer ->
                DomainLoqiWriter.saveDomain(domain, writer, loqiWriteOptions)
            }
        }

        return 0
    }
}

private fun resolveDomain(model: DomainSolvingModel, tag: String?): DomainModel {
    if (tag == null) {
        return model.domainModel
    }

    require(model.tagsData.containsKey(tag)) {
        val knownTags = model.tagsData.keys.sorted().ifEmpty { listOf("<none>") }.joinToString(", ")
        "Tag '$tag' not found. Known tags: $knownTags"
    }
    return model.getMergedTagDomain(tag)
}

private fun resolveConcreteDomain(model: DomainSolvingModel, tag: String?, domainLoqiFile: Path?): DomainModel {
    val domain = resolveDomain(model, tag).copy()
    if (domainLoqiFile != null) {
        val extraDomain = domainLoqiFile.reader().use(DomainLoqiBuilder::buildDomain)
        domain.addMerge(extraDomain)
    }
    return domain
}

private fun parseMetaCriterion(raw: String): Pair<String, String> {
    val separatorIndex = raw.indexOf('=')
    require(separatorIndex > 0) { "Invalid --meta value '$raw'. Expected format KEY=VALUE" }
    return raw.substring(0, separatorIndex) to raw.substring(separatorIndex + 1)
}

private fun DecisionTreeNode.matchesMetaCriteria(criteria: List<Pair<String, String>>, union: Boolean): Boolean {
    val checks = criteria.map { (key, value) ->
        metadata.entries.any { it.propertyName == key && it.value.toString() == value }
    }
    return if (union) checks.any { it } else checks.all { it }
}

private fun loadDecisionTreeForDiscovery(treeFile: Path, debugMeta: Boolean): DecisionTree =
    if (treeFile.name.endsWith(".xml", ignoreCase = true)) {
        DecisionTreeXMLBuilder.fromXMLFile(treeFile.toUri().toString())
    } else {
        treeFile.reader().use { reader -> TreeLoqiBuilder.buildTree(reader, debugMeta) }
    }

private fun jsonString(value: String): String {
    val builder = StringBuilder(value.length + 2)
    builder.append('"')
    value.forEach { char ->
        when (char) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> {
                if (char.code < 0x20) {
                    builder.append("\\u")
                    builder.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(char)
                }
            }
        }
    }
    builder.append('"')
    return builder.toString()
}

private fun toJson(value: Any?): String =
    when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
            "${jsonString(key.toString())}:${toJson(entryValue)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
        else -> jsonString(value.toString())
    }

private fun printJsonLine(value: Map<String, Any?>) {
    println(toJson(value))
}

private fun copyDecisionTreeFiles(sourceDir: Path, outputDir: Path): Int {
    val sourceUrl = sourceDir.toUri().toURL()
    val treeMatches = buildList {
        addAll(DirectoryScanUtils.findFilesMatching(sourceUrl, Regex("((?:tree|tpg)_\\S+|tree)\\.xml")))
        addAll(DirectoryScanUtils.findFilesMatching(sourceUrl, Regex("((?:tree|tpg)_\\S+|tree)\\.loqi")))
        addAll(DirectoryScanUtils.findFilesMatching(sourceUrl, Regex("(\\S+)\\.tpg")))
    }

    treeMatches.forEach { match ->
        val sourcePath = Path.of(match.url.toURI())
        Files.copy(sourcePath, outputDir.resolve(sourcePath.name), StandardCopyOption.REPLACE_EXISTING)
    }

    return treeMatches.size
}

private const val EXPERIMENTAL_DECOMPILE_WARNING =
    "WARNING: decompile-tree is experimental and does not generate production-ready thought process graphs; use it primarily for analysis."

private fun configureHumanConsoleEncoding() {
    val console = System.console() ?: return
    val charset = console.charset()
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, charset))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, charset))
}

fun main(args: Array<String>) {
    val commandLine = CommandLine(CLI())
    configureHumanConsoleEncoding()
    commandLine.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { ex, _, parseResult ->
        val commandName = parseResult.commandSpec().qualifiedName()
        System.err.println("$commandName failed:")
        ex.printStackTrace(System.err)
        1
    }
    commandLine.parameterExceptionHandler = CommandLine.IParameterExceptionHandler { ex, _ ->
        ex.printStackTrace(System.err)
        ex.commandLine.usage(System.err)
        2
    }

    val exitCode = commandLine.execute(*args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
