package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.network.p2p.messages.PV62.BlockHeaderImplicits._
import io.iohk.ethereum.rlp.{RLPList, encode => rlpEncode}
import org.spongycastle.util.encoders.Hex
import io.iohk.ethereum.validators.BlockHeaderValidatorImpl.{MinGasLimit => minGasLimit, MaxGasLimit => maxGasLimit, MaxExtraDataSize}
import eu.timepit.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.string._

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
    unixTimestamp: Long,//it's validating in BlockHeaderValidator
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
  
  //Refinement
  type Hash256 = MatchesRegex[W.`"[0-9a-fA-F]{64}"`.T]
  type Hash160 = MatchesRegex[W.`"[0-9a-fA-F]{40}"`.T]
  type Hash64 = MatchesRegex[W.`"[0-9a-fA-F]{16}"`.T]  

  require(validateConstructor == Right(BHValid))    
  
  def validateConstructor(): Either[BHInvalid, BHValid] = {
    for {      
      _ <- booleanToMap(valref(parentHash, 256)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(ommersHash, 256)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(beneficiary, 160)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(stateRoot, 256)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(transactionsRoot, 256)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(receiptsRoot, 256)) //Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(difficulty >=0)//Based on validation stated in section 4.4.2 of http://paper.gavwood.com/
      _ <- booleanToMap(number >=0)//Based on validation stated in section 4.4.2 of http://paper.gavwood.com/
      _ <- booleanToMap(gasLimit >= minGasLimit && gasLimit <= maxGasLimit)//Based on validation stated in section 
        //4.4.2 of http://paper.gavwood.com/
      _ <- booleanToMap(gasUsed >=0 && gasUsed <= gasLimit)//Based on validation stated in section 4.4.2 of http://paper.gavwood.com/
      _ <- booleanToMap(extraData.length <= MaxExtraDataSize)//Based on validation stated in section 4.4.2 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(mixHash, 256))//Based on stated in section 4.4 of http://paper.gavwood.com/
      _ <- booleanToMap(valref(nonce, 64))//Based on stated in section 4.4 of http://paper.gavwood.com/
    } yield BHValid
  }

  def valref(attr: ByteString, tkeccak: Int) : Boolean = {
    tkeccak match {
      case 256 => refineV[Hash256](Hex.toHexString(attr.toArray[Byte])).isRight
      case 160  => refineV[Hash160](Hex.toHexString(attr.toArray[Byte])).isRight
      case 64  => refineV[Hash64](Hex.toHexString(attr.toArray[Byte])).isRight
      case _ => throw new NoSuchMethodException
    }    
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
  def bEmpty64:  ByteString = ByteString(Hex.decode("0"*16))
}
