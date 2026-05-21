package mp.utils

/**
 * Вспомогательный класс итератор для реализации мультимап
 */
class MutableMultimapIterator<V>(
    multimap: MutableMap<*, out MutableCollection<V>>,
    // Нужно владельцам мультимапы, чтобы сбросить кэши при удалении через Iterator.remove().
    private val onRemove: () -> Unit = {},
) : MutableIterator<V> {
    private val entryIterator = multimap.entries.iterator()
    private var valueIterator: MutableIterator<V>? = null
    private var currentValues: MutableCollection<V>? = null
    private var canRemove = false


    override fun hasNext(): Boolean {
        if (valueIterator?.hasNext() == true) return true
        while (entryIterator.hasNext()) {
            currentValues = entryIterator.next().value
            valueIterator = currentValues!!.iterator()
            if (valueIterator!!.hasNext()) return true
        }
        return false
    }

    override fun next(): V {
        if (!hasNext()) throw NoSuchElementException()
        canRemove = true
        return valueIterator!!.next()
    }

    override fun remove() {
        if (!canRemove) return
        valueIterator!!.remove()
        if (currentValues!!.isEmpty()) {
            entryIterator.remove()
        }
        onRemove()
        canRemove = false
    }
}
