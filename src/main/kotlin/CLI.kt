import its.model.DomainSolvingModel
import its.model.definition.DomainModel
import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.DomainLoqiWriter
import its.model.definition.loqi.TreeLoqiBuilder
import its.model.nodes.DecisionTree
import its.model.nodes.xml.DecisionTreeXMLWriter
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.bufferedWriter
import kotlin.io.path.reader

@Command(
    name = "domain-cli",
    mixinStandardHelpOptions = true,
    version = ["its_DomainModel CLI"],
    description = ["CLI для валидации доменной модели и работы с LOQI/XML"],
    subcommands = [
        ValidateDomainSolvingModelCommand::class,
        TreeLoqiToXmlCommand::class,
        ValidateDomainLoqiCommand::class,
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

    override fun call(): Int {
        DomainSolvingModel(modelDir.toString(), buildMethod).validate()
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

fun main(args: Array<String>) {
    val commandLine = CommandLine(CLI())
    commandLine.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { ex, _, parseResult ->
        val commandName = parseResult.commandSpec().qualifiedName()
        System.err.println("$commandName failed: ${ex.message ?: ex.javaClass.simpleName}")
        1
    }
    commandLine.parameterExceptionHandler = CommandLine.IParameterExceptionHandler { ex, _ ->
        System.err.println(ex.message)
        ex.commandLine.usage(System.err)
        2
    }

    val exitCode = commandLine.execute(*args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
