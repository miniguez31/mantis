package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.rlp.{RLPList, encode => rlpEncode}
import org.spongycastle.util.encoders.Hex

case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString) {

  override def toString: String = {
    s"""BlockHeader {
       |parentHash: ${Hex.toHexString(parentHash.toArray[Byte])}
       |ommersHash: ${Hex.toHexString(ommersHash.toArray[Byte])}
       |beneficiary: ${Hex.toHexString(beneficiary.toArray[Byte])}
       |stateRoot: ${Hex.toHexString(stateRoot.toArray[Byte])}
       |transactionsRoot: ${Hex.toHexString(transactionsRoot.toArray[Byte])}
       |receiptsRoot: ${Hex.toHexString(receiptsRoot.toArray[Byte])}
       |logsBloom: ${Hex.toHexString(logsBloom.toArray[Byte])}
       |difficulty: $difficulty,
       |number: $number,
       |gasLimit: $gasLimit,
       |gasUsed: $gasUsed,
       |unixTimestamp: $unixTimestamp,
       |extraData: ${Hex.toHexString(extraData.toArray[Byte])}
       |mixHash: ${Hex.toHexString(mixHash.toArray[Byte])}
       |nonce: ${Hex.toHexString(nonce.toArray[Byte])}
       |}""".stripMargin
  }
  //Preconditions
  assert(Hex.toHexString(parentHash.toArray[Byte]).length == 64 , "Bad value for parentHash "+Hex.toHexString(parentHash.toArray[Byte])+" "+Hex.toHexString(parentHash.toArray[Byte]).length)
  assert(Hex.toHexString(ommersHash.toArray[Byte]).length == 64 , "Bad value for ommersHash "+Hex.toHexString(ommersHash.toArray[Byte])+" "+Hex.toHexString(ommersHash.toArray[Byte]).length)
  assert(Hex.toHexString(beneficiary.toArray[Byte]).length == 40 , "Bad value for beneficiary "+Hex.toHexString(beneficiary.toArray[Byte])+" "+Hex.toHexString(beneficiary.toArray[Byte]).length)
  assert(Hex.toHexString(stateRoot.toArray[Byte]).length == 64 , "Bad value for stateRoot "+Hex.toHexString(stateRoot.toArray[Byte])+" "+Hex.toHexString(stateRoot.toArray[Byte]).length)
  assert(Hex.toHexString(transactionsRoot.toArray[Byte]).length == 64 , "Bad value for transactionsRoot "+Hex.toHexString(transactionsRoot.toArray[Byte])+" "+Hex.toHexString(transactionsRoot.toArray[Byte]).length)
  assert(Hex.toHexString(receiptsRoot.toArray[Byte]).length == 64 , "Bad value for receiptsRoot "+Hex.toHexString(receiptsRoot.toArray[Byte])+" "+Hex.toHexString(receiptsRoot.toArray[Byte]).length)
  
  /**
    * calculates blockHash for given block header
    * @return - hash that can be used to get block bodies / receipts
    */
  lazy val hash: ByteString = ByteString(kec256(this.toBytes: Array[Byte]))

  lazy val hashAsHexString: String = Hex.toHexString(hash.toArray)

  def idTag: String =
    s"$number: $hashAsHexString"  
}

object BlockHeader {

  def getEncodedWithoutNonce(blockHeader: BlockHeader): Array[Byte] = {
    val rlpEncoded = blockHeader.toRLPEncodable match {
      case rlpList: RLPList => RLPList(rlpList.items.dropRight(2): _*)
      case _ => throw new Exception("BlockHeader cannot be encoded without nonce and mixHash")
    }
    rlpEncode(rlpEncoded)
  }
  //BlockHeader empties
  def bEmpty256: ByteString = ByteString(Hex.decode("0"*64))
  def bEmpty160: ByteString = ByteString(Hex.decode("0"*40))
}
