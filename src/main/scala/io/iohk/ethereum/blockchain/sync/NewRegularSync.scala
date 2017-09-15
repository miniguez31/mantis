package io.iohk.ethereum.blockchain.sync

import akka.actor._
import io.iohk.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer}
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.transactions.PendingTransactionsManager
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, RemoveOmmers}
import io.iohk.ethereum.utils.Config.SyncConfig
import io.iohk.ethereum.validators.Validators
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

class NewRegularSync(
  val appStateStorage: AppStateStorage,
  // FIXME: can we make indepent of Blockchain?
  val blockchain: Blockchain,
  val validators: Validators,
  val etcPeerManager: ActorRef,
  val peerEventBus: ActorRef,
  val ommersPool: ActorRef,
  val pendingTransactionsManager: ActorRef,
  val ledger: Ledger,
  val syncConfig: SyncConfig,
  implicit val scheduler: Scheduler)
  extends Actor with ActorLogging with PeerListSupport with BlacklistSupport with SyncBlocksValidator with BlockBroadcast {

  import NewRegularSync._
  import syncConfig._

  private var headersQueue: Seq[BlockHeader] = Nil
  private var waitingForActor: Option[ActorRef] = None
  private var resolvingBranches: Boolean = false

  scheduler.schedule(printStatusInterval, printStatusInterval, self, PrintStatus)

  def handleCommonMessages: Receive = handlePeerListMessages orElse handleBlacklistMessages

  override def receive: Receive = idle

  def idle: Receive = handleCommonMessages orElse {
    case Start =>
      log.info("Starting block synchronization")
      appStateStorage.fastSyncDone()
      context become running
      askForHeaders()
  }

  def running: Receive = handleCommonMessages orElse {
    case ResumeRegularSync =>
      askForHeaders()

    case ResponseReceived(peer: Peer, BlockHeaders(headers), timeTaken) =>
      log.info("Received {} block headers in {} ms", headers.size, timeTaken)
      waitingForActor = None
      if (resolvingBranches) handleBlockBranchResolution(peer, headers.reverse)
      else handleDownload(peer, headers)

    case ResponseReceived(peer, BlockBodies(blockBodies), timeTaken) =>
      log.info("Received {} block bodies in {} ms", blockBodies.size, timeTaken)
      waitingForActor = None
      handleBlockBodies(peer, blockBodies)

    //todo improve mined block handling - add info that block was not included because of syncing [EC-250]
    //we allow inclusion of mined block only if we are not syncing / reorganising chain
    case MinedBlock(block) =>
      if (headersQueue.isEmpty && waitingForActor.isEmpty) {
        //we are at the top of chain we can insert new block
        blockchain.getBlockHeaderByHash(block.header.parentHash)
          .flatMap(b => blockchain.getTotalDifficultyByHash(b.hash)) match {
          case Some(parentTd) if appStateStorage.getBestBlockNumber() < block.header.number =>
            //just insert block and let resolve it with regular download
            insertMinedBlock(block, parentTd)
          case _ =>
            log.error("Failed to add mined block")
        }
      } else {
        ommersPool ! AddOmmers(block.header)
      }

    case PrintStatus =>
      log.info(s"Block: ${appStateStorage.getBestBlockNumber()}. Peers: ${handshakedPeers.size} (${blacklistedPeers.size} blacklisted)")

    case PeerRequestHandler.RequestFailed(peer, reason) if waitingForActor.contains(sender()) =>
      waitingForActor = None
      if (handshakedPeers.contains(peer)) {
        blacklist(peer.id, blacklistDuration, reason)
      }
      scheduleResume()
  }

  private def insertMinedBlock(block: Block, parentTd: BigInt) = {
    val result: Either[BlockExecutionError, Seq[Receipt]] = ledger.executeBlock(block)

    result match {
      case Right(receipts) =>
        blockchain.save(block)
        blockchain.save(block.header.hash, receipts)
        appStateStorage.putBestBlockNumber(block.header.number)
        val newTd = parentTd + block.header.difficulty
        blockchain.save(block.header.hash, newTd)

        handshakedPeers.keys.foreach(peer => etcPeerManager ! EtcPeerManagerActor.SendMessage(NewBlock(block, newTd), peer.id))
        ommersPool ! new RemoveOmmers((block.header +: block.body.uncleNodesList).toList)
        pendingTransactionsManager ! PendingTransactionsManager.RemoveTransactions(block.body.transactionList)

        log.debug(s"Added new block $block")
      case Left(err) =>
        log.warning(s"Failed to execute mined block because of $err")
    }
  }

  private def askForHeaders() = {
    bestPeer match {
      case Some(peer) =>
        val blockNumber = appStateStorage.getBestBlockNumber()
        requestBlockHeaders(peer, GetBlockHeaders(Left(blockNumber + 1), blockHeadersPerRequest, skip = 0, reverse = false))
        resolvingBranches = false

      case None =>
        log.debug("No peers to download from")
        scheduleResume()
    }
  }

  private def handleBlockBranchResolution(peer: Peer, message: Seq[BlockHeader]) = {
    if (message.nonEmpty && message.last.hash == headersQueue.head.parentHash) {
      headersQueue = message ++ headersQueue
      processBlockHeaders(peer, headersQueue)
    } else {
      //we did not get previous blocks, there is no way to resolve, blacklist peer and continue download
      resumeWithDifferentPeer(peer)
    }
  }

  private def handleDownload(peer: Peer, message: Seq[BlockHeader]) = if (message.nonEmpty) {
    headersQueue = message
    processBlockHeaders(peer, message)
  } else {
    //no new headers to process, schedule to ask again in future, we are at the top of chain
    scheduleResume()
  }

  private def processBlockHeaders(peer: Peer, headers: Seq[BlockHeader]): Unit =
    ledger.resolveBranch(headers) match {
      case NewBranch(oldBranch) =>
        // TODO: should we postpone handling of old blocks until we receive block bodies for the new branch?
        val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
        pendingTransactionsManager ! PendingTransactionsManager.AddTransactions(transactionsToAdd.toList)

        val hashes = headers.take(blockBodiesPerRequest).map(_.hash)
        requestBlockBodies(peer, GetBlockBodies(hashes))

        //add first block from branch as ommer
        oldBranch.headOption.foreach { h => ommersPool ! AddOmmers(h.header) }

      case OldBranch =>
        //add first block from branch as ommer
        headersQueue.headOption.foreach { h => ommersPool ! AddOmmers(h) }
        scheduleResume()

      case UnknownBranch =>
        if ((headersQueue.length - 1) / branchResolutionBatchSize >= branchResolutionMaxRequests) {
          log.debug("fail to resolve branch, branch too long, it may indicate malicious peer")
          resumeWithDifferentPeer(peer)
        } else {
          val request = GetBlockHeaders(Right(headersQueue.head.parentHash), branchResolutionBatchSize, skip = 0, reverse = true)
          requestBlockHeaders(peer, request)
          resolvingBranches = true
        }

      case InvalidBranch =>
        log.debug("Got block header that does not have parent")
        resumeWithDifferentPeer(peer)
    }

  private def requestBlockHeaders(peer: Peer, msg: GetBlockHeaders): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetBlockHeaders, BlockHeaders](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = BlockHeaders.code)))
  }

  private def requestBlockBodies(peer: Peer, msg: GetBlockBodies): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetBlockBodies, BlockBodies](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = BlockBodies.code)))
  }

  private def handleBlockBodies(peer: Peer, m: Seq[BlockBody]) = {
    if (m.nonEmpty && headersQueue.nonEmpty) {
      val blocks = headersQueue.zip(m).map{ case (header, body) => Block(header, body) }

      blockchain.getBlockHeaderByHash(blocks.head.header.parentHash)
        .flatMap(b => blockchain.getTotalDifficultyByHash(b.hash)) match {
        case Some(blockParentTd) =>
          val (newBlocks, errorOpt) = processBlocks(blocks)

          if (newBlocks.nonEmpty) {
            broadcastBlocks(newBlocks, handshakedPeers)
            log.debug(s"got new blocks up till block: ${newBlocks.last.block.header.number} " +
              s"with hash ${Hex.toHexString(newBlocks.last.block.header.hash.toArray[Byte])}")
          }

          errorOpt match {
            case Some(error) =>
              val numberBlockFailed = blocks.head.header.number + newBlocks.length
              resumeWithDifferentPeer(peer, reason = s"a block execution error: ${error.toString}, in block $numberBlockFailed")
            case None =>
              headersQueue = headersQueue.drop(blocks.length)
              if (headersQueue.nonEmpty) {
                val hashes = headersQueue.take(blockBodiesPerRequest).map(_.hash)
                requestBlockBodies(peer, GetBlockBodies(hashes))
              } else {
                context.self ! ResumeRegularSync
              }
          }
        case None =>
          //TODO: Investigate if we can recover from this error (EC-165)
          val parentHash = Hex.toHexString(blocks.head.header.parentHash.toArray)
          throw new IllegalStateException(s"No total difficulty for the latest block with number ${blocks.head.header.number - 1} (and hash $parentHash)")
      }

    } else {
      //we got empty response for bodies from peer but we got block headers earlier
      resumeWithDifferentPeer(peer)
    }
  }

  /**
    * Inserts and executes all the blocks, up to the point to which one of them fails (or we run out of blocks).
    * If the execution of any block were to fail, newBlocks only contains the NewBlock msgs for all the blocks executed before it,
    * and only the blocks successfully executed are inserted into the blockchain.
    *
    * @param blocks to execute
    * @param newBlocks which, after adding the corresponding NewBlock msg for blocks, will be broadcasted
    * @return list of NewBlocks to broadcast (one per block successfully executed) and an error if one happened during execution
    */
  @tailrec
  private def processBlocks(blocks: Seq[Block], newBlocks: Seq[NewBlock] = Nil)
  : (Seq[NewBlock], Option[BlockImportFailure]) = blocks match {
    case Nil =>
      newBlocks -> None

    case Seq(block, otherBlocks@_*) =>
      ledger.importBlock(block) match {
        case BlockImported(_, td, topOfChain) =>
          if (topOfChain) {
            //FIXME: what about a deeper than 1 level chain re-org? Try to return a switched branch in `BlockImported`
            pendingTransactionsManager ! PendingTransactionsManager.RemoveTransactions(block.body.transactionList)
            ommersPool ! new RemoveOmmers((block.header +: block.body.uncleNodesList).toList)
          }

          // FIXME: again, the switched branch...
          val updatedNewBlocks = if (topOfChain) newBlocks :+ NewBlock(block, td) else newBlocks

          processBlocks(otherBlocks, newBlocks :+ NewBlock(block, td))

        case error: BlockImportFailure =>
          newBlocks -> Option(error)
      }
  }

  private def scheduleResume() = {
    headersQueue = Nil
    scheduler.scheduleOnce(checkForNewBlockInterval, self, ResumeRegularSync)
  }

  private def resumeWithDifferentPeer(currentPeer: Peer, reason: String = "error in response") = {
    blacklist(currentPeer.id, blacklistDuration, reason)
    headersQueue = Nil
    context.self ! ResumeRegularSync
  }

  private def bestPeer: Option[Peer] = {
    val peersToUse = peersToDownloadFrom
      .collect {
        case (ref, PeerInfo(_, totalDifficulty, true, _)) => (ref, totalDifficulty)
      }

    if (peersToUse.nonEmpty) Some(peersToUse.maxBy { case (_, td) => td }._1)
    else None
  }

}

object NewRegularSync {
  // scalastyle:off parameter.number
  def props(appStateStorage: AppStateStorage, blockchain: Blockchain, validators: Validators,
      etcPeerManager: ActorRef, peerEventBus: ActorRef, ommersPool: ActorRef, pendingTransactionsManager: ActorRef, ledger: Ledger,
      syncConfig: SyncConfig, scheduler: Scheduler): Props =
    Props(new NewRegularSync(appStateStorage, blockchain, validators, etcPeerManager, peerEventBus, ommersPool, pendingTransactionsManager,
      ledger, syncConfig, scheduler))

  private case object ResumeRegularSync
  private case class ResolveBranch(peer: ActorRef)
  private case object PrintStatus

  case object Start
  case class MinedBlock(block: Block)
}