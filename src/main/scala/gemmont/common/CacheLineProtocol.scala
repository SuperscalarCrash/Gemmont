package gemmont.common

import chisel3._

object CacheLineSource {
  val Instruction: UInt = 0.U(1.W)
  val Data: UInt = 1.U(1.W)
}

class LineReadReq(addressWidth: Int = 32, txnIdWidth: Int = 4) extends Bundle {
  require(addressWidth > 0 && txnIdWidth > 0)

  val source = UInt(1.W)
  val txnId = UInt(txnIdWidth.W)
  val lineAddress = UInt(addressWidth.W)
}

class LineReadResp(
    addressWidth: Int = 32,
    txnIdWidth: Int = 4,
    lineBytes: Int = 64
) extends Bundle {
  require(addressWidth > 0 && txnIdWidth > 0)
  require(lineBytes >= 4 && (lineBytes & (lineBytes - 1)) == 0)

  val source = UInt(1.W)
  val txnId = UInt(txnIdWidth.W)
  val lineAddress = UInt(addressWidth.W)
  val data = UInt((lineBytes * 8).W)
}

class LineWriteReq(
    addressWidth: Int = 32,
    txnIdWidth: Int = 4,
    lineBytes: Int = 64
) extends Bundle {
  require(addressWidth > 0 && txnIdWidth > 0)
  require(lineBytes >= 4 && (lineBytes & (lineBytes - 1)) == 0)

  val txnId = UInt(txnIdWidth.W)
  val lineAddress = UInt(addressWidth.W)
  val data = UInt((lineBytes * 8).W)
}

class LineWriteAck(txnIdWidth: Int = 4) extends Bundle {
  require(txnIdWidth > 0)

  val txnId = UInt(txnIdWidth.W)
}

class LinePrefetchReq(addressWidth: Int = 32) extends Bundle {
  require(addressWidth > 0)

  val lineAddress = UInt(addressWidth.W)
}

class LinePrefetchResp(addressWidth: Int = 32, lineBytes: Int = 64) extends Bundle {
  require(addressWidth > 0)
  require(lineBytes >= 4 && (lineBytes & (lineBytes - 1)) == 0)

  val lineAddress = UInt(addressWidth.W)
  val hit = Bool()
  val data = UInt((lineBytes * 8).W)
}
