package its.model.definition

import its.model.Describable
import java.util.*

//Вспомогательные классы для RelationshipDef

/**
 * Тип отношения [RelationshipDef]
 */
sealed interface RelationshipKind {
    val isBase: Boolean
        get() = this is BaseRelationshipKind
}

/**
 * Независимое отношение
 */
class BaseRelationshipKind(
    val scaleType: ScaleType? = null,
    val quantifier: LinkQuantifier? = null,
    val paramsDecl: ParamsDecl = ParamsDecl(),
) : RelationshipKind {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseRelationshipKind

        if (scaleType != other.scaleType) return false
        if (quantifier != other.quantifier) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(this::class, scaleType, quantifier)
    }

    /**
     * Тип порядковой шкалы
     */
    enum class ScaleType {

        /**
         * Линейный порядок
         */
        Linear,

        /**
         * Частичный порядок
         */
        Partial;

        companion object _static {
            @JvmStatic
            fun fromString(value: String) = when (value.uppercase()) {
                "LINEAR" -> Linear
                "PARTIAL" -> Partial
                else -> null
            }
        }
    }
}

/**
 * Квантификатор отношения (какое кол-во связей допустимо)
 */
data class LinkQuantifier(
    val subjCount: LinkCount = LinkCount.AnyCount,
    val objCount: LinkCount = LinkCount.AnyCount,
) : Describable {
    val reversed: LinkQuantifier
        get() = LinkQuantifier(objCount, subjCount)

    companion object {
        @JvmStatic
        fun OneToOne() = LinkQuantifier(LinkCount.Exact(1), LinkCount.Exact(1))

        @JvmStatic
        fun OneToMany() = LinkQuantifier(LinkCount.Exact(1), LinkCount.AnyCount)

        @JvmStatic
        fun ManyToOne() = LinkQuantifier(LinkCount.AnyCount, LinkCount.Exact(1))

        @JvmStatic
        fun ManyToMany() = LinkQuantifier(LinkCount.AnyCount, LinkCount.AnyCount)
    }

    override val description = "{${subjCount.description} -> ${objCount.description}}"
    override fun toString() = description
}

sealed class LinkCount : Describable {
    abstract fun accepts(actualCount: Int): Boolean
    abstract fun acceptsAsUpperBound(actualCount: Int): Boolean

    open val isExactOne: Boolean
        get() = false

    object AnyCount : LinkCount() {
        override fun accepts(actualCount: Int) = true
        override fun acceptsAsUpperBound(actualCount: Int) = true
        override val description = "*"
    }

    object Optional : LinkCount() {
        override fun accepts(actualCount: Int) = actualCount in 0..1
        override fun acceptsAsUpperBound(actualCount: Int) = actualCount <= 1
        override val description = "?"
    }

    data class Exact(val count: Int) : LinkCount() {
        init {
            require(count >= 0) { "Link count must be non-negative" }
        }

        override fun accepts(actualCount: Int) = actualCount == count
        override fun acceptsAsUpperBound(actualCount: Int) = actualCount <= count
        override val isExactOne = count == 1
        override val description = count.toString()
    }
}


/**
 * Зависимое (вычисляемое) отношение
 */
class DependantRelationshipKind(
    val type: Type,
    val baseRelationshipRef: RelationshipRef,
) : RelationshipKind {

    /**
     * Тип зависимости вычисляемого отношения
     */
    enum class Type {
        OPPOSITE,
        TRANSITIVE,
        BETWEEN,
        CLOSER,
        FURTHER,
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DependantRelationshipKind

        if (type != other.type) return false
        if (baseRelationshipRef != other.baseRelationshipRef) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(this::class, type, baseRelationshipRef)
    }
}
