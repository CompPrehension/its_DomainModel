package its.model.expressions.literals

import its.model.hashOf

/**
 * Ссылочный литерал
 */
abstract class ReferenceLiteral(
    val name: String,
) : Literal() {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReferenceLiteral

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return hashOf(javaClass, name)
    }
}