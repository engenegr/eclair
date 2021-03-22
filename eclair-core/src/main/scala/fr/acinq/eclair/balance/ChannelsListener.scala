package fr.acinq.eclair.balance

import akka.Done
import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.balance.ChannelsListener.{ChannelData, ChannelDied, Command, GetChannels, GetChannelsResponse}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.{ChannelPersisted, ChannelRestored, HasCommitments}

import scala.concurrent.Promise

object ChannelsListener {

  // @formatter:off
  sealed trait Command
  private final case class ChannelData(channelId: ByteVector32, channel: akka.actor.ActorRef, data: HasCommitments) extends Command
  private final case class ChannelDied(channelId: ByteVector32) extends Command
  final case class GetChannels(replyTo: typed.ActorRef[GetChannelsResponse]) extends Command
  // @formatter:on

  case class GetChannelsResponse(channels: Map[ByteVector32, HasCommitments])

  def apply(ready: Promise[Done]): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelRestored](e => ChannelData(e.channelId, e.channel, e.data)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelPersisted](e => ChannelData(e.channelId, e.channel, e.data)))
      // since subscription is asynchronous, we send a fake event so we know when we are subscribed
      context.system.eventStream ! EventStream.Publish(ChannelRestored(null, ByteVector32.Zeroes, null, null, null))
      Behaviors.receiveMessagePartial {
        case ChannelData(channelId, _, _) if channelId == ByteVector32.Zeroes =>
          context.log.info("channels listener is ready")
          ready.success(Done)
          new ChannelsListener(context).running(Map.empty)
      }
    }
}

private class ChannelsListener(context: ActorContext[Command]) {

  private val log = context.log

  def running(channels: Map[ByteVector32, HasCommitments]): Behavior[Command] =
    Behaviors.receiveMessage {
      case ChannelData(channelId, channel, data) =>
        Closing.isClosed(data, additionalConfirmedTx_opt = None) match {
          case None =>
            context.watchWith(channel.toTyped, ChannelDied(channelId))
            running(channels + (channelId -> data))
          case _ =>
            // if channel is closed we remove early from the map because
            log.debug("remove channel={} from list (closed)", channelId)
            context.unwatch(channel.toTyped)
            running(channels - channelId)
        }
      case ChannelDied(channelId) =>
        log.debug("remove channel={} from list (died)", channelId)
        running(channels - channelId)
      case GetChannels(replyTo) =>
        replyTo ! GetChannelsResponse(channels)
        Behaviors.same
    }
}
