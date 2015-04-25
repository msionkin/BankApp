import akka.actor._
import akka.actor.ReceiveTimeout
import scala.concurrent.duration._
import scala.language.postfixOps

object Bank {
  //типы сообщений, передаваемых между актерами
  private case object nextClient
  private case class serve (client: Client)
  private case class newClient (client: Client)

  //система актеров, в которой создаются все актеры приложения
  private val bankSystem = ActorSystem("Bank")

  //максимальный промежуток времени (мс), через который приходит новый клиент
  private var timeToCome = 700
  //максимальный интервал времени (мс), в течении которого обслуживается клиент
  private var timeForIssue = 20000

  private class Client(val name: String, val issueType: String, val timeForServe: Int) {
    //override def receive: Actor.Receive = ???
  }

  /**
   * @param name имя окна обслуживания
   * @param issueTypes множество типов проблем, которые может решить окно
   */
  private class ServiceWindow(val name: String, val issueTypes: Set[String]) {
    //количество клиентов, стоящих в очереди в данное окно
    var clientsInQueueCount = 0
    def incClientsCount() = {clientsInQueueCount += 1}
    def decClientsCount() = {clientsInQueueCount -= 1}

    //обслуживает ли данное окно проблемы типа issueType
    def isCanServe(issueType: String): Boolean = {
      return issueTypes.contains(issueType)
    }
  }

  //этот объект необходим для инициализации акторов типа "окно обслуживания" с помощью Props
  private object ServiceWindowActor {
    def props(serviceWindow: ServiceWindow): Props = Props(new ServiceWindowActor(serviceWindow))
  }

  //актор типа "окно обслуживания"
  private class ServiceWindowActor(val serviceWindow: ServiceWindow) extends Actor {
    def receive = {
      //сообщение о том, что надо обслужить клиента
      case serve(client) =>
        println("start service for " + client.name + " with problem " + client.issueType + " by " + serviceWindow.name + " " + serviceWindow.clientsInQueueCount)
        Thread.sleep(client.timeForServe)
        /*var i = 0
        var sum = 1.0
        var mult = 1.0
        for (i <- 1 to 10000000) {
          mult = mult * Math.pow(i,sum/mult)/Math.sin(i)
          sum = sum + mult/sum
          mult = sum/i
        }*/
        println("stop  service for " + client.name + " with problem " + client.issueType + " by " + serviceWindow.name)
        serviceWindow.decClientsCount()
    }

    def isCanServe(issueType: String): Boolean = serviceWindow.isCanServe(issueType)
  }

  //"фабрика" клиентов, генерирующая определенное число новых клиентов случайным образом
  private class ClientsFactory(distributor: ActorRef) extends Actor {
    val clientIssueTypes = List("issue1", "issue2", "issue3" /*"insolubleIssue"*/)

    var random = scala.util.Random
    var randTimeToCome = 0
    var randTimeForIssue = 0
    var randIssueType = ""
    var clientsCount = 0  //количество сгенерированных клиентов
    val maxClientsCount = 24 //максимальное количество клиентов

    def receive = {
      //сообщение о том, что надо сгенерировать нового клиента
      case nextClient =>
        if (clientsCount > maxClientsCount) {  //если общее количество клиентов больше максимального
          self ! PoisonPill                    //то отключаем "фабрику" (завершаем работу актора)
        } else {
          randTimeToCome = random.nextInt(timeToCome)
          randTimeForIssue = random.nextInt(timeForIssue)
          randIssueType = clientIssueTypes(random.nextInt(clientIssueTypes.length))
          clientsCount += 1
          //посылаем сообщение о новом клиенте распределителю
          distributor ! newClient(new Client("client" + clientsCount, randIssueType, randTimeForIssue))
          //приостанавливаем работу фабрики
          Thread.sleep(randTimeToCome)
          self ! nextClient
        }
    }
  }

  //"фабрика" окон обслуживания, отрабатывает один раз
  private object ServiceWindowsFactory {
    val serviceIssueTypes = List(Set("issue1", "issue2"), Set("issue1", "issue2"), Set("issue3"), Set("info window"))

    def generate: List[ServiceWindow] = {
      var count: Int = 0

      def next(issues: Set[String]): ServiceWindow = {
        count += 1
        return new ServiceWindow("ServiceWindow" + count, issues)
      }

      return serviceIssueTypes.map((issues: Set[String]) => next(issues))
    }
  }

  //Распределитель клиентов по окнам обслуживания (в зависимости от типа проблемы)
  private class Distributor() extends Actor {
    val serviceWindows = ServiceWindowsFactory.generate

    //создаем акторов, соответствующих окнам обслуживания, и запоминаем в Map
    var serviceWindowActorsMap = collection.mutable.Map[ServiceWindow, ActorRef]()
    for (sw <- serviceWindows) {
      serviceWindowActorsMap(sw) = context.actorOf(ServiceWindowActor.props(sw), name = sw.name)
    }

    def receive = {
      case newClient(client) =>                              //при получении нового клиента,
        selectServiceWindowFor(client) forward serve(client) //выбираем подходящее для него окно
        context.setReceiveTimeout(2*timeForIssue milliseconds) //ожидаем следующего клиента в течении заданного времени

      case ReceiveTimeout =>                                  //если клиента нет,
        if(serviceWindows.forall(_.clientsInQueueCount == 0)) { //то проверяем каждое окно, на наличие очереди
          println("All clients have served, all windows are free")
          println("Bank is closed")
          bankSystem.shutdown()                                //завершаем работу банка
        } else {
          println("Not all queues in windows are empty")
        }
    }

    //функция выбора окна для клиента
    def selectServiceWindowFor(client: Client): ActorRef = {
      val serWins = serviceWindows.filter(_.isCanServe(client.issueType)) //выбираем окна, которые могу обслужить данного клиента
      if (serWins.size > 0) {                                  //если такие окна есть, то
        var goodWindow = serWins.minBy(_.clientsInQueueCount) //выбираем окно с наименьшей очередью
        goodWindow.incClientsCount()                          //увеличиваем количество человек в этом окне на одного
        return serviceWindowActorsMap(goodWindow)
      } else {                                                 //если таких окон нет, то
        var sw = serviceWindows.filter(_.isCanServe("info window")) //выбираем справочное окно
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