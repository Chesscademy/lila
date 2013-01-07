package lila
package setup

import chess.{ Variant, Mode, Color ⇒ ChessColor }
import elo.EloRange
import user.User
import lobby.Hook

case class HookConfig(
    variant: Variant,
    clock: Boolean,
    time: Int,
    increment: Int,
    mode: Mode,
    color: Color,
    eloRange: EloRange,
    ladder: Boolean) extends HumanConfig {

  def >> = (variant.id, clock, time, increment, modeId.some, eloRange.toString.some, color.name).some

  private def modeId = ladder.fold(HookConfig.LADDER_MODE_ID, mode.id)

  def hook(user: Option[User]) = Hook(
    variant = variant,
    clock = makeClock,
    mode = mode,
    color = color.name,
    user = user,
    eloRange = eloRange)

  def encode = RawHookConfig(
    v = variant.id,
    k = clock,
    t = time,
    i = increment,
    m = mode.id,
    e = eloRange.toString,
    l = ladder)

  def noRatedUnlimited = mode.casual || clock
}

object HookConfig extends BaseHumanConfig {

  def <<(v: Int, k: Boolean, t: Int, i: Int, m: Option[Int], e: Option[String], c: String) = {
    val realMode = m.fold(Mode.orDefault, Mode.default)
    new HookConfig(
      variant = Variant(v) err "Invalid game variant " + v,
      clock = k,
      time = t,
      increment = i,
      mode = realMode,
      eloRange = e.filter(_ ⇒ realMode.rated).fold(
        EloRange.orDefault,
        EloRange.default),
      color = Color(c) err "Invalid color " + c,
      ladder = m == HookConfig.LADDER_MODE_ID)
  }

  val default = HookConfig(
    variant = variantDefault,
    clock = true,
    time = 5,
    increment = 8,
    mode = Mode.default,
    eloRange = EloRange.default,
    color = Color.default,
    ladder = false)
}

private[setup] case class RawHookConfig(
    v: Int,
    k: Boolean,
    t: Int,
    i: Int,
    m: Int,
    e: String,
    l: Boolean = false) {

  def decode = for {
    variant ← Variant(v)
    mode ← Mode(m)
    eloRange ← EloRange(e)
  } yield HookConfig(
    variant = variant,
    clock = k,
    time = t,
    increment = i,
    mode = mode,
    eloRange = eloRange,
    color = Color.White,
    ladder = l)
}
