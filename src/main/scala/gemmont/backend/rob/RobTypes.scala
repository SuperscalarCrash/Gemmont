package gemmont.backend.rob

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.backend.ArchitecturalCommit
import gemmont.decode.ExceptionPayload
import gemmont.frontend.{PredictionInfo, PredictionRecovery, PredictorUpdate}
import gemmont.cache.CacheMaintenanceRequest
import gemmont.isa.{LoadStoreOp, TlbOperation}

class RobMicroOp extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val prediction = new PredictionInfo
  val predictionRecovery = new PredictionRecovery
  val writebackAddress = UInt(5.W)
  val writeRegister = Bool()
  val isLoad = Bool()
  val isStore = Bool()
  val loadStoreOperation = LoadStoreOp()
  val readTimerLow = Bool()
  val readTimerHigh = Bool()
  val readTimerId = Bool()
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val branchLike = Bool()
  val tlbOperation = TlbOperation()
  val operateCache = Bool()
  val writeCsr = Bool()
  val readCsr = Bool()
  val isWait = Bool()
  val isLoadLinked = Bool()
  val isStoreConditional = Bool()
  val uniqueRetire = Bool()
  val isErtn = Bool()
  val flushState = Bool()
}

class RobRename extends Bundle {
  val previousPhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
  val physical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class RobInfo extends Bundle {
  val microOp = new RobMicroOp
  val rename = new RobRename
  val frontendException = Bool()
}

class RobState extends Bundle {
  val complete = Bool()
  val exception = Valid(new ExceptionPayloadWithAddress)
  val mispredict = Bool()
  val actualTaken = Bool()
  val lsuUncached = Bool()
  val integerResult = UInt(32.W)
  val isCounter = Bool()
  val counterValue = UInt(64.W)
  val csrStatusRead = Bool()
  val csrReadData = UInt(32.W)
  val isLoad = Bool()
  val isStore = Bool()
  val isLoadLinked = Bool()
  val isStoreConditional = Bool()
  val loadStoreOperation = LoadStoreOp()
  val virtualAddress = UInt(32.W)
  val physicalAddress = UInt(32.W)
  val storeData = UInt(32.W)
}

class ExceptionPayloadWithAddress extends ExceptionPayload {
  val badAddress = UInt(32.W)
}

class RobEntry extends Bundle {
  val info = new RobInfo
  val state = new RobState
}

class RobAllocate extends Bundle {
  val info = new RobInfo
  val initialState = new RobState
}

class RobCompletion extends Bundle {
  val index = UInt(5.W)
  val state = new RobState
}

class CommitOutputs extends Bundle {
  val retireMask = UInt(3.W)
  val flush = Bool()
  val registerFlush = Bool()
  val recoverPrf = Bool()
  val hasException = Bool()
  val exception = Valid(new ExceptionPayloadWithAddress)
  val exceptionPc = UInt(32.W)
  val backendRedirect = Valid(UInt(32.W))
  val architecturalCommits = Vec(3, Valid(new ArchitecturalCommit))
  val predictorUpdate = Valid(new PredictorUpdate)
  val csrWrite = Valid(new Bundle {
    val address = UInt(14.W)
    val data = UInt(32.W)
  })
  val cacheMaintenance = Valid(new CacheMaintenanceRequest)
  val tlbOperation = TlbOperation()
  val tlbInvalidateAsid = UInt(DesignParams.asidWidth.W)
  val tlbInvalidateVppn = UInt(19.W)
  val uncachedMask = UInt(3.W)
  val commitStore = Bool()
  val setLoadLinked = Bool()
  val clearLoadLinked = Bool()
  val enterWait = Bool()
  val ertn = Bool()
}
