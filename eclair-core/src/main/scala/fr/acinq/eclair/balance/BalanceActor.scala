package fr.acinq.eclair.balance

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.eclair.balance.BalanceActor.{Command, TickBalance, WrappedChannels, WrappedGlobalBalance}
import fr.acinq.eclair.balance.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.{Eclair, GlobalBalance}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object BalanceActor {

  // @formatter:off
  sealed trait Command
  private final case object TickBalance extends Command
  private final case class WrappedChannels(wrapped: ChannelsListener.GetChannelsResponse) extends Command
  private final case class WrappedGlobalBalance(wrapped: Try[GlobalBalance]) extends Command
  // @formatter:on

  def apply(eclair: Eclair, channelsListener: ActorRef[ChannelsListener.GetChannels], interval: FiniteDuration)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(TickBalance, interval)
        new BalanceActor(context, eclair, channelsListener).apply(refBalance_opt = None)
      }
    }
  }

}

private class BalanceActor(context: ActorContext[Command],
                           eclair: Eclair,
                           channelsListener: ActorRef[ChannelsListener.GetChannels])(implicit ec: ExecutionContext) {

  private val log = context.log

  def apply(refBalance_opt: Option[GlobalBalance]): Behavior[Command] = Behaviors.receiveMessage {
    case TickBalance =>
      log.debug("checking balance...")
      channelsListener ! ChannelsListener.GetChannels(context.messageAdapter[ChannelsListener.GetChannelsResponse](WrappedChannels))
      Behaviors.same
    case WrappedChannels(res) =>
      val self = context.self
      eclair.globalBalance(Some(res.channels))(30 seconds).onComplete(res => self ! WrappedGlobalBalance(res))
      Behaviors.same
    case WrappedGlobalBalance(res) =>
      res match {
        case Success(result) =>
          log.info("current balance: total={} onchain.confirmed={} onchain.unconfirmed={} offchain={} openback.pay-to-open={} openback.swap-in={}", result.total.toDouble, result.onchain.confirmed.toDouble, result.onchain.unconfirmed.toDouble, result.offchain.total.toDouble)
          log.debug("current balance details : {}", result)
          Metrics.GlobalBalance.withoutTags().update(result.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnchainConfirmed).update(result.onchain.confirmed.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnchainUnconfirmed).update(result.onchain.unconfirmed.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForFundingConfirmed).update(result.offchain.waitForFundingConfirmed.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForFundingLocked).update(result.offchain.waitForFundingLocked.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.normal).update(result.offchain.normal.total.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.shutdown).update(result.offchain.shutdown.total.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingLocal).update(result.offchain.closing.localCloseBalance.total.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingRemote).update(result.offchain.closing.remoteCloseBalance.total.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingUnknown).update(result.offchain.closing.unknownCloseBalance.total.toMilliBtc.toLong)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForPublishFutureCommitment).update(result.offchain.waitForPublishFutureCommitment.toMilliBtc.toLong)
          refBalance_opt match {
            case Some(refBalance) =>
              val normalizedValue = 100 + (if (refBalance.total.toSatoshi.toLong > 0) (result.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong) * 1000D / refBalance.total.toSatoshi.toLong else 0)
              val diffValue = result.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong
              log.info("relative balance: current={} reference={} normalized={} diff={}", result.total.toDouble, refBalance.total.toDouble, normalizedValue, diffValue)
              Metrics.GlobalBalanceNormalized.withoutTags().update(normalizedValue)
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.plus).update(diffValue.max(0))
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.minus).update((-diffValue).max(0))
              Behaviors.same
            case None =>
              log.info("using balance={} as reference", result.total.toDouble)
              apply(Some(result))
          }
        case Failure(t) =>
          log.warn("could not compute balance: ", t)
          Behaviors.same
      }
  }

}
