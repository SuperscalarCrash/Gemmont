package gemmont.core

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.{DesignParams, GemmontConfig}
import gemmont.backend.{PhysicalRegisterFile, PhysicalWrite}
import gemmont.backend.execute.ExecutionCluster
import gemmont.backend.issue.IssueDispatch
import gemmont.backend.rob.{CommitUnit, ExceptionPayloadWithAddress, ReorderBuffer, RobEntry}
import gemmont.common.{
  Axi4Master,
  LinePrefetchReq,
  LinePrefetchResp,
  LineReadReq,
  LineReadResp,
  LineWriteAck,
  LineWriteReq
}
import gemmont.decode.DecodeRename
import gemmont.debug.CoreProfileObservation
import gemmont.frontend.Frontend
import gemmont.isa.LoongArch
import gemmont.lsu.LoadStoreUnit
import gemmont.privilege.{PrivilegeController, PrivilegeDebugState, TlbCsrState}

class GemmontCoreRetireTrace extends Bundle {
  val fire = UInt(3.W)
  val entries = Vec(3, Valid(new RobEntry))
  val writeData = Vec(3, UInt(32.W))
}

class GemmontCoreDebug extends Bundle {
  val exception = Valid(new ExceptionPayloadWithAddress)
  val ertn = Bool()
  val retire = new GemmontCoreRetireTrace
  val physicalRegisters = Vec(DesignParams.physicalRegisterCount, UInt(32.W))
  val architecturalRat = Vec(
    DesignParams.architecturalRegisterCount - 1,
    UInt(DesignParams.physicalRegisterAddressWidth.W)
  )
  val privilege = new PrivilegeDebugState
  val tlb = new TlbCsrState
  val tlbVictimIndex = UInt(DesignParams.tlbIndexWidth.W)
}

class GemmontCore(
    config: GemmontConfig = GemmontConfig(),
    phtInitializationFile: String = "src/main/resources/pht-init.hex",
    btbInitializationFile: String = "src/main/resources/btb-init.hex"
) extends Module {
  val io = IO(new Bundle {
    val externalInterrupt = Input(UInt(8.W))
    val instructionLineReadReq = Decoupled(
      new LineReadReq(config.addressWidth, config.axiIdWidth)
    )
    val instructionLineReadResp = Flipped(
      Decoupled(
        new LineReadResp(config.addressWidth, config.axiIdWidth, config.frontend.icache.lineBytes)
      )
    )
    val dataLineReadReq = Decoupled(new LineReadReq(config.addressWidth, config.axiIdWidth))
    val dataLineReadResp = Flipped(
      Decoupled(new LineReadResp(config.addressWidth, config.axiIdWidth, config.dcache.lineBytes))
    )
    val dataLinePrefetchReq = Decoupled(new LinePrefetchReq(config.addressWidth))
    val dataLinePrefetchResp = Flipped(
      Decoupled(new LinePrefetchResp(config.addressWidth, config.dcache.lineBytes))
    )
    val dataLineWriteReq = Decoupled(
      new LineWriteReq(config.addressWidth, config.axiIdWidth, config.dcache.lineBytes)
    )
    val dataLineWriteAck = Flipped(Decoupled(new LineWriteAck(config.axiIdWidth)))
    val uncachedAxi = new Axi4Master(config.addressWidth, config.dataWidth, config.axiIdWidth)
    val debug = Output(new GemmontCoreDebug)
    val profile = Output(new CoreProfileObservation)
  })

  val frontend = Module(
    new Frontend(config.frontend, config.tlb.entries, phtInitializationFile, btbInitializationFile)
  )
  val decodeRename = Module(new DecodeRename)
  val issueDispatch = Module(new IssueDispatch)
  val registerFile = Module(
    new PhysicalRegisterFile(
      readPorts = 10,
      busyReadPorts = 6,
      writePorts = 5,
      clearBusyPorts = 5,
      allocationPorts = 3
    )
  )
  val reorderBuffer = Module(new ReorderBuffer)
  val commit = Module(new CommitUnit)
  val execution = Module(new ExecutionCluster)
  val loadStore = Module(new LoadStoreUnit(config.tlb.entries, config.dcache))
  val privilege = Module(new PrivilegeController(config.tlb))

  io.instructionLineReadReq <> frontend.io.lineReadReq
  frontend.io.lineReadResp <> io.instructionLineReadResp
  io.dataLineReadReq <> loadStore.io.dataLineReadReq
  loadStore.io.dataLineReadResp <> io.dataLineReadResp
  io.dataLinePrefetchReq <> loadStore.io.dataLinePrefetchReq
  loadStore.io.dataLinePrefetchResp <> io.dataLinePrefetchResp
  io.dataLineWriteReq <> loadStore.io.dataLineWriteReq
  loadStore.io.dataLineWriteAck <> io.dataLineWriteAck
  io.uncachedAxi <> loadStore.io.uncachedAxi

  for (lane <- 0 until 3) {
    decodeRename.io.fetch(lane) <> frontend.io.decode(lane)
    reorderBuffer.io.allocate(lane) <> decodeRename.io.robAllocate(lane)
    decodeRename.io.robAllocatedIndex(lane) := reorderBuffer.io.allocatedIndex(lane)
    decodeRename.io.commits(lane) := commit.io.output.architecturalCommits(lane)
    registerFile.io.allocate(lane) := decodeRename.io.physicalAllocations(lane)
  }
  issueDispatch.io.dispatch <> decodeRename.io.dispatch
  decodeRename.io.privilegeLevel := privilege.io.translationControl.privilege

  registerFile.io.busyAddress := issueDispatch.io.busyAddress
  issueDispatch.io.busy := registerFile.io.busy
  execution.io.integerIssue := issueDispatch.io.integer
  execution.io.mulDivIssue := issueDispatch.io.mulDiv
  val speculativeReadStall =
    RegNext(loadStore.io.speculativeWakeupFailed, false.B)

  issueDispatch.io.integerIssueFire := !speculativeReadStall
  issueDispatch.io.mulDivIssueFire := execution.io.mulDivIssueFire
  loadStore.io.issue := issueDispatch.io.memory
  issueDispatch.io.memoryIssueFire := loadStore.io.issueFire

  for (port <- 0 until 8) {
    registerFile.io.readAddress(port) := execution.io.readAddress(port)
    execution.io.readData(port) := registerFile.io.readData(port)
  }
  for (port <- 0 until 2) {
    registerFile.io.readAddress(8 + port) := loadStore.io.readAddress(port)
    loadStore.io.readData(port) := registerFile.io.readData(8 + port)
  }

  val physicalWrites = Wire(Vec(5, Valid(new PhysicalWrite)))
  val clearBusy = Wire(
    Vec(5, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
  )
  for (port <- 0 until 4) {
    physicalWrites(port) := execution.io.write(port)
    clearBusy(port) := execution.io.clearBusy(port)
    reorderBuffer.io.complete(port) := execution.io.completion(port)
  }
  physicalWrites(4) := loadStore.io.write
  registerFile.io.lsuBypassResultBit2ForMdu := loadStore.io.bypassResultBit2ForMdu
  registerFile.io.lsuBypassResultBit30ForMdu :=
    loadStore.io.bypassResultBit30ForMdu

  val registeredLoadStoreWakeup = RegInit(
    0.U.asTypeOf(Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
  )

  val registeredLoadStoreWakeupForGlobal = RegInit(
    0.U.asTypeOf(Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
  )
  val registeredLoadStoreWakeupForSelection = RegInit(
    0.U.asTypeOf(Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
  )
  val registeredLoadStoreWakeupForIntegerSelection = Seq.tabulate(3, 7) { (lane, slot) =>
    RegInit(
      0.U.asTypeOf(Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
      .suggestName(s"registeredLoadStoreWakeupForIntegerSelectionLane${lane}Slot$slot")
  }
  (Seq(
    registeredLoadStoreWakeupForGlobal,
    registeredLoadStoreWakeupForSelection
  ) ++ registeredLoadStoreWakeupForIntegerSelection.flatten).foreach { copy =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(copy.toTarget, "dont_touch = \"yes\"")
    })
  }
  registeredLoadStoreWakeup := loadStore.io.clearBusy
  registeredLoadStoreWakeupForGlobal := loadStore.io.clearBusy
  registeredLoadStoreWakeupForSelection := loadStore.io.clearBusy
  registeredLoadStoreWakeupForIntegerSelection.flatten.foreach(_ := loadStore.io.clearBusy)
  when(commit.io.output.flush || commit.io.output.registerFlush) {
    registeredLoadStoreWakeup.valid := false.B
    registeredLoadStoreWakeupForGlobal.valid := false.B
    registeredLoadStoreWakeupForSelection.valid := false.B
    registeredLoadStoreWakeupForIntegerSelection.flatten.foreach(_.valid := false.B)
  }
  clearBusy(4) := registeredLoadStoreWakeup
  reorderBuffer.io.complete(4) := loadStore.io.completion
  registerFile.io.write := physicalWrites
  registerFile.io.clearBusy := clearBusy
  val issueWakeup = WireInit(clearBusy)
  issueWakeup(4) := registeredLoadStoreWakeupForGlobal
  issueDispatch.io.globalWakeup := issueWakeup

  issueDispatch.io.sameCycleWakeup := registeredLoadStoreWakeupForSelection
  issueDispatch.io.integerSameCycleWakeup := VecInit(
    registeredLoadStoreWakeupForIntegerSelection.map(copies => VecInit(copies))
  )
  execution.io.bypassWrite := physicalWrites
  execution.io.lsuBypassResultBit0ForLane0 := loadStore.io.bypassResultBit0ForLane0
  execution.io.lsuBypassResultBit1ForLane0 := loadStore.io.bypassResultBit1ForLane0
  execution.io.lsuBypassResultBit2ForLane1 := loadStore.io.bypassResultBit2
  execution.io.lsuBypassResultBit3ForLane1 := loadStore.io.bypassResultBit3ForLane1
  execution.io.lsuBypassResultBit7ForLane0 := loadStore.io.bypassResultBit7ForLane0

  execution.io.stallRead := speculativeReadStall
  loadStore.io.stallRead := false.B

  for (lane <- 0 until 3) {
    commit.io.entries(lane).valid := reorderBuffer.io.retire(lane).valid
    commit.io.entries(lane).bits := reorderBuffer.io.retire(lane).bits
    reorderBuffer.io.retire(lane).ready := commit.io.output.retireMask(lane)
  }

  val retirementInterruptPending =
    RegNext(privilege.io.interruptPending, false.B)
  commit.io.interruptPending := retirementInterruptPending
  commit.io.inhibitInterrupt := loadStore.io.inhibitInterrupt
  commit.io.uncachedLoadCompleted := loadStore.io.uncachedLoadCompleted
  reorderBuffer.io.flush := commit.io.output.flush
  decodeRename.io.flush := commit.io.output.flush
  decodeRename.io.recoverRename := commit.io.output.recoverPrf

  val backendFlush = commit.io.output.flush || commit.io.output.registerFlush
  issueDispatch.io.flush := backendFlush
  execution.io.flush := backendFlush
  loadStore.io.flush := backendFlush

  privilege.io.externalInterrupt := io.externalInterrupt
  privilege.io.csrReadAddress := execution.io.csrReadAddress
  execution.io.csrReadData := privilege.io.csrReadData
  privilege.io.csrWrite.valid := commit.io.output.csrWrite.valid
  privilege.io.csrWrite.bits.address := commit.io.output.csrWrite.bits.address
  privilege.io.csrWrite.bits.data := commit.io.output.csrWrite.bits.data
  privilege.io.exception.valid := commit.io.output.exception.valid
  privilege.io.exception.bits.code := commit.io.output.exception.bits.code
  privilege.io.exception.bits.subcode := commit.io.output.exception.bits.subcode
  privilege.io.exception.bits.badAddress := commit.io.output.exception.bits.badAddress
  privilege.io.exception.bits.pc := commit.io.output.exceptionPc
  privilege.io.exception.bits.isTlbRefill := commit.io.output.exception.bits.isTlbRefill
  privilege.io.ertn := commit.io.output.ertn
  privilege.io.setLoadLinked := commit.io.output.setLoadLinked
  privilege.io.clearLoadLinked := commit.io.output.clearLoadLinked
  privilege.io.enterWait := commit.io.output.enterWait
  privilege.io.tlbOperation := commit.io.output.tlbOperation
  privilege.io.tlbInvalidateAsid := commit.io.output.tlbInvalidateAsid
  privilege.io.tlbInvalidateVirtualPageNumber := commit.io.output.tlbInvalidateVppn

  val cycleTimer = RegInit(0.U(64.W))
  cycleTimer := cycleTimer + 1.U
  execution.io.timerValue := cycleTimer
  execution.io.timerId := privilege.io.debug.tid

  frontend.io.predictorUpdate := commit.io.output.predictorUpdate
  for (lane <- 0 until 3) {
    val retired = reorderBuffer.io.retire(lane)
    frontend.io.committedBranches(lane).valid :=
      commit.io.output.retireMask(lane) && !commit.io.output.exception.valid &&
        retired.bits.info.microOp.isBranch
    frontend.io.committedBranches(lane).bits.pc := retired.bits.info.microOp.pc
    frontend.io.committedBranches(lane).bits.taken := retired.bits.state.actualTaken
  }
  frontend.io.waiting := privilege.io.waiting
  val frontendTranslationControl = WireInit(privilege.io.translationControl)
  frontendTranslationControl.privilege := privilege.io.frontendPrivilege
  frontendTranslationControl.directMap0 := privilege.io.frontendDirectMap0
  frontendTranslationControl.directMap1 := privilege.io.frontendDirectMap1
  frontend.io.translationControl := frontendTranslationControl
  frontend.io.tlb := privilege.io.tlbEntries
  frontend.io.maintenance := commit.io.output.cacheMaintenance
  loadStore.io.translationControl := privilege.io.translationControl
  loadStore.io.tlb := privilege.io.tlbEntries
  loadStore.io.robHeadIndex := reorderBuffer.io.popPointer
  loadStore.io.commitStore := commit.io.output.commitStore
  loadStore.io.loadLinked := privilege.io.loadLinked

  val translationChangingCsrWrite = commit.io.output.csrWrite.valid && Seq(
    LoongArch.CsrAddress.Crmd,
    LoongArch.CsrAddress.Dmw0,
    LoongArch.CsrAddress.Dmw1
  ).map(address => commit.io.output.csrWrite.bits.address === address.U).reduce(_ || _)
  val postRetirementRedirect = WireDefault(privilege.io.redirect)
  when(translationChangingCsrWrite) {
    postRetirementRedirect.valid := true.B
    postRetirementRedirect.bits := commit.io.output.backendRedirect.bits
    assert(
      commit.io.output.backendRedirect.valid,
      "translation-changing CSR write must produce a linear recovery"
    )
    assert(
      !privilege.io.redirect.valid,
      "privilege and CSR post-retirement redirects must be mutually exclusive"
    )
  }
  val delayedPostRetirementRedirectValid = RegNext(postRetirementRedirect.valid, false.B)
  val delayedPostRetirementRedirectTarget =
    RegEnable(postRetirementRedirect.bits, postRetirementRedirect.valid)

  frontend.io.flush := commit.io.output.flush
  val frontendRedirect = WireDefault(commit.io.output.backendRedirect)
  when(translationChangingCsrWrite) {
    frontendRedirect.valid := false.B
  }
  when(delayedPostRetirementRedirectValid) {
    frontendRedirect.valid := true.B
    frontendRedirect.bits := delayedPostRetirementRedirectTarget
    assert(
      !commit.io.output.backendRedirect.valid && !postRetirementRedirect.valid,
      "delayed post-retirement redirect must not collide with a newer recovery"
    )
  }
  frontend.io.backendRedirect := frontendRedirect

  io.debug.exception := commit.io.output.exception
  io.debug.ertn := commit.io.output.ertn
  io.debug.retire.fire := commit.io.output.retireMask
  for (lane <- 0 until 3) {
    io.debug.retire.entries(lane).valid := reorderBuffer.io.retire(lane).valid
    io.debug.retire.entries(lane).bits := reorderBuffer.io.retire(lane).bits
    val physical = reorderBuffer.io.retire(lane).bits.info.rename.physical

    io.debug.retire.writeData(lane) := Mux(
      physical >= 62.U,
      registerFile.io.debugRegisters(62),
      registerFile.io.debugRegisters(physical)
    )
  }
  io.debug.physicalRegisters := registerFile.io.debugRegisters
  io.debug.architecturalRat := decodeRename.io.architecturalRat
  io.debug.privilege := privilege.io.debug
  io.debug.tlb := privilege.io.tlbState
  io.debug.tlbVictimIndex := privilege.io.tlbVictimIndex

  val profileRetireValid = VecInit(
    (0 until 3).map { lane =>
      commit.io.output.retireMask(lane) && !commit.io.output.exception.valid
    }
  )
  val profileRetirePc = VecInit(
    (0 until 3).map(lane => reorderBuffer.io.retire(lane).bits.info.microOp.pc)
  ).asUInt
  val profileRetireCounter = VecInit(
    (0 until 3).map { lane =>
      val microOp = reorderBuffer.io.retire(lane).bits.info.microOp
      profileRetireValid(lane) &&
      (microOp.readTimerLow || microOp.readTimerHigh || microOp.readTimerId)
    }
  ).asUInt

  io.profile.cycle := cycleTimer
  io.profile.fetchPc := frontend.io.debugPc
  io.profile.retirePc := profileRetirePc

  io.profile.retireCounterValue := VecInit(
    (0 until 3).map(lane => reorderBuffer.io.retire(lane).bits.state.counterValue(31, 0))
  ).asUInt
  io.profile.retireValid := profileRetireValid.asUInt
  io.profile.retireCounter := profileRetireCounter
  io.profile.branchRetired :=
    commit.io.output.predictorUpdate.valid && commit.io.output.predictorUpdate.bits.branchLike
  io.profile.mispredictRetired :=
    commit.io.output.predictorUpdate.valid && commit.io.output.predictorUpdate.bits.mispredict
  io.profile.branchMispredictRetired :=
    io.profile.mispredictRetired && commit.io.output.predictorUpdate.bits.branchLike
  io.profile.otherRecovery := commit.io.output.flush && !io.profile.mispredictRetired
  io.profile.h64LateCorrection := frontend.io.h64LateCorrection

  val h64DecodeCapture = decodeRename.io.profileObservation.idFire.orR
  val pendingRenameValid = RegEnable(decodeRename.io.profileObservation.idFire, h64DecodeCapture)
  val pendingH64Valid = RegEnable(
    frontend.io.profileObservation.h64Valid & decodeRename.io.profileObservation.idFire,
    h64DecodeCapture
  )
  val pendingH64Pc = RegEnable(frontend.io.profileObservation.h64Pc, h64DecodeCapture)
  val pendingH64Token = RegEnable(frontend.io.profileObservation.h64Token, h64DecodeCapture)
  val pendingH64Epoch = RegEnable(frontend.io.profileObservation.h64Epoch, h64DecodeCapture)
  val pendingH64Way = RegEnable(frontend.io.profileObservation.h64Way, h64DecodeCapture)
  val pendingH64History = RegEnable(frontend.io.profileObservation.h64History, h64DecodeCapture)
  val pendingH64Path = RegEnable(frontend.io.profileObservation.h64Path, h64DecodeCapture)
  val pendingH64Score = RegEnable(frontend.io.profileObservation.h64Score, h64DecodeCapture)
  val pendingH64PhtCounter = RegEnable(
    frontend.io.profileObservation.h64PhtCounter,
    h64DecodeCapture
  )
  val pendingH64FastTaken = RegEnable(
    frontend.io.profileObservation.h64FastTaken,
    h64DecodeCapture
  )
  val pendingH64NeuralTaken = RegEnable(
    frontend.io.profileObservation.h64NeuralTaken,
    h64DecodeCapture
  )
  val pendingH64Reliable = RegEnable(frontend.io.profileObservation.h64Reliable, h64DecodeCapture)
  val pendingH64Override = RegEnable(frontend.io.profileObservation.h64Override, h64DecodeCapture)
  val pendingH64DirectionBias = RegEnable(
    frontend.io.profileObservation.h64DirectionBias,
    h64DecodeCapture
  )
  io.profile.h64RenameFire := Mux(
    decodeRename.io.profileObservation.renameFire,
    pendingRenameValid,
    0.U
  )
  io.profile.h64RenameEvaluated := pendingH64Valid
  io.profile.h64RenameRobIndex := decodeRename.io.profileObservation.renameRobIndex
  io.profile.h64RenameFastTaken := pendingH64FastTaken
  io.profile.h64RenameNeuralTaken := pendingH64NeuralTaken
  io.profile.h64RenameReliable := pendingH64Reliable
  io.profile.h64RenameOverride := pendingH64Override
  io.profile.h64DecodeFire := Mux(
    decodeRename.io.profileObservation.renameFire,
    pendingH64Valid,
    0.U
  )
  io.profile.h64DecodeRobIndex := decodeRename.io.profileObservation.renameRobIndex
  io.profile.h64DecodePc := pendingH64Pc
  io.profile.h64DecodeToken := pendingH64Token
  io.profile.h64DecodeEpoch := pendingH64Epoch
  io.profile.h64DecodeWay := pendingH64Way
  io.profile.h64DecodeHistory := pendingH64History
  io.profile.h64DecodePath := pendingH64Path
  io.profile.h64DecodeScore := pendingH64Score
  io.profile.h64DecodePhtCounter := pendingH64PhtCounter
  io.profile.h64DecodeFastTaken := pendingH64FastTaken
  io.profile.h64DecodeNeuralTaken := pendingH64NeuralTaken
  io.profile.h64DecodeReliable := pendingH64Reliable
  io.profile.h64DecodeOverride := pendingH64Override
  io.profile.h64DecodeDirectionBias := pendingH64DirectionBias
  io.profile.h64RetireActualTaken := VecInit((0 until 3).map { lane =>
    reorderBuffer.io.retire(lane).bits.state.actualTaken
  }).asUInt
  io.profile.retireRobIndex := reorderBuffer.io.popPointer
  io.profile.mispredictResolved := execution.io.profileObservation.integerMispredict
  io.profile.mispredictResolvedRob := execution.io.profileObservation.integerExeRob
  io.profile.frontendValid := VecInit(frontend.io.decode.map(_.valid)).asUInt
  io.profile.dispatchBlocked := decodeRename.io.dispatch.valid && !decodeRename.io.dispatch.ready
  io.profile.robOccupancy := reorderBuffer.io.occupancy
  io.profile.integerIssueOccupancy := issueDispatch.io.integerOccupancy
  io.profile.mulDivIssueOccupancy := issueDispatch.io.mulDivOccupancy
  io.profile.memoryIssueOccupancy := issueDispatch.io.memoryOccupancy
  io.profile.storeBufferOccupancy := loadStore.io.storeBufferOccupancy
  io.profile.integerIssue := execution.io.profileObservation.integerIssue
  io.profile.mulDivIssue := execution.io.profileObservation.mduIssue
  io.profile.memoryIssue := loadStore.io.profileObservation.issue
  io.profile.memoryIssueOperandsReady := issueDispatch.io.memory.valid
  io.profile.memoryIssueAddressReady := issueDispatch.io.memoryHeadOperandReady(0)
  io.profile.memoryIssueDataReady := issueDispatch.io.memoryHeadOperandReady(1)
  io.profile.memoryIssueLsuReady := loadStore.io.allowIssue
  io.profile.memoryIssueHeadPc := issueDispatch.io.memory.bits.payload.pc
  io.profile.speculativeWakeupFailed := loadStore.io.speculativeWakeupFailed
  io.profile.mem2Valid := loadStore.io.profileObservation.mem2Valid
  io.profile.mem2Pc := loadStore.io.profileObservation.mem2Pc
  io.profile.mem2CacheWait := loadStore.io.profileObservation.mem2CacheWait
  io.profile.mem2StoreBufferWait := loadStore.io.profileObservation.mem2StoreBufferWait
  io.profile.mem2AuxWait := loadStore.io.profileObservation.mem2AuxWait
  io.profile.instructionCache := frontend.io.profile
  io.profile.dataCache := loadStore.io.profile

}
