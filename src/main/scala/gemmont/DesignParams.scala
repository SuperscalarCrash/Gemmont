package gemmont

import chisel3.util.log2Ceil

object DesignParams {
  val addressWidth = 32
  val dataWidth = 32
  val axiIdWidth = 4
  val fetchWidth = 4
  val issueWidth = 3
  val cacheLineBytes = 64
  val cacheWays = 2
  val instructionCacheSets = 64
  val dataCacheSets = 64
  val l2DataCacheSets = 512
  val architecturalRegisterCount = 32
  val physicalRegisterCount = 63
  val robDepth = 32
  val retireWidth = 3
  val storeBufferDepth = 8
  val tlbEntries = 32
  val asidWidth = 10
  val resetPc = BigInt("1c000000", 16)

  val architecturalRegisterAddressWidth = log2Ceil(architecturalRegisterCount)
  val physicalRegisterAddressWidth = log2Ceil(physicalRegisterCount)
  val robAddressWidth = log2Ceil(robDepth)
  val tlbIndexWidth = log2Ceil(tlbEntries)
}
