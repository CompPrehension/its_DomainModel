import its.model.definition.loqi.TreeLoqiBuilder
import its.model.definition.loqi.TreeLoqiWriter
import its.model.nodes.xml.DecisionTreeXMLBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dev entrypoint for XML↔LOQI round-trip checks.
 * Production CLI: CLIKt (see CLI.kt) — validate-dsm, tree-loqi-to-xml, etc.
 */
fun main(args: Array<String>) {
    when {
        args.size >= 2 && args[0] == "-parse" -> {
            TreeLoqiBuilder.buildTree(File(args[1]).toURI().toURL())
            println("Parse OK: ${args[1]}")
            return
        }
        args.size >= 2 -> {
            val input = Path.of(args[0])
            val output = Path.of(args[1])
            val treeName = args.getOrNull(2) ?: "ExprEval"

            val decisionTree = DecisionTreeXMLBuilder.fromXMLFile(input.toUri().toString())
            val loqi = TreeLoqiWriter.getWrittenTree(decisionTree, treeName)

            Files.createDirectories(output.parent)
            Files.writeString(output, loqi)

            println("Wrote ${output.toAbsolutePath()} (${loqi.lines().size} lines)")
            try {
                TreeLoqiBuilder.buildTree(File(output.toString()).toURI().toURL())
                println("Round-trip parse: OK")
            } catch (e: Exception) {
                System.err.println("Round-trip parse failed: ${e.message}")
                e.printStackTrace()
            }
            return
        }
    }
    System.err.println(
        "Usage: MainKt -parse <file.loqi> | <input.xml> <output.loqi> [treeName]\n" +
            "For validate-dsm and tree-loqi-to-xml use CLIKt (domain-cli)."
    )
}
