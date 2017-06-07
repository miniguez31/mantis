package io.iohk.ethereum.network

import java.net.InetSocketAddress

import akka.actor.{ActorContext, ActorRef}
import akka.agent.Agent
import akka.pattern.ask
import akka.util.Timeout
import io.iohk.ethereum.network.EtcMessageHandler.EtcPeerInfo
import io.iohk.ethereum.network.PeerActor.{ConnectionRequest, DisconnectPeer, GetStatus, SendMessage}
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.{MessageClassifier, PeerDisconnectedClassifier}
import io.iohk.ethereum.network.PeerEventBusActor.{PeerSelector, Subscribe, Unsubscribe}
import io.iohk.ethereum.network.PeerManagerActor.PeerConfiguration
import io.iohk.ethereum.network.handshaker.Handshaker
import io.iohk.ethereum.network.p2p.MessageSerializable
import io.iohk.ethereum.utils.NodeStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

trait Peer {

  /**
    * Address of the peer
    */
  val remoteAddress: InetSocketAddress

  /**
    * Unique identifier of the peer
    */
  val id: PeerId

  /**
    * Returns the current peer status
    */
  def status(): Future[PeerActor.Status]

  /**
    * Sends a message to the peer
    *
    * @param message to send to the peer
    */
  def send(message: MessageSerializable): Unit

  /**
    * Sends various messages to the peer
    *
    * @param messages to send to the peer
    */
  def send(messages: Seq[MessageSerializable]): Unit = messages.foreach(send)

  /**
    * Ends the connection with the peer
    *
    * @param reason to disconnect from the peer
    */
  def disconnectFromPeer(reason: Int): Unit

  /**
    * Subscribes the actor sender to the event of the peer sending any message from a given set.
    * The subscriber will receive a [[io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer]] when any of
    * those messages are sent from the peer.
    *
    * @param subscriber, the sender of the subscription
    */
  def subscribe(msgs: Set[Int])(implicit subscriber: ActorRef): Unit

  /**
    * Unsubscribes the actor sender to the event of the peer sending any message from a given set
    *
    * @param subscriber, the sender of the unsubscription
    */
  def unsubscribe(msgs: Set[Int])(implicit subscriber: ActorRef): Unit

  /**
    * Subscribes the actor sender to the event of the peer disconnecting.
    * The subscriber will receive a [[io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected]] when the
    * peer is disconnected.
    *
    * @param subscriber, the sender of the subscription
    */
  def subscribeToDisconnect()(implicit subscriber: ActorRef): Unit

  /**
    * Unsubscribes the actor sender to the event of the peer disconnecting
    *
    * @param subscriber, the sender of the unsubscription
    */
  def unsubscribeFromDisconnect()(implicit subscriber: ActorRef): Unit

}

case class PeerId(value: String) extends AnyVal

class PeerImpl(val remoteAddress: InetSocketAddress, val ref: ActorRef, peerEventBusActor: ActorRef) extends Peer {

  implicit val timeout = Timeout(3.seconds)

  val id: PeerId = PeerId(ref.path.name)

  def status(): Future[PeerActor.Status] =
    (ref ? GetStatus).mapTo[PeerActor.StatusResponse].map(_.status)

  override def send(message: MessageSerializable): Unit =
    ref ! SendMessage(message)

  override def disconnectFromPeer(reason: Int): Unit =
    ref ! DisconnectPeer(reason)

  override def subscribe(msgsCodes: Set[Int])(implicit subscriber: ActorRef): Unit =
    peerEventBusActor.!(Subscribe(MessageClassifier(msgsCodes, PeerSelector.WithId(id))))(subscriber)

  override def unsubscribe(msgsCodes: Set[Int])(implicit subscriber: ActorRef): Unit =
    peerEventBusActor.!(Unsubscribe(MessageClassifier(msgsCodes, PeerSelector.WithId(id))))(subscriber)

  override def subscribeToDisconnect()(implicit subscriber: ActorRef): Unit =
    peerEventBusActor.!(Subscribe(PeerDisconnectedClassifier(id)))(subscriber)

  def unsubscribeFromDisconnect()(implicit subscriber: ActorRef): Unit =
    peerEventBusActor.!(Unsubscribe(PeerDisconnectedClassifier(id)))(subscriber)
}

object PeerImpl {

  def peerFactory(nodeStatusHolder: Agent[NodeStatus],
                  peerConfiguration: PeerConfiguration,
                  peerEventBus: ActorRef,
                  handshaker: Handshaker[EtcPeerInfo],
                  messageHandlerBuilder: (EtcPeerInfo, Peer) => MessageHandler[EtcPeerInfo, EtcPeerInfo])
  : (ActorContext, InetSocketAddress, ConnectionRequest) => Peer = {
    (ctx, addr, req) =>
      val id = addr.toString.filterNot(_ == '/')
      val peerActor = ctx.actorOf(PeerActor.props(addr, nodeStatusHolder, peerConfiguration, peerEventBus,
        handshaker, messageHandlerBuilder), id)
      peerActor ! req
      new PeerImpl(addr, peerActor, peerEventBus)
  }

}