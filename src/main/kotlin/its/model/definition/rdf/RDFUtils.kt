package its.model.definition.rdf

import org.apache.jena.rdf.model.Resource
import org.apache.jena.util.SplitIRI
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object RDFUtils {
    /**
     * POAS префикс
     */
    const val POAS_PREF = "http://www.vstu.ru/poas/code#"

    /**
     * XSD префикс
     */
    const val XSD_PREF = "http://www.w3.org/2001/XMLSchema#"

    /**
     * RDF префикс
     */
    const val RDF_PREF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /**
     * RDFS префикс
     */
    const val RDFS_PREF = "http://www.w3.org/2000/01/rdf-schema#"

    val Resource.name: String
        get() = decodeIriLocalName(SplitIRI.localname(this.uri))

    fun encodeIriLocalName(name: String): String {
        val builder = StringBuilder(name.length)
        name.forEach { ch ->
            if (ch.isIriLocalNameSafe()) {
                builder.append(ch)
            } else {
                ch.toString().toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                    builder.append('%')
                    builder.append(((byte.toInt() ushr 4) and 0xF).toString(16).uppercase())
                    builder.append((byte.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
        return builder.toString()
    }

    fun decodeIriLocalName(name: String): String {
        val builder = StringBuilder(name.length)
        val bytes = ByteArrayOutputStream()

        fun flushBytes() {
            if (bytes.size() == 0) return
            builder.append(bytes.toString(StandardCharsets.UTF_8))
            bytes.reset()
        }

        var i = 0
        while (i < name.length) {
            if (
                name[i] == '%' &&
                i + 2 < name.length &&
                name[i + 1].digitToIntOrNull(16) != null &&
                name[i + 2].digitToIntOrNull(16) != null
            ) {
                val byteValue = name.substring(i + 1, i + 3).toInt(16)
                bytes.write(byteValue)
                i += 3
            } else {
                flushBytes()
                builder.append(name[i])
                i++
            }
        }
        flushBytes()

        return builder.toString()
    }

    private fun Char.isIriLocalNameSafe(): Boolean {
        return this in 'a'..'z' ||
            this in 'A'..'Z' ||
            this in '0'..'9' ||
            this == '-' ||
            this == '.' ||
            this == '_' ||
            this == '~'
    }
}
