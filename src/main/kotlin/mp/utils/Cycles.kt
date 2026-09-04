package mp.utils

/**
 * Найти циклы в структуре, где у каждого элемента есть не более одного следующего ([next]) -
 * т.е. в структуре, которая должна быть линией, деревом или лесом.
 *
 * Каждая цепочка обходится не более одного раза, поэтому для каждой связной части
 * возвращается не более одного цикла.
 *
 * @param startNodes элементы, с которых начинается обход
 * @param next следующий элемент (родитель/предыдущий) для данного, или null, если цепочка на нем кончается
 * @return найденные циклы; каждый - список своих элементов в порядке обхода (без повтора замыкающего элемента)
 */
fun <T : Any> findCycles(startNodes: Iterable<T>, next: (T) -> T?): List<List<T>> {
    val cycles = mutableListOf<List<T>>()
    val checkedNodes = mutableSetOf<T>()
    for (startNode in startNodes) {
        if (startNode in checkedNodes) continue

        //Идем по цепочке, запоминая порядок пройденных элементов;
        //если вернулись в элемент текущей цепочки - это цикл
        val path = LinkedHashMap<T, Int>()
        var currentNode: T? = startNode
        while (currentNode != null && currentNode !in checkedNodes) {
            val node = currentNode
            val cycleStart = path[node]
            if (cycleStart != null) {
                cycles.add(path.keys.drop(cycleStart))
                break
            }
            path[node] = path.size
            currentNode = next(node)
        }
        checkedNodes.addAll(path.keys)
    }
    return cycles
}
