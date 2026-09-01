package gemmont.cache

import chisel3._
import gemmont.isa.{CacheOperation, CacheSelect, LoadStoreOp}

class CacheMaintenanceRequest extends Bundle {
  val address = UInt(32.W)
  val select = CacheSelect()
  val operation = CacheOperation()
}

class InstructionCacheRequest extends Bundle {
  val virtualAddress = UInt(32.W)
  val physicalAddress = UInt(32.W)
}

class InstructionCacheResponse(fetchWidth: Int) extends Bundle {
  val pc = UInt(32.W)
  val words = Vec(fetchWidth, UInt(32.W))
  val validMask = UInt(fetchWidth.W)
}

class DataCacheRequest extends Bundle {
  val physicalAddress = UInt(32.W)
  val write = Bool()
  val writeData = UInt(32.W)
  val byteEnable = UInt(4.W)
}

class DataCacheResponse extends Bundle {
  val readData = UInt(32.W)
}

class UncachedRequest extends Bundle {
  val address = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val byteEnable = UInt(4.W)
  val operation = LoadStoreOp()
}
