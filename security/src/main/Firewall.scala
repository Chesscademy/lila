package lila.security

import lila.common.PimpedJson._
import lila.http.LilaCookie
import lila.memo.VarMemo
import lila.db.Types.Coll

import scala.concurrent.duration._
import akka.util.Timeout

import play.api.mvc.{ RequestHeader, Handler, Action, Cookies }
import play.api.mvc.Results.Redirect
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits._

import play.modules.reactivemongo.Implicits._

import org.joda.time.DateTime
import ornicar.scalalib.Random

final class Firewall(
  coll: Coll, 
  cookieName: Option[String], 
  enabled: Boolean) extends lila.db.api.Full {

  val requestHandler: (RequestHeader ⇒ Option[Handler]) = enabled.fold(
    cookieName.fold((_: RequestHeader) ⇒ none[Handler]) { cn ⇒
      req ⇒ {
        val bIp = blocksIp(req.remoteAddress)
        val bCs = blocksCookies(req.cookies, cn)
        if (bIp && !bCs) infectCookie(cn)(req).some
        else if (bCs && !bIp) { blockIp(req.remoteAddress); none }
        else none
      }
    },
    _ ⇒ None)

  val blocks: (RequestHeader) ⇒ Boolean = enabled.fold(
    cookieName.fold((req: RequestHeader) ⇒ blocksIp(req.remoteAddress)) { cn ⇒
      req ⇒ (blocksIp(req.remoteAddress) || blocksCookies(req.cookies, cn))
    },
    _ ⇒ false)

  def accepts(req: RequestHeader): Boolean = !blocks(req)

  def refresh: Funit = ipsMemo reload fetch

  def blockIp(ip: String): Funit =
    if (validIp(ip) && !blocksIp(ip)) {
      log("Block IP: " + ip)
      coll.insert(Json.obj("_id" -> ip, "date" -> DateTime.now)) >> refresh
    }
    else fuccess(log("Invalid IP block: " + ip))

  private def infectCookie(name: String)(implicit req: RequestHeader) = Action {
    log("Infect cookie " + formatReq(req))
    val cookie = LilaCookie.cookie(name, Random nextString 32)
    Redirect("/") withCookies cookie
  }

  def logBlock(req: RequestHeader) {
    log("Block " + formatReq(req))
  }

  private def log(msg: Any) {
    println("[%s] %s".format("firewall", msg.toString))
  }

  private def formatReq(req: RequestHeader) =
    "%s %s %s".format(req.remoteAddress, req.uri, req.headers.get("User-Agent") | "?")

  private def blocksIp(ip: String): Boolean = ips contains ip

  private def blocksCookies(cookies: Cookies, name: String) =
    (cookies get name).isDefined

  // http://stackoverflow.com/questions/106179/regular-expression-to-match-hostname-or-ip-address
  private val ipRegex = """^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$""".r

  private def validIp(ip: String) =
    (ipRegex matches ip) && ip != "127.0.0.1" && ip != "0.0.0.0"

  private implicit val timeout = Timeout(2.seconds)
  private val ipsMemo = new VarMemo(fetch, 2.seconds)

  private def ips: Set[String] = ipsMemo.get.await

  private def fetch: Fu[Set[String]] = 
    coll.genericQueryBuilder
      .projection(Json.obj("_id" -> true))
      .cursor.toList map2 { (obj: JsObject) ⇒ obj.get[String]("_id") } map (_.flatten.toSet)
}