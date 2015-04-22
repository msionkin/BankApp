import akka.actor.{Props, ActorSystem, Actor, ActorRef}

case object StartMessage
case object StopMessage
case class nextValue(value: Int)

class Factorial (act: ActorRef, num : Int) extends Actor {
  var mult = num+1

  def decrement {mult -= 1}

  def receive = {
    case StartMessage =>
      act ! nextValue(1)
    case nextValue(number) =>
      decrement
      if (mult > 0) {
        act ! nextValue(number * mult)
      } else {
        println(number)
        context.system.shutdown()
      }
  }
}

object FactorialApp extends App{
  val factorialSystem = ActorSystem("factorialSystem")
  val fact: ActorRef = factorialSystem.actorOf(Props(new Factorial(fact,5)), name = "fact")

  fact ! StartMessage
}
