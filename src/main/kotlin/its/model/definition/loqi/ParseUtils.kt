package its.model.definition.loqi

import its.model.definition.DomainDefinitionException
import java.util.concurrent.Callable

fun <T> domainOpAt(line: Int = -1, expr: Callable<T>): T {
    val res: T
    try {
        res = expr.call()
    } catch (e: DomainDefinitionException) {
        if (line < 0) throw LoqiDomainBuildException(e.message ?: "", e)
        else throw LoqiDomainBuildException(line, e.message ?: "", e)
    }
    return res
}