package mp.utils

/**
 * Создает набор всех возможных комбинаций элементов из входных коллекций;
 *
 * Пример: если на входе [[a, b], [x, y]]
 * то на выходе [[a, x], [a, y], [b, x], [b, y]]
 */
fun <T> getCombinations(possibilities: List<Collection<T>>): List<MutableList<T>> {
    if (possibilities.isEmpty()) {
        return ArrayList()
    }
    val firstPossibility: Collection<T> = possibilities.first()
    if (possibilities.size == 1) {
        val result = ArrayList<MutableList<T>>(firstPossibility.size)
        firstPossibility.mapTo(result) { el ->
            ArrayList<T>(1).apply { add(el) }
        }
        return result
    }
    val otherPossibilities = possibilities.subList(1, possibilities.size)
    val otherPermutations = getCombinations(otherPossibilities)
    val result = ArrayList<MutableList<T>>(firstPossibility.size * otherPermutations.size)
    for (el in firstPossibility) {
        for (otherPossibilitiesPerm in otherPermutations) {
            result.add(ArrayList<T>(possibilities.size).apply {
                add(el)
                addAll(otherPossibilitiesPerm)
            })
        }
    }
    return result
}
