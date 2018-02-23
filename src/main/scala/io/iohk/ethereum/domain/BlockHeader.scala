package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.rlp.{RLPList, encode => rlpEncode}
import org.spongycastle.util.encoders.Hex
import io.iohk.ethereum.validators.BlockHeaderValidatorImpl.{MinGasLimit => minGasLimit, MaxGasLimit => maxGasLimit}

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
  //Preconditions based on validations stated in section 4.4.2 of http://paper.gavwood.com/  
  require(validateConstructor == Right(BHValid))  
 
  def validateConstructor(): Either[BHInvalid, BHValid] = {
    for {      
      _ <- booleanToMap(parentHash.length == 32)
      _ <- booleanToMap(ommersHash.length == 32)
      _ <- booleanToMap(beneficiary.length == 20)
      _ <- booleanToMap(stateRoot.length == 32)
      _ <- booleanToMap(transactionsRoot.length == 32)
      _ <- booleanToMap(receiptsRoot.length == 32)
      _ <- booleanToMap(difficulty >=0)// Based on validation domain/DifficultyCalculator
      _ <- booleanToMap(number >=0)//Based on validation validators/BlockHeaderValidator
      _ <- booleanToMap(gasLimit >= minGasLimit && gasLimit <= maxGasLimit)//Based on validation validators/BlockHeaderValidator
    } yield BHValid
  }  

  def booleanToMap(eval: Boolean): Either[BHInvalid, BHValid] = {
    if (eval == true)
      Right(BHValid)
    else
      Left(BHInvalid)
  }

  /**
    * calculates blockHash for given block header
    * @return - hash that can be used to get block bodies / receipts
    */
  lazy val hash: ByteString = ByteString(kec256(this.toBytes: Array[Byte]))

  lazy val hashAsHexString: String = Hex.toHexString(hash.toArray)

  def idTag: String =
    s"$number: $hashAsHexString"  
}

sealed trait BHInvalid
sealed trait BHValid
case object BHValid extends BHValid
case object BHInvalid extends BHInvalid

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
