package its.model

/**
 * То же значение, что и у [java.util.Objects.hash] от двух аргументов, но без создания массива аргументов
 */
internal fun hashOf(a: Any?, b: Any?): Int = 31 * (31 + a.hashCode()) + b.hashCode()

/**
 * То же значение, что и у [java.util.Objects.hash] от трех аргументов, но без создания массива аргументов
 */
internal fun hashOf(a: Any?, b: Any?, c: Any?): Int = 31 * hashOf(a, b) + c.hashCode()
