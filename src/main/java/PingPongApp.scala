import akka.actor.{Props, ActorSystem, Actor, ActorRef}

case object PingMessage
case object PongMessage
case object StartMessage
case object StopMessage

class Ping(pong: ActorRef, num: Int, name: String) extends Actor {
  var mult = num
  def incrementAndPrint { mult -= 1; println(name + "  " + mult) }
  def receive = {
    case StartMessage =>
      incrementAndPrint
      pong ! PingMessage
    case PongMessage =>
      incrementAndPrint
      if (mult <= 0) {
        sender ! StopMessage
        println("ping stopped")
        context.stop(self)
      } else {
        sender ! PingMessage
      }
  }
}

class Pong extends Actor {
  def receive = {
    case PingMessage =>
      println("  pong")
      sender ! PongMessage
    case StopMessage =>
      println("pong stopped")
      context.stop(self)
  }
}

object PingPongApp extends App {
  val poingPongsystem = ActorSystem("poingPongsystem")
  val pong = poingPongsystem.actorOf(Props[Pong], name = "pong")
  val ping = poingPongsystem.actorOf(Props(new Ping(pong, 10, "ping1")), name = "ping")
  val ping2 = poingPongsystem.actorOf(Props(new Ping(pong, 7, "ping2")), name = "ping2")
  // start them going
  ping ! StartMessage
  ping2 ! StartMessage
}
