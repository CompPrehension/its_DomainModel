import its.model.definition.DomainModel
import its.model.definition.loqi.DomainLoqiBuilder
import its.model.definition.loqi.DomainLoqiWriter
import its.model.definition.loqi.LoqiWriteOptions
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.bufferedWriter
import kotlin.io.path.reader

abstract class DomainLoqiOperationCommand : Callable<Int> {
    @Parameters(index = "0", paramLabel = "LEFT_LOQI", description = ["Первый DomainModel в формате LOQI"])
    lateinit var leftLoqiFile: Path

    @Parameters(index = "1", paramLabel = "RIGHT_LOQI", description = ["Второй DomainModel: добавляемые или вычитаемые данные"])
    lateinit var rightLoqiFile: Path

    @Option(names = ["-o", "--output"], paramLabel = "DOMAIN_LOQI", description = ["Куда сохранить LOQI; по умолчанию stdout"])
    var outputFile: Path? = null

    @Option(names = ["--validate"], description = ["Дополнительно проверить полноту результата (по умолчанию допускаются неполные модели)"])
    var validate: Boolean = false

    @Option(names = ["--separate-metadata"], description = ["Вынести metadata в отдельные секции"])
    var separateMetadata: Boolean = false

    @Option(names = ["--separate-class-property-values"], description = ["Вынести значения свойств классов в отдельные секции"])
    var separateClassPropertyValues: Boolean = false

    protected abstract fun applyOperation(left: DomainModel, right: DomainModel)

    override fun call(): Int {
        val left = leftLoqiFile.reader().use(DomainLoqiBuilder::buildDomain)
        val right = rightLoqiFile.reader().use(DomainLoqiBuilder::buildDomain)
        applyOperation(left, right)
        left.validateAndThrowInvalid()
        if (validate) left.validateAndThrow()

        val options = buildSet {
            if (separateMetadata) add(LoqiWriteOptions.SEPARATE_METADATA)
            if (separateClassPropertyValues) add(LoqiWriteOptions.SEPARATE_CLASS_PROPERTY_VALUES)
        }
        val output = outputFile
        if (output != null) {
            output.bufferedWriter().use { DomainLoqiWriter.saveDomain(left, it, options) }
            println("LOQI saved to ${output.toAbsolutePath()}")
        } else {
            val writer = System.out.writer()
            DomainLoqiWriter.saveDomain(left, writer, options)
            writer.flush()
        }
        return 0
    }
}

@Command(
    name = "merge-domain-loqi",
    aliases = ["merge"],
    mixinStandardHelpOptions = true,
    description = ["Объединяет два DomainModel LOQI через addMerge; несовместимые определения вызывают ошибку"],
)
class MergeDomainLoqiCommand : DomainLoqiOperationCommand() {
    override fun applyOperation(left: DomainModel, right: DomainModel) = left.addMerge(right)
}

@Command(
    name = "subtract-domain-loqi",
    aliases = ["subtract"],
    mixinStandardHelpOptions = true,
    description = ["Вычитает RIGHT_LOQI из LEFT_LOQI через subtract; опустевшие совпадающие определения удаляются"],
)
class SubtractDomainLoqiCommand : DomainLoqiOperationCommand() {
    override fun applyOperation(left: DomainModel, right: DomainModel) = left.subtract(right)
}
