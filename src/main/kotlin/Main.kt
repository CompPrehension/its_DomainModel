import its.model.DomainSolvingModel

fun main(){
    val dir = "D:\\MEGA\\IT\\Projects\\Git\\Indev\\render-meaning-tree\\domain"
    val model = DomainSolvingModel(
        dir,
        buildMethod = DomainSolvingModel.BuildMethod.LOQI
    ).validate()

    val v = CounterVisitor()
    v.process(model.decisionTree)

    println("There are ${v.count} tree nodes total")
}