package zio.akka.cluster.sharding

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout }
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.util.Timeout
import zio.akka.cluster.sharding
import zio.akka.cluster.sharding.MessageEnvelope.{ MessagePayload, PassivatePayload, PoisonPillPayload }
import zio.{ Has, Ref, Runtime, Tag, Task, UIO, ZIO, ZLayer }

/**
 *  A `Sharding[M]` is able to send messages of type `M` to a sharded entity or to stop one.
 */
trait Sharding[M] {

  def send(entityId: String, data: M): Task[Unit]
  def send(entityId: String, data: M, sender: ActorRef): Task[Unit]

  def stop(entityId: String): Task[Unit]

  def passivate(entityId: String): Task[Unit]

}

object Sharding {

  /**
   *  Starts cluster sharding on this node for a given entity type.
   *
   * @param name the name of the entity type
   * @param onMessage the behavior of the entity when it receives a message
   * @param numberOfShards a fixed number of shards
   * @param askTimeout     a finite duration specifying how long an ask is allowed to wait for an entity to respond
   * @return a [[Sharding]] object that can be used to send messages to sharded entities
   */
  def start[R <: Has[_], Msg, State: Tag](
    name: String,
    onMessage: Msg => ZIO[Entity[State] with R, Nothing, Unit],
    numberOfShards: Int = 100,
    askTimeout: FiniteDuration = 10.seconds
  ): ZIO[Has[ActorSystem] with R, Throwable, Sharding[Msg]] =
    for {
      rts            <- ZIO.runtime[Has[ActorSystem] with R]
      actorSystem     = rts.environment.get[ActorSystem]
      shardingRegion <- Task(
                          ClusterSharding(actorSystem).start(
                            typeName = name,
                            entityProps = Props(new ShardEntity[R, Msg, State](rts)(onMessage)),
                            settings = ClusterShardingSettings(actorSystem),
                            extractEntityId = {
                              case MessageEnvelope(entityId, payload) =>
                                payload match {
                                  case MessageEnvelope.PoisonPillPayload    => ("0", PoisonPill)
                                  case MessageEnvelope.PassivatePayload     => ("0", Passivate(PoisonPill))
                                  case p: MessageEnvelope.MessagePayload[_] => ("0", p)
                                }
                            },
                            extractShardId = {
                              case msg: MessageEnvelope => (math.abs(msg.entityId.hashCode) % numberOfShards).toString
                            }
                          )
                        )
    } yield new ShardingImpl[Msg] {
      override val getShardingRegion: ActorRef = shardingRegion
      override implicit val timeout: Timeout   = Timeout(askTimeout)
    }

  /**
   * Starts cluster sharding in proxy mode for a given entity type.
   *
   * @param name           the name of the entity type
   * @param role           an optional role to specify that this entity type is located on cluster nodes with a specific role
   * @param numberOfShards a fixed number of shards
   * @param askTimeout     a finite duration specifying how long an ask is allowed to wait for an entity to respond
   * @return a [[Sharding]] object that can be used to send messages to sharded entities on other nodes
   */
  def startProxy[Msg](
    name: String,
    role: Option[String],
    numberOfShards: Int = 100,
    askTimeout: FiniteDuration = 10.seconds
  ): ZIO[Has[ActorSystem], Throwable, Sharding[Msg]] =
    for {
      rts            <- ZIO.runtime[Has[ActorSystem]]
      actorSystem     = rts.environment.get
      shardingRegion <- Task(
                          ClusterSharding(actorSystem).startProxy(
                            typeName = name,
                            role,
                            extractEntityId = {
                              case MessageEnvelope(entityId, payload) =>
                                payload match {
                                  case MessageEnvelope.PoisonPillPayload    => (entityId, PoisonPill)
                                  case MessageEnvelope.PassivatePayload     => (entityId, Passivate(PoisonPill))
                                  case p: MessageEnvelope.MessagePayload[_] => (entityId, p)
                                }
                            },
                            extractShardId = {
                              case msg: MessageEnvelope => (math.abs(msg.entityId.hashCode) % numberOfShards).toString
                            }
                          )
                        )
    } yield new ShardingImpl[Msg] {
      override val timeout: Timeout            = Timeout(askTimeout)
      override val getShardingRegion: ActorRef = shardingRegion
    }

  private[sharding] trait ShardingImpl[Msg] extends Sharding[Msg] {
    implicit val timeout: Timeout
    val getShardingRegion: ActorRef

    override def send(entityId: String, data: Msg): Task[Unit] =
      Task(getShardingRegion ! sharding.MessageEnvelope(entityId, MessagePayload(data)))

    override def send(entityId: String, data: Msg, sender: ActorRef): Task[Unit] =
      Task(getShardingRegion.!(sharding.MessageEnvelope(entityId, MessagePayload(data)))(sender))

    override def stop(entityId: String): Task[Unit] =
      Task(getShardingRegion ! sharding.MessageEnvelope(entityId, PoisonPillPayload))

    override def passivate(entityId: String): Task[Unit] =
      Task(getShardingRegion ! sharding.MessageEnvelope(entityId, PassivatePayload))

  }

  private[sharding] class ShardEntity[R <: Has[_], Msg, State: Tag](rts: Runtime[R])(
    onMessage: Msg => ZIO[Entity[State] with R, Nothing, Unit]
  ) extends Actor {

    val ref: Ref[Option[State]]                     = rts.unsafeRun(Ref.make[Option[State]](None))
    val actorContext: ActorContext                  = context
    val service: Entity.Service[State]              = new Entity.Service[State] {
      override def context: ActorContext                         = actorContext
      override def id: String                                    = actorContext.self.path.name
      override def state: Ref[Option[State]]                     = ref
      override def stop: UIO[Unit]                               = UIO(actorContext.stop(self))
      override def passivate: UIO[Unit]                          = UIO(actorContext.parent ! Passivate(PoisonPill))
      override def passivateAfter(duration: Duration): UIO[Unit] = UIO(actorContext.self ! SetTimeout(duration))
      override def replyToSender[M](msg: M): Task[Unit]          = Task(actorContext.sender() ! msg)
    }
    val entity: ZLayer[Any, Nothing, Entity[State]] = ZLayer.succeed(service)

    def receive: Receive = {
      case SetTimeout(duration) =>
        actorContext.setReceiveTimeout(duration)
      case ReceiveTimeout       =>
        actorContext.parent ! Passivate(PoisonPill)
      case p: Passivate         =>
        actorContext.parent ! p
      case MessagePayload(msg)  =>
        rts.unsafeRunSync(onMessage(msg.asInstanceOf[Msg]).provideSomeLayer[R](entity))
        ()
      case _                    =>
    }
  }
}
