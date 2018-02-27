package io.iohk.ethereum.blockchain.sync

import akka.actor.ActorSystem
import akka.pattern._
import akka.testkit.TestActorRef
import akka.util.ByteString
import io.iohk.ethereum.NormalPatience
import io.iohk.ethereum.blockchain.sync.FastSync.SyncState
import io.iohk.ethereum.blockchain.sync.FastSyncStateStorageActor.GetStorage
import io.iohk.ethereum.db.dataSource.EphemDataSource
import io.iohk.ethereum.db.storage.FastSyncStateStorage
import io.iohk.ethereum.domain.BlockHeader
import org.scalatest.concurrent.Eventually
import org.scalatest.{AsyncFlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex

class FastSyncStateStorageActorSpec extends AsyncFlatSpec with Matchers with Eventually with NormalPatience {

  "FastSyncStateActor" should "eventually persist a newest state of a fast sync" in {

    val dataSource = EphemDataSource()
    implicit val system = ActorSystem("FastSyncStateActorSpec_System")
    val syncStateActor = TestActorRef(new FastSyncStateStorageActor)
    val maxN = 10

    val targetBlockHeader = BlockHeader(
      ByteString(Hex.decode("d882d5c210bab4cb7ef0b9f3dc2130cb680959afcd9a8f9bf83ee6f13e2f9da3"))
    , BlockHeader.bEmpty256, BlockHeader.bEmpty160, BlockHeader.bEmpty256, BlockHeader.bEmpty256,
      BlockHeader.bEmpty256, ByteString(""), 0, 0, 5000, 0, 0, ByteString(""), BlockHeader.bEmpty256, BlockHeader.bEmpty64)
    syncStateActor ! new FastSyncStateStorage(dataSource)
    (0 to maxN).foreach(n => syncStateActor ! SyncState(targetBlockHeader).copy(downloadedNodesCount = n))

    eventually {
      (syncStateActor ? GetStorage).mapTo[Option[SyncState]].map { syncState =>
        val expected = SyncState(targetBlockHeader).copy(downloadedNodesCount = maxN)
        syncState shouldEqual Some(expected)
      }
    }

  }

}
