package gemmont.backend.issue

import chisel3._
import gemmont.DesignParams
import gemmont.frontend.PredictionInfo
import gemmont.isa._

class IssueOperand extends Bundle {
  val ready = Bool()
  val physical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class IntegerIssueMicroOp extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val prediction = new PredictionInfo
  val functionalUnit = FunctionalUnit()
  val useRj = Bool()
  val useRk = Bool()
  val useRd = Bool()
  val writeRegister = Bool()
  val immediateExtend = ExtendOp()
  val aluOperation = AluOp()
  val compareOperation = CompareOp()
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val writeCsr = Bool()
  val readCsr = Bool()
  val readTimerLow = Bool()
  val readTimerHigh = Bool()
  val readTimerId = Bool()
  val tlbOperation = TlbOperation()
}

class MulDivIssueMicroOp extends Bundle {
  val functionalUnit = FunctionalUnit()
  val writeRegister = Bool()
  val signed = Bool()
}

class MemoryIssueMicroOp extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val writeRegister = Bool()
  val operation = LoadStoreOp()
  val isLoad = Bool()
  val isStore = Bool()
  val isBarrier = Bool()
  val isLoadLinked = Bool()
  val isStoreConditional = Bool()
  val immediate = UInt(32.W)
  val cacheOperation = CacheOperation()
  val cacheSelect = CacheSelect()
}

class TypedIssueSlot[T <: Data](gen: T) extends Bundle {
  val functionalUnit = FunctionalUnit()
  val operands = Vec(2, new IssueOperand)
  val writeRegister = Bool()
  val writePhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
  val robIndex = UInt(5.W)
  val payload = gen.cloneType
}
