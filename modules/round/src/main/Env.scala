package lila.round

import akka.actor._
import akka.pattern.ask
import com.typesafe.config.Config

import lila.common.PimpedConfig._
import lila.game.actorApi.ChangeFeaturedGame
import lila.user.Evaluator
import lila.hub.actorApi.map.Ask
import lila.socket.actorApi.GetVersion
import makeTimeout.large

final class Env(
    config: Config,
    system: ActorSystem,
    db: lila.db.Env,
    hub: lila.hub.Env,
    ai: lila.ai.Ai,
    getUsername: String ⇒ Fu[Option[String]],
    getUsernameOrAnon: String ⇒ Fu[String],
    evaluator: Evaluator,
    uciMemo: lila.game.UciMemo,
    rematch960Cache: lila.memo.ExpireSetMemo,
    i18nKeys: lila.i18n.I18nKeys,
    scheduler: lila.common.Scheduler) {

  private val settings = new {
    val MessageTtl = config duration "message.ttl"
    val UidTimeout = config duration "uid.timeout"
    val PlayerDisconnectTimeout = config duration "player.disconnect.timeout"
    val PlayerRagequitTimeout = config duration "player.ragequit.timeout"
    val AnimationDelay = config duration "animation.delay"
    val Moretime = config duration "moretime"
    val SocketName = config getString "socket.name"
    val SocketTimeout = config duration "socket.timeout"
    val FinisherLockTimeout = config duration "finisher.lock.timeout"
    val HijackTimeout = config duration "hijack.timeout"
    val NetDomain = config getString "net.domain"
    val ActorMapName = config getString "actor.map.name"
    val ActorName = config getString "actor.name"
    val HijackEnabled = config getBoolean "hijack.enabled"
    val HijackSalt = config getString "hijack.salt"
  }
  import settings._

  lazy val history = () ⇒ new History(ttl = MessageTtl)

  val roundMap = system.actorOf(Props(new lila.hub.ActorMap[Round] {
    def mkActor(id: String) = new Round(
      gameId = id,
      messenger = messenger,
      takebacker = takebacker,
      finisher = finisher,
      rematcher = rematcher,
      player = player,
      drawer = drawer,
      socketHub = socketHub,
      moretimeDuration = Moretime)
    def receive = actorMapReceive
  }), name = ActorMapName)

  private val socketHub = system.actorOf(
    Props(new lila.socket.SocketHubActor.Default[Socket] {
      def mkActor(id: String) = new Socket(
        gameId = id,
        history = history(),
        getUsername = getUsername,
        uidTimeout = UidTimeout,
        socketTimeout = SocketTimeout,
        disconnectTimeout = PlayerDisconnectTimeout,
        ragequitTimeout = PlayerRagequitTimeout)
    }),
    name = SocketName)

  lazy val socketHandler = new SocketHandler(
    hub = hub,
    roundMap = roundMap,
    socketHub = socketHub,
    hijack = hijack,
    bus = system.lilaBus)

  private lazy val perfsUpdater = new PerfsUpdater(evaluator = evaluator)

  private lazy val finisher = new Finisher(
    messenger = messenger,
    perfsUpdater = perfsUpdater,
    indexer = hub.actor.gameIndexer,
    tournamentOrganizer = hub.actor.tournamentOrganizer)

  private lazy val rematcher = new Rematcher(
    messenger = messenger,
    router = hub.actor.router,
    rematch960Cache = rematch960Cache)

  private lazy val player: Player = new Player(
    engine = ai,
    bus = system.lilaBus,
    finisher = finisher,
    cheatDetector = cheatDetector,
    roundMap = hub.actor.roundMap,
    uciMemo = uciMemo)

  // public access to AI play, for setup.Processor usage
  val aiPlay = player ai _

  private lazy val drawer = new Drawer(
    messenger = messenger,
    finisher = finisher)

  private lazy val cheatDetector = new CheatDetector

  lazy val meddler = new Meddler(
    roundMap = roundMap,
    socketHub = socketHub)

  lazy val messenger = new Messenger(
    bus = system.lilaBus,
    i18nKeys = i18nKeys)

  def version(gameId: String): Fu[Int] =
    socketHub ? Ask(gameId, GetVersion) mapTo manifest[Int]

  private[round] def animationDelay = AnimationDelay
  private[round] def moretimeSeconds = Moretime.toSeconds

  {
    import scala.concurrent.duration._

    scheduler.future(0.33 hour, "game: finish by clock") {
      titivate.finishByClock
    }

    scheduler.effect(0.41 hour, "game: finish abandoned") {
      titivate.finishAbandoned
    }
  }

  private lazy val titivate = new Titivate(roundMap, meddler, scheduler)

  lazy val hijack = new Hijack(HijackTimeout, HijackSalt, HijackEnabled)

  private lazy val takebacker = new Takebacker(
    messenger = messenger,
    uciMemo = uciMemo)

  lazy val moveBroadcast = system.actorOf(Props(new MoveBroadcast), name = "move-broadcast")
}

object Env {

  lazy val current = "[boot] round" describes new Env(
    config = lila.common.PlayApp loadConfig "round",
    system = lila.common.PlayApp.system,
    db = lila.db.Env.current,
    hub = lila.hub.Env.current,
    ai = lila.ai.Env.current.ai,
    getUsername = lila.user.Env.current.usernameOption,
    getUsernameOrAnon = lila.user.Env.current.usernameOrAnonymous,
    evaluator = lila.user.Env.current.evaluator,
    uciMemo = lila.game.Env.current.uciMemo,
    rematch960Cache = lila.game.Env.current.cached.rematch960,
    i18nKeys = lila.i18n.Env.current.keys,
    scheduler = lila.common.PlayApp.scheduler)
}
