import its.model.definition.loqi.DomainLoqiBuilder
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainLoqiOperationsCliTest {
    @TempDir
    lateinit var directory: Path

    private val base = """
        enum Color { red, green } [ label = "colors"; ]
        class Thing {
            class prop rank: int = 1;
            obj prop value: int;
            rel next(Thing);
        } [ label = "base"; ]
        obj a : Thing { value = 1; next(b); }
        obj b : Thing { value = 2; }
        var start = a
    """.trimIndent()

    private val extra = """
        enum Color { blue } [ note = "extra"; ]
        class Thing { obj prop extra: string; } [ note = "added"; ]
        class Added
        obj a : Thing { extra = "added"; }
    """.trimIndent()

    private fun input(name: String, text: String) = directory.resolve(name).also { it.writeText(text) }

    private fun run(vararg args: String): Int {
        val command = CommandLine(CLI())
        command.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { _, _, _ -> 1 }
        return command.execute(*args)
    }

    private fun parse(text: String) = StringReader(text).use(DomainLoqiBuilder::buildDomain)

    @Test
    fun `merge combines overlapping definitions and subtract removes only the extra data`() {
        val left = input("left.loqi", base)
        val right = input("right.loqi", extra)
        val mergedFile = directory.resolve("merged.loqi")
        assertEquals(0, run("merge-domain-loqi", "$left", "$right", "--validate", "-o", "$mergedFile"))
        val merged = parse(mergedFile.readText())
        assertEquals(setOf("red", "green", "blue"), merged.enums.get("Color")!!.values.map { it.name }.toSet())
        assertNotNull(merged.classes.get("Added"))
        assertNotNull(merged.classes.get("Thing")!!.declaredProperties.get("extra"))
        assertEquals("added", merged.objects.get("a")!!.getPropertyValue("extra"))
        assertEquals("base", merged.classes.get("Thing")!!.metadata.get("label"))
        assertEquals("added", merged.classes.get("Thing")!!.metadata.get("note"))
        assertEquals("a", merged.variables.get("start")!!.valueObjectName)
        assertEquals(1, merged.objects.get("a")!!.relationshipLinks.size)

        val resultFile = directory.resolve("difference.loqi")
        assertEquals(0, run("subtract-domain-loqi", "$mergedFile", "$right", "--validate", "-o", "$resultFile"))
        val result = parse(resultFile.readText())
        assertEquals(setOf("red", "green"), result.enums.get("Color")!!.values.map { it.name }.toSet())
        assertEquals("colors", result.enums.get("Color")!!.metadata.get("label"))
        assertNull(result.enums.get("Color")!!.metadata.get("note"))
        assertNull(result.classes.get("Added"))
        assertNull(result.classes.get("Thing")!!.declaredProperties.get("extra"))
        assertNull(result.classes.get("Thing")!!.metadata.get("note"))
        assertEquals("base", result.classes.get("Thing")!!.metadata.get("label"))
        assertEquals(1, result.objects.get("a")!!.getPropertyValue("value"))
        assertEquals(1, result.objects.get("a")!!.relationshipLinks.size)
    }

    @Test
    fun `subtract identical models produces an empty domain`() {
        val left = input("left.loqi", base)
        val output = directory.resolve("result.loqi")
        assertEquals(0, run("subtract", "$left", "$left", "--validate", "-o", "$output"))
        val result = parse(output.readText())
        assertTrue(result.classes.isEmpty())
        assertTrue(result.enums.isEmpty())
        assertTrue(result.objects.isEmpty())
        assertTrue(result.variables.isEmpty())
    }

    @Test
    fun `partial results are allowed by default and completeness validation is optional`() {
        val left = input("left.loqi", "class Thing\nobj a : Thing")
        val right = input("right.loqi", "class Thing")
        val output = directory.resolve("result.loqi")
        assertEquals(0, run("subtract", "$left", "$right", "-o", "$output"))
        val partial = parse(output.readText())
        assertTrue(partial.classes.isEmpty())
        assertNotNull(partial.objects.get("a"))

        val rejected = directory.resolve("rejected.loqi")
        assertEquals(1, run("subtract", "$left", "$right", "--validate", "-o", "$rejected"))
        assertFalse(rejected.toFile().exists())
    }

    @Test
    fun `merge conflicts do not overwrite an output file`() {
        val left = input("left.loqi", "class Thing { obj prop value: int; }")
        val right = input("right.loqi", "class Thing { obj prop value: string; }")
        val output = input("result.loqi", "keep this content")
        assertEquals(1, run("merge", "$left", "$right", "-o", "$output"))
        assertEquals("keep this content", output.readText())
        assertEquals("class Thing { obj prop value: int; }", left.readText())
    }

    @Test
    fun `enum-only results survive serialization and stdout remains open`() {
        val left = input("left.loqi", "enum Color { red, green }")
        val right = input("right.loqi", "enum Color { green }")
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val output = PrintStream(bytes, true, Charsets.UTF_8)
        try {
            System.setOut(output)
            assertEquals(0, run("subtract", "$left", "$right"))
            val loqi = bytes.toString(Charsets.UTF_8)
            assertEquals(listOf("red"), parse(loqi).enums.get("Color")!!.values.map { it.name })
            output.print("still open")
            assertTrue(bytes.toString(Charsets.UTF_8).endsWith("still open"))
        } finally {
            System.setOut(previous)
            output.close()
        }
    }

    @Test
    fun `writer options preserve metadata and class property values`() {
        val left = input("left.loqi", base)
        val right = input("right.loqi", "")
        val output = directory.resolve("result.loqi")
        assertEquals(0, run("merge", "$left", "$right", "--separate-metadata", "--separate-class-property-values", "-o", "$output"))
        val text = output.readText()
        assertTrue(text.contains("values for class Thing"))
        assertTrue(text.contains("meta for class Thing"))
        val result = parse(text)
        assertEquals(1, result.classes.get("Thing")!!.getPropertyValue("rank"))
        assertEquals("base", result.classes.get("Thing")!!.metadata.get("label"))
    }
}
