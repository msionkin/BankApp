import akka.actor._
import akka.actor.ReceiveTimeout
import scala.concurrent.duration._
import scala.language.postfixOps

object Bank {
  private case object nextClient
  private case class serve (client: Client)
  private case class newClient (client: Client)

  private val bankSystem = ActorSystem("Bank")

  private var timeToComeMS = 500    //time of next client coming constraint (in ms)
  private var timeForIssueMS = 20000  //time which need to serve the client constraint (in ms)

  private class Client(val name: String, val issueType: String, val timeForServe: Int) {
    //override def receive: Actor.Receive = ???
  }

  /*
   params: name - name of service window
           issueTypes - types of issues that service window can serve
   */
  private class ServiceWindow(val name: String, val issueTypes: Set[String]) {
    var clientsInQueueCount = 0
    def incClientsCount() = {clientsInQueueCount += 1}
    def decClientsCount() = {clientsInQueueCount -= 1}
    def canServe(issueType: String): Boolean = {
      return issueTypes.contains(issueType)
    }
  }

  private object ServiceWindowActor {
    def props(serviceWindow: ServiceWindow): Props = Props(new ServiceWindowActor(serviceWindow))
      /* var s: Props = Props(new ServiceWindow(name, issueTypes))
       var str : String = s.args.apply(0).asInstanceOf[String]*/
  }

  private class ServiceWindowActor(val serviceWindow: ServiceWindow) extends Actor {
    def receive = {
      case serve(client) =>
        //serviceWindow.incClientsCount()
        println("start service for " + client.name + " with problem " + client.issueType + " by " + serviceWindow.name + " " + serviceWindow.clientsInQueueCount)
        Thread.sleep(client.timeForServe)
        /*var i = 0
        var sum = 1.0
        var mult = 1.0
        for (i <- 1 to 1000000) {
          mult = mult * Math.pow(i,sum/mult)/Math.sin(i)
          sum = sum + mult/sum
          mult = sum/i
        }*/
        println("stop  service for " + client.name + " with problem " + client.issueType + " by " + serviceWindow.name)
        serviceWindow.decClientsCount()
    }

    def canServe(issueType: String): Boolean = serviceWindow.canServe(issueType)
  }

  private class ClientsFactory(distributor: ActorRef) extends Actor {
    val clientIssueTypes = List("issue1", "issue2", "issue3" /*"insolubleIssue"*/)

    var random = scala.util.Random
    var timeToCome = 0    //time of next client coming
    var timeForIssue = 0  //time which need to serve the client
    var issueType = "" //client type of issue
    var count = 0  //count of clients

    //send  new client to the distributor and then send msg for generate new client
    def receive = {
      case nextClient =>
        if (count > 25) {
          self ! PoisonPill
        } else {
          timeToCome = random.nextInt(timeToComeMS)
          timeForIssue = random.nextInt(timeForIssueMS)
          issueType = clientIssueTypes(random.nextInt(clientIssueTypes.length))
          count += 1
          distributor ! newClient(new Client("client" + count, issueType, timeForIssue))
          Thread.sleep(timeToCome)
          self ! nextClient
        }
    }
  }

  private object ServiceWindowsFactory {
    val serviceIssueTypes = List(Set("issue1", "issue2"), Set("issue1", "issue2"), Set("issue3"), Set("any issue"))

    def generate: List[ServiceWindow] = {
      var count: Int = 0

      def next(issues: Set[String]): ServiceWindow = {
        count += 1
        return new ServiceWindow("ServiceWindow" + count, issues)
      }

      return serviceIssueTypes.map((issues: Set[String]) => next(issues))
    }
  }

  private class Distributor() extends Actor {
    val serviceWindows = ServiceWindowsFactory.generate

    var serviceWindowActorsMap = collection.mutable.Map[ServiceWindow, ActorRef]()
    for (sw <- serviceWindows) {
      serviceWindowActorsMap(sw) = context.actorOf(ServiceWindowActor.props(sw), name = sw.name)
    }

    //TODO: stop all system by interview all serviceWindows that they haven't any queue (mailbox is empty),
    //and then delete ReceiveTimeout
    def receive = {
      case newClient(client) =>
        selectServiceWindowFor(client) forward serve(client)
        context.setReceiveTimeout(timeForIssueMS milliseconds)

      case ReceiveTimeout =>
        if(serviceWindows.forall(_.clientsInQueueCount == 0)) {
          bankSystem.awaitTermination()
        } else {
          println("not all queues in windows are empty")
        }
    }

    def selectServiceWindowFor(client: Client): ActorRef = {
      val serWins = serviceWindows.filter(_.canServe(client.issueType)) //list of serWins which can help this client
      if (serWins.size > 0) {
        var goodWindow = serWins.minBy(_.clientsInQueueCount) //выбираем окно с наименьшей очередью
        goodWindow.incClientsCount()                          //увеличиваем количество человек в этом окне на одного
        return serviceWindowActorsMap(goodWindow)
      } else {
        //TODO: select correct service window
        var sw = serviceWindows.filter(_.canServe("any issue"))
        return serviceWindowActorsMap(sw.head)
      }
    }
  }

  def start = {
    val distributor = bankSystem.actorOf(Props(new Distributor()))

    val clientsFactory = bankSystem.actorOf(Props(new ClientsFactory(distributor)))
    clientsFactory ! nextClient
  }
}

object BankApp extends App {
  Bank.start
}