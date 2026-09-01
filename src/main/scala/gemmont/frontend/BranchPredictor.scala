package gemmont.frontend

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import gemmont.H64CorrectorConfig
import gemmont.common.BankedReorderRam

class PredictionInfo extends Bundle {
  val predictsBranch = Bool()
  val taken = Bool()
  val target = UInt(32.W)
}

class PredictionRecovery extends Bundle {
  val globalHistory = UInt(BranchPredictorParameters.historyWidth.W)
  val precedingBranches = UInt(2.W)
}

class FetchPrediction extends Bundle {
  val pc = UInt(32.W)
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
  val instructionMask = UInt(4.W)
  val branchMask = UInt(4.W)
  val conditionalMask = UInt(4.W)
  val takenMask = UInt(4.W)
  val redirect = Bool()
  val redirectTarget = UInt(32.W)
  val targets = Vec(4, UInt(32.W))
  val recovery = Vec(4, new PredictionRecovery)
  val selectedWay = UInt(2.W)
  val selectedCall = Bool()
  val selectedReturn = Bool()
  val selectedCallReturnAddress = UInt(30.W)
}

class PredictorQuery extends Bundle {
  val pc = UInt(32.W)
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
}

private[frontend] class PredictionRasSnapshot extends Bundle {
  val stack = Vec(8, UInt(30.W))
  val top = UInt(3.W)
}

class PredictorUpdate extends Bundle {
  val prediction = new PredictionInfo
  val recovery = new PredictionRecovery
  val branchLike = Bool()
  val conditionalBranch = Bool()
  val taken = Bool()
  val staticTarget = Bool()
  val isReturn = Bool()
  val isCall = Bool()
  val mispredict = Bool()
  val pc = UInt(32.W)
  val target = UInt(32.W)
}

class BranchPredictor(
    phtInitializationFile: String = "src/main/resources/pht-init.hex",
    btbInitializationFile: String = "src/main/resources/btb-init.hex",
    btbRedirectControlInitializationFile: String =
      "src/main/resources/btb-redirect-control-init.hex",
    btbRedirectStateInitializationFile: String = "src/main/resources/btb-redirect-state-init.hex",
    h64InitializationFile: String = "src/main/resources/h64-residual-weights-int4.hex",
    h64Config: H64CorrectorConfig = H64CorrectorConfig()
) extends Module {
  private val fetchWidth = 4
  private val btbEntries = 1024

  private val btbWordWidth = 56

  private val btbRedirectTagWidth = 6
  private val btbRedirectTagControlWidth = 1 + btbRedirectTagWidth
  private val btbRedirectFlagWidth = 5

  private val cacheRedirectTargetWidth = 6
  private val cacheRedirectEntryWidth =
    btbRedirectTagControlWidth + btbRedirectFlagWidth + cacheRedirectTargetWidth

  private val stateRedirectTargetFragmentWidth = 16
  private val stateRedirectEntryWidth =
    btbRedirectTagControlWidth + btbRedirectFlagWidth +
      stateRedirectTargetFragmentWidth

  val io = IO(new Bundle {
    val query = Flipped(Valid(new PredictorQuery))

    val backendRedirectPhtPcWord = Flipped(
      Valid(UInt(BranchPredictorParameters.phtIndexWidth.W))
    )

    val backendRedirectPhtUsesRecoveryHistory = Input(Bool())
    val nonLiveQueryPcFragment = Input(UInt(16.W))
    val response = Output(Valid(new FetchPrediction))

    val liveRedirectTarget = Output(Valid(UInt(32.W)))

    val pcRedirect = Output(Valid(UInt(32.W)))

    val liveRedirectReadSet = Output(Valid(UInt(6.W)))

    val liveRedirectQueryAllowed = Input(Bool())
    val consumeResponse = Input(Bool())
    val update = Flipped(Valid(new PredictorUpdate))
    val committedBranches = Input(Vec(3, Valid(new H64CommittedBranch)))
    val lateCorrection = Flipped(Valid(new LatePredictionCorrection))

    val localInvalidate = Input(Bool())
    val flush = Input(Bool())
    val h64Result = Output(Valid(new H64CorrectorResult))
  })

  val btb = Module(
    new BankedReorderRam(
      wordWidth = btbWordWidth,
      wordCount = btbEntries,
      portCount = fetchWidth,
      writeFirst = true,
      initializationFile = Some(btbInitializationFile),
      readWrapBoundaryWords = Some(16)
    )
  )

  val btbRedirectTagControls = Seq.fill(fetchWidth)(
    Mem(btbEntries / fetchWidth, UInt(btbRedirectTagControlWidth.W))
  )
  val btbRedirectFlags = Seq.fill(fetchWidth)(
    Mem(btbEntries / fetchWidth, UInt(btbRedirectFlagWidth.W))
  )

  val cacheRedirectEntries = Seq.fill(fetchWidth)(
    SyncReadMem(
      btbEntries / fetchWidth,
      UInt(cacheRedirectEntryWidth.W),
      SyncReadMem.ReadFirst
    )
  )

  val stateRedirectEntries = Seq.fill(fetchWidth)(
    SyncReadMem(
      btbEntries / fetchWidth,
      UInt(stateRedirectEntryWidth.W),
      SyncReadMem.ReadFirst
    )
  )
  val redirectControlMemories = btbRedirectTagControls ++ btbRedirectFlags
  redirectControlMemories.foreach { memory =>
    loadMemoryFromFileInline(memory, btbRedirectControlInitializationFile)
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"distributed\"")
    })
  }
  cacheRedirectEntries.foreach { memory =>
    loadMemoryFromFileInline(memory, btbRedirectControlInitializationFile)
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"block\"")
    })
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "dont_touch = \"yes\"")
    })
  }
  stateRedirectEntries.foreach { memory =>
    loadMemoryFromFileInline(memory, btbRedirectStateInitializationFile)
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"block\"")
    })
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "dont_touch = \"yes\"")
    })
  }
  val pht = Module(new DirectionPredictorTable(phtInitializationFile))
  private val historyWidth = BranchPredictorParameters.historyWidth
  val globalHistory = RegInit(0.U(historyWidth.W))
  val ras = RegInit(VecInit(Seq.fill(8)((BigInt("1c000000", 16) >> 2).U(30.W))))
  val rasTop = RegInit(0.U(3.W))
  val committedRas =
    RegInit(VecInit(Seq.fill(8)((BigInt("1c000000", 16) >> 2).U(30.W))))
  val committedRasTop = RegInit(0.U(3.W))
  val neuralHistory = RegInit(0.U(H64CorrectorParameters.historyWidth.W))
  val neuralPath = RegInit(0.U(H64CorrectorParameters.pathWidth.W))
  val neuralIndexState = RegInit(0.U(H64CorrectorParameters.indexWidth.W))
  val committedNeuralHistory = RegInit(0.U(H64CorrectorParameters.historyWidth.W))
  val committedNeuralPath = RegInit(0.U(H64CorrectorParameters.pathWidth.W))

  val lateCorrectionPending = RegNext(io.lateCorrection.valid, false.B)

  val pendingLateCorrection = RegNext(io.lateCorrection.bits)
  val pendingCorrectedGlobalHistory =
    ((pendingLateCorrection.recovery.globalHistory <<
      (pendingLateCorrection.recovery.precedingBranches +& 1.U)) |
      pendingLateCorrection.taken.asUInt)(historyWidth - 1, 0)
  val pendingCorrectedNeuralHistory = Cat(
    pendingLateCorrection.neuralHistoryBefore(
      H64CorrectorParameters.historyWidth - 2,
      0
    ),
    pendingLateCorrection.taken
  )
  val pendingCorrectedNeuralPath =
    (pendingLateCorrection.neuralPathBefore << 1) ^
      (pendingLateCorrection.pc >> 2)
  val pendingCorrectedNeuralIndexState =
    pendingCorrectedNeuralHistory(H64CorrectorParameters.indexWidth - 1, 0) ^
      pendingCorrectedNeuralPath(H64CorrectorParameters.indexWidth - 1, 0)
  val committedNeuralHistoryByLane =
    Wire(Vec(4, UInt(H64CorrectorParameters.historyWidth.W)))
  val committedNeuralPathByLane = Wire(Vec(4, UInt(H64CorrectorParameters.pathWidth.W)))
  committedNeuralHistoryByLane(0) := committedNeuralHistory
  committedNeuralPathByLane(0) := committedNeuralPath
  for (lane <- 0 until 3) {
    val branch = io.committedBranches(lane)
    committedNeuralHistoryByLane(lane + 1) := Mux(
      branch.valid,
      Cat(
        committedNeuralHistoryByLane(lane)(H64CorrectorParameters.historyWidth - 2, 0),
        branch.bits.taken
      ),
      committedNeuralHistoryByLane(lane)
    )
    committedNeuralPathByLane(lane + 1) := Mux(
      branch.valid,
      (committedNeuralPathByLane(lane) << 1) ^ (branch.bits.pc >> 2),
      committedNeuralPathByLane(lane)
    )
  }
  val committedNeuralHistoryNext = committedNeuralHistoryByLane(3)
  val committedNeuralPathNext = committedNeuralPathByLane(3)
  val committedNeuralIndexStateNext =
    committedNeuralHistoryNext(H64CorrectorParameters.indexWidth - 1, 0) ^
      committedNeuralPathNext(H64CorrectorParameters.indexWidth - 1, 0)
  val retiresConditional = io.committedBranches.map(_.valid).reduce(_ || _)
  val readNeuralHistory = WireDefault(
    Mux(
      io.flush,
      committedNeuralHistoryNext,
      Mux(lateCorrectionPending, pendingCorrectedNeuralHistory, neuralHistory)
    )
  )
  val readNeuralPath = WireDefault(
    Mux(
      io.flush,
      committedNeuralPathNext,
      Mux(lateCorrectionPending, pendingCorrectedNeuralPath, neuralPath)
    )
  )
  val readNeuralIndexState = WireDefault(
    Mux(
      io.flush,
      committedNeuralIndexStateNext,
      Mux(
        lateCorrectionPending,
        pendingCorrectedNeuralIndexState,
        neuralIndexState
      )
    )
  )

  val h64Corrector =
    if (h64Config.enabled)
      Some(
        Module(
          new H64BranchCorrector(
            h64InitializationFile,
            h64Config.marginThreshold
          )
        )
      )
    else None
  io.h64Result := 0.U.asTypeOf(io.h64Result)
  h64Corrector.foreach { corrector =>
    corrector.io.query.valid := io.query.valid
    corrector.io.query.bits.pc := io.query.bits.pc
    corrector.io.query.bits.history := readNeuralHistory
    corrector.io.query.bits.path := readNeuralPath
    corrector.io.query.bits.indexState := readNeuralIndexState
    corrector.io.query.bits.token := io.query.bits.token
    corrector.io.query.bits.epoch := io.query.bits.epoch

    corrector.io.flush := io.flush || io.localInvalidate
    io.h64Result := corrector.io.result
  }

  val recoveredHistory =
    ((io.update.bits.recovery.globalHistory <<
      (io.update.bits.recovery.precedingBranches +& 1.U)) |
      io.update.bits.taken.asUInt)(historyWidth - 1, 0)
  val nextGlobalHistory = Mux(
    io.update.valid && io.update.bits.mispredict,
    recoveredHistory,
    globalHistory
  )

  val readGlobalHistory = WireDefault(
    Mux(
      !io.flush && lateCorrectionPending,
      pendingCorrectedGlobalHistory,
      nextGlobalHistory
    )
  )
  val readFoldedHistory =
    WireDefault(BranchPredictorParameters.foldHistory(readGlobalHistory))

  val phtBaseGlobalHistory = Mux(
    !io.backendRedirectPhtUsesRecoveryHistory && lateCorrectionPending,
    pendingCorrectedGlobalHistory,
    nextGlobalHistory
  )
  val phtReadFoldedHistory =
    WireDefault(BranchPredictorParameters.foldHistory(phtBaseGlobalHistory))

  val readGroupPc = Cat(io.query.bits.pc(31, 4), 0.U(4.W))
  val stateFeedbackQueryPcFragment = WireDefault(io.nonLiveQueryPcFragment)
  btb.io.read.valid := io.query.valid
  btb.io.read.bits := readGroupPc(11, 2)
  btb.io.write.valid := false.B
  btb.io.write.bits := 0.U.asTypeOf(btb.io.write.bits)

  pht.io.read.valid := io.query.valid
  val phtReadPcWord = WireDefault(
    Mux(
      io.backendRedirectPhtPcWord.valid,
      io.backendRedirectPhtPcWord.bits,
      Cat(stateFeedbackQueryPcFragment(12, 2), 0.U(2.W))
    )
  )
  pht.io.read.bits := BranchPredictorParameters.phtAddressFromPcWord(
    phtReadPcWord,
    phtReadFoldedHistory
  )
  when(io.query.valid && io.backendRedirectPhtPcWord.valid) {
    assert(
      io.backendRedirectPhtPcWord.bits === readGroupPc(14, 2),
      "backend-local PHT PC must match the complete recovery query"
    )
  }
  assert(
    !io.backendRedirectPhtUsesRecoveryHistory || io.backendRedirectPhtPcWord.valid,
    "PHT recovery-history marker requires a backend redirect query"
  )
  pht.io.update.valid := false.B
  pht.io.update.bits := 0.U.asTypeOf(pht.io.update.bits)

  val queryPc = RegEnable(io.query.bits.pc, io.query.valid)
  val queryToken = RegEnable(io.query.bits.token, io.query.valid)
  val queryEpoch = RegEnable(io.query.bits.epoch, io.query.valid)
  val queryGlobalHistory = RegEnable(readGlobalHistory, io.query.valid)

  val cacheQueryPartialTag =
    RegEnable(io.query.bits.pc(11 + btbRedirectTagWidth, 12), io.query.valid)
  val stateQueryPartialTag =
    RegEnable(stateFeedbackQueryPcFragment(15, 10), io.query.valid)
  val stateQueryOffset =
    RegEnable(stateFeedbackQueryPcFragment(1, 0), io.query.valid)

  val redirectControlQueryRows = (0 until fetchWidth).map { _ =>
    Seq.fill(2)(RegEnable(io.query.bits.pc(11, 4), io.query.valid))
  }
  val physicalQueryBankValid = VecInit((0 until fetchWidth).map { bank =>
    RegEnable(bank.U >= io.query.bits.pc(3, 2), io.query.valid)
  })
  val cacheQueryBankValidRegisters = (0 until fetchWidth).map { bank =>
    RegEnable(bank.U >= io.query.bits.pc(3, 2), io.query.valid)
  }
  val stateQueryBankValidRegisters = (0 until fetchWidth).map { bank =>
    RegEnable(bank.U >= stateFeedbackQueryPcFragment(1, 0), io.query.valid)
  }
  val cacheQueryBankValid = VecInit(cacheQueryBankValidRegisters)
  val stateQueryBankValid = VecInit(stateQueryBankValidRegisters)
  Seq(cacheQueryPartialTag, stateQueryPartialTag, stateQueryOffset).foreach { state =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(state.toTarget, "dont_touch = \"yes\"")
    })
  }
  (cacheQueryBankValidRegisters ++ stateQueryBankValidRegisters).foreach { state =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(state.toTarget, "dont_touch = \"yes\"")
    })
  }
  redirectControlQueryRows.flatten.foreach { row =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(row.toTarget, "max_fanout = 4")
    })
  }
  val responseValid = btb.io.responseValid && pht.io.responseValid
  val calculatedResponse = WireDefault(0.U.asTypeOf(new FetchPrediction))
  calculatedResponse.pc := queryPc
  calculatedResponse.token := queryToken
  calculatedResponse.epoch := queryEpoch

  val queryTag = queryPc(31, 12)
  val queryOffset = queryPc(3, 2)
  val hits = Wire(Vec(fetchWidth, Bool()))
  val predictionHits = Wire(Vec(fetchWidth, Bool()))
  val returnTarget = Cat(ras(rasTop), 0.U(2.W))

  val physicalRedirectTagControlsRaw =
    Wire(Vec(fetchWidth, UInt(btbRedirectTagControlWidth.W)))
  val physicalRedirectFlagsRaw =
    Wire(Vec(fetchWidth, UInt(btbRedirectFlagWidth.W)))
  val physicalRedirectTagControls =
    Wire(Vec(fetchWidth, UInt(btbRedirectTagControlWidth.W)))
  val physicalRedirectFlags =
    Wire(Vec(fetchWidth, UInt(btbRedirectFlagWidth.W)))
  val cacheRedirectEntryResponsesRaw =
    Wire(Vec(fetchWidth, UInt(cacheRedirectEntryWidth.W)))
  val stateRedirectEntryResponsesRaw =
    Wire(Vec(fetchWidth, UInt(stateRedirectEntryWidth.W)))
  stateRedirectEntryResponsesRaw.foreach { response =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(response.toTarget, "dont_touch = \"yes\"")
    })
  }
  for (bank <- 0 until fetchWidth) {
    physicalRedirectTagControlsRaw(bank) :=
      btbRedirectTagControls(bank).read(redirectControlQueryRows(bank)(0))
    physicalRedirectFlagsRaw(bank) :=
      btbRedirectFlags(bank).read(redirectControlQueryRows(bank)(1))
    cacheRedirectEntryResponsesRaw(bank) :=
      cacheRedirectEntries(bank).read(io.query.bits.pc(11, 4), io.query.valid)
    stateRedirectEntryResponsesRaw(bank) :=
      stateRedirectEntries(bank).read(
        stateFeedbackQueryPcFragment(9, 2),
        io.query.valid
      )
  }

  val redirectWriteBank = btb.io.write.bits.address(1, 0)
  val redirectWriteRow = btb.io.write.bits.address(9, 2)
  val redirectWriteEntry = btb.io.write.bits.data
  val redirectWriteTagControl = Cat(
    redirectWriteEntry(55),
    redirectWriteEntry(34 + btbRedirectTagWidth, 35)
  )
  val redirectWriteFlags = redirectWriteEntry(4, 0)
  val redirectWriteCacheEntry = Cat(
    redirectWriteTagControl,
    redirectWriteFlags,
    redirectWriteEntry(14, 9)
  )
  val redirectWriteStateEntry = Cat(
    redirectWriteTagControl,
    redirectWriteFlags,
    redirectWriteEntry(20, 5)
  )
  for (bank <- 0 until fetchWidth) {
    val selected = btb.io.write.valid && redirectWriteBank === bank.U
    when(selected) {
      btbRedirectTagControls(bank).write(redirectWriteRow, redirectWriteTagControl)
      btbRedirectFlags(bank).write(redirectWriteRow, redirectWriteFlags)
      cacheRedirectEntries(bank).write(
        redirectWriteRow,
        redirectWriteCacheEntry
      )
      stateRedirectEntries(bank).write(
        redirectWriteRow,
        redirectWriteStateEntry
      )
    }
  }

  val cacheForwardedDataDelay = RegNext(redirectWriteCacheEntry)
  val stateForwardedDataDelay = RegNext(redirectWriteStateEntry)
  val cacheResponseForwarded = RegInit(VecInit(Seq.fill(fetchWidth)(false.B)))
  val stateResponseForwarded = RegInit(VecInit(Seq.fill(fetchWidth)(false.B)))
  for (bank <- 0 until fetchWidth) {
    cacheResponseForwarded(bank) :=
      io.query.valid && btb.io.write.valid && redirectWriteBank === bank.U &&
        io.query.bits.pc(11, 4) === redirectWriteRow
    stateResponseForwarded(bank) :=
      io.query.valid && btb.io.write.valid && redirectWriteBank === bank.U &&
        stateFeedbackQueryPcFragment(9, 2) === redirectWriteRow
  }
  Seq(
    cacheForwardedDataDelay,
    stateForwardedDataDelay,
    cacheResponseForwarded,
    stateResponseForwarded
  ).foreach { state =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(state.toTarget, "dont_touch = \"yes\"")
    })
  }
  val cacheForwardedTagControl = cacheForwardedDataDelay(
    cacheRedirectEntryWidth - 1,
    cacheRedirectTargetWidth + btbRedirectFlagWidth
  )
  val cacheForwardedFlags = cacheForwardedDataDelay(
    cacheRedirectTargetWidth + btbRedirectFlagWidth - 1,
    cacheRedirectTargetWidth
  )
  val stateForwardedTagControl = stateForwardedDataDelay(
    stateRedirectEntryWidth - 1,
    stateRedirectTargetFragmentWidth + btbRedirectFlagWidth
  )
  val stateForwardedFlags = stateForwardedDataDelay(
    stateRedirectTargetFragmentWidth + btbRedirectFlagWidth - 1,
    stateRedirectTargetFragmentWidth
  )
  for (bank <- 0 until fetchWidth) {

    physicalRedirectTagControls(bank) := Mux(
      cacheResponseForwarded(bank),
      cacheForwardedTagControl,
      physicalRedirectTagControlsRaw(bank)
    )
    physicalRedirectFlags(bank) := Mux(
      cacheResponseForwarded(bank),
      cacheForwardedFlags,
      physicalRedirectFlagsRaw(bank)
    )
  }
  when(btb.io.responseValid) {
    assert(
      cacheResponseForwarded.asUInt === btb.io.physicalResponseForwarded.asUInt,
      "cache redirect collision must match the packed BTB collision"
    )
    assert(
      stateResponseForwarded.asUInt === btb.io.physicalResponseForwarded.asUInt,
      "state redirect collision must match the packed BTB collision"
    )
  }

  val physicalHits = Wire(Vec(fetchWidth, Bool()))
  val physicalRedirectHits = Wire(Vec(fetchWidth, Bool()))
  val physicalTargets = Wire(Vec(fetchWidth, UInt(30.W)))
  val physicalRedirectStaticTargets = Wire(Vec(fetchWidth, Bool()))
  val physicalRedirectCalls = Wire(Vec(fetchWidth, Bool()))
  val physicalRedirectReturns = Wire(Vec(fetchWidth, Bool()))
  val physicalRedirectDirectionBiases = Wire(Vec(fetchWidth, Bool()))
  val physicalConditionalBranches = Wire(Vec(fetchWidth, Bool()))
  val physicalTakenCandidates = Wire(Vec(fetchWidth, Bool()))
  val physicalResolvedTargets = Wire(Vec(fetchWidth, UInt(32.W)))
  val cacheRedirectHits = Wire(Vec(fetchWidth, Bool()))
  val cacheTakenCandidates = Wire(Vec(fetchWidth, Bool()))
  val cacheSetTakenCandidates = Wire(Vec(fetchWidth, Bool()))
  val cacheResolvedTargets = Wire(Vec(fetchWidth, UInt(6.W)))
  val cacheResolvedFullTargets = Wire(Vec(fetchWidth, UInt(32.W)))
  val stateRedirectHits = Wire(Vec(fetchWidth, Bool()))
  val stateTakenCandidates = Wire(Vec(fetchWidth, Bool()))
  val indexStateTakenCandidates = Wire(Vec(fetchWidth, Bool()))
  val stateResolvedPhtPcWords =
    Wire(Vec(fetchWidth, UInt(BranchPredictorParameters.phtIndexWidth.W)))
  val stateResolvedTargetFragments =
    Wire(Vec(fetchWidth, UInt(stateRedirectTargetFragmentWidth.W)))
  val statePhysicalConditionalBranches = Wire(Vec(fetchWidth, Bool()))
  val indexStatePhysicalConditionalBranches = Wire(Vec(fetchWidth, Bool()))
  for (bank <- 0 until fetchWidth) {
    val packed = btb.io.physicalResponse(bank)
    val tagControl = physicalRedirectTagControls(bank)
    val flags = physicalRedirectFlags(bank)
    physicalHits(bank) := packed(55) && packed(54, 35) === queryTag
    physicalRedirectHits(bank) :=
      tagControl(btbRedirectTagControlWidth - 1) &&
        tagControl(btbRedirectTagControlWidth - 2, 0) ===
        queryTag(btbRedirectTagWidth - 1, 0)
    physicalTargets(bank) := packed(34, 5)
    physicalRedirectStaticTargets(bank) := flags(3)
    physicalRedirectCalls(bank) := flags(2)
    physicalRedirectReturns(bank) := flags(1)
    physicalRedirectDirectionBiases(bank) := flags(0)
    physicalConditionalBranches(bank) :=
      physicalQueryBankValid(bank) && physicalHits(bank) && packed(4)

    val phtTaken =
      pht.io.physicalResponse(bank)(1) ^ physicalRedirectDirectionBiases(bank)
    physicalTakenCandidates(bank) :=
      physicalQueryBankValid(bank) && physicalRedirectHits(bank) &&
        (physicalRedirectStaticTargets(bank) || phtTaken)
    physicalResolvedTargets(bank) := Mux(
      physicalRedirectReturns(bank),
      returnTarget,
      Cat(physicalTargets(bank), 0.U(2.W))
    )

    val cacheRawEntry = cacheRedirectEntryResponsesRaw(bank)
    val cacheRawTarget = cacheRawEntry(cacheRedirectTargetWidth - 1, 0)
    val cacheRawFlags = cacheRawEntry(
      cacheRedirectTargetWidth + btbRedirectFlagWidth - 1,
      cacheRedirectTargetWidth
    )
    val cacheRawTagControl = cacheRawEntry(
      cacheRedirectEntryWidth - 1,
      cacheRedirectTargetWidth + btbRedirectFlagWidth
    )
    val cacheRawHit =
      cacheRawTagControl(btbRedirectTagControlWidth - 1) &&
        cacheRawTagControl(btbRedirectTagControlWidth - 2, 0) ===
        cacheQueryPartialTag
    val cacheForwardedHit =
      cacheForwardedTagControl(btbRedirectTagControlWidth - 1) &&
        cacheForwardedTagControl(btbRedirectTagControlWidth - 2, 0) ===
        cacheQueryPartialTag
    val cacheUsesForwardedEntry = cacheResponseForwarded(bank)
    cacheRedirectHits(bank) :=
      Mux(cacheUsesForwardedEntry, cacheForwardedHit, cacheRawHit)

    val cacheRawPhtTaken =
      pht.io.physicalResponse(bank)(1) ^ cacheRawFlags(0)
    val cacheForwardedPhtTaken =
      pht.io.physicalResponse(bank)(1) ^ cacheForwardedFlags(0)
    val cacheRawTaken =
      cacheRawHit && (cacheRawFlags(3) || cacheRawPhtTaken)
    val cacheForwardedTaken =
      cacheForwardedHit &&
        (cacheForwardedFlags(3) || cacheForwardedPhtTaken)
    cacheTakenCandidates(bank) :=
      cacheQueryBankValid(bank) && Mux(
        cacheUsesForwardedEntry,
        cacheForwardedTaken,
        cacheRawTaken
      )

    val cacheSetTagControl = Mux(
      cacheUsesForwardedEntry,
      cacheForwardedTagControl,
      cacheRawTagControl
    )
    val cacheSetFlags =
      Mux(cacheUsesForwardedEntry, cacheForwardedFlags, cacheRawFlags)
    val cacheSetHit =
      cacheSetTagControl(btbRedirectTagControlWidth - 1) &&
        cacheSetTagControl(btbRedirectTagControlWidth - 2, 0) ===
        cacheQueryPartialTag
    val cacheSetPhtTaken =
      pht.io.physicalResponse(bank)(1) ^ cacheSetFlags(0)
    val cacheSetTaken =
      cacheSetHit && (cacheSetFlags(3) || cacheSetPhtTaken)
    cacheSetTakenCandidates(bank) :=
      cacheQueryBankValid(bank) && cacheSetTaken
    Seq(
      cacheSetTagControl,
      cacheSetFlags,
      cacheSetHit,
      cacheSetPhtTaken,
      cacheSetTaken,
      cacheSetTakenCandidates(bank)
    ).foreach(dontTouch(_))

    val cacheRawReturn = cacheRawFlags(1)
    val cacheForwardedReturn = cacheForwardedFlags(1)
    val cacheSelectedReturn =
      Mux(cacheUsesForwardedEntry, cacheForwardedReturn, cacheRawReturn)
    val cacheRawResolvedTarget =
      Mux(cacheRawReturn, returnTarget(11, 6), cacheRawTarget)
    val cacheForwardedResolvedTarget = Mux(
      cacheForwardedReturn,
      returnTarget(11, 6),
      cacheForwardedDataDelay(cacheRedirectTargetWidth - 1, 0)
    )
    cacheResolvedTargets(bank) := Mux(
      cacheUsesForwardedEntry,
      cacheForwardedResolvedTarget,
      cacheRawResolvedTarget
    )
    cacheResolvedFullTargets(bank) := Mux(
      cacheSelectedReturn,
      returnTarget,
      Cat(physicalTargets(bank), 0.U(2.W))
    )

    val stateRawEntry = stateRedirectEntryResponsesRaw(bank)
    val stateRawTargetFragment =
      stateRawEntry(stateRedirectTargetFragmentWidth - 1, 0)
    val stateRawFlags = stateRawEntry(
      stateRedirectTargetFragmentWidth + btbRedirectFlagWidth - 1,
      stateRedirectTargetFragmentWidth
    )
    val stateRawTagControl = stateRawEntry(
      stateRedirectEntryWidth - 1,
      stateRedirectTargetFragmentWidth + btbRedirectFlagWidth
    )
    val stateRawHit =
      stateRawTagControl(btbRedirectTagControlWidth - 1) &&
        stateRawTagControl(btbRedirectTagControlWidth - 2, 0) ===
        stateQueryPartialTag
    val stateForwardedHit =
      stateForwardedTagControl(btbRedirectTagControlWidth - 1) &&
        stateForwardedTagControl(btbRedirectTagControlWidth - 2, 0) ===
        stateQueryPartialTag
    val stateUsesForwardedEntry = stateResponseForwarded(bank)
    stateRedirectHits(bank) :=
      Mux(stateUsesForwardedEntry, stateForwardedHit, stateRawHit)
    val stateRawPhtTaken =
      pht.io.physicalResponse(bank)(1) ^ stateRawFlags(0)
    val stateForwardedPhtTaken =
      pht.io.physicalResponse(bank)(1) ^ stateForwardedFlags(0)
    val stateRawTaken =
      stateRawHit && (stateRawFlags(3) || stateRawPhtTaken)
    val stateForwardedTaken =
      stateForwardedHit && (stateForwardedFlags(3) || stateForwardedPhtTaken)
    stateTakenCandidates(bank) :=
      stateQueryBankValid(bank) && Mux(
        stateUsesForwardedEntry,
        stateForwardedTaken,
        stateRawTaken
      )
    val stateRawReturn = stateRawFlags(1)
    val stateForwardedReturn = stateForwardedFlags(1)
    val stateRawResolvedTargetFragment = Mux(
      stateRawReturn,
      returnTarget(17, 2),
      stateRawTargetFragment
    )
    val stateForwardedResolvedTargetFragment = Mux(
      stateForwardedReturn,
      returnTarget(17, 2),
      stateForwardedDataDelay(stateRedirectTargetFragmentWidth - 1, 0)
    )
    stateResolvedTargetFragments(bank) := Mux(
      stateUsesForwardedEntry,
      stateForwardedResolvedTargetFragment,
      stateRawResolvedTargetFragment
    )
    stateResolvedPhtPcWords(bank) := Cat(
      stateResolvedTargetFragments(bank)(12, 2),
      0.U(2.W)
    )
    val stateRawConditional = stateRawHit && stateRawFlags(4)
    val stateForwardedConditional =
      stateForwardedHit && stateForwardedFlags(4)
    statePhysicalConditionalBranches(bank) := stateQueryBankValid(bank) && Mux(
      stateUsesForwardedEntry,
      stateForwardedConditional,
      stateRawConditional
    )

    val indexStateTagControl = Mux(
      stateUsesForwardedEntry,
      stateForwardedTagControl,
      stateRawTagControl
    )
    val indexStateFlags =
      Mux(stateUsesForwardedEntry, stateForwardedFlags, stateRawFlags)
    val indexStateHit =
      indexStateTagControl(btbRedirectTagControlWidth - 1) &&
        indexStateTagControl(btbRedirectTagControlWidth - 2, 0) ===
        stateQueryPartialTag
    val indexStatePhtTaken =
      pht.io.physicalResponse(bank)(1) ^ indexStateFlags(0)
    val indexStateTaken =
      indexStateHit && (indexStateFlags(3) || indexStatePhtTaken)
    indexStateTakenCandidates(bank) :=
      stateQueryBankValid(bank) && indexStateTaken
    indexStatePhysicalConditionalBranches(bank) :=
      stateQueryBankValid(bank) && indexStateHit && indexStateFlags(4)
    Seq(
      indexStateTagControl,
      indexStateFlags,
      indexStateHit,
      indexStatePhtTaken,
      indexStateTaken,
      indexStateTakenCandidates(bank),
      indexStatePhysicalConditionalBranches(bank)
    ).foreach(dontTouch(_))
  }
  cacheSetTakenCandidates.foreach { candidate =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(candidate.toTarget, "dont_touch = \"yes\"")
    })
  }
  indexStateTakenCandidates.foreach { candidate =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(candidate.toTarget, "dont_touch = \"yes\"")
    })
  }

  val physicalTakenMask = Wire(Vec(fetchWidth, Bool()))
  physicalTakenMask(0) := physicalTakenCandidates(0)
  physicalTakenMask(1) :=
    physicalTakenCandidates(1) && !physicalTakenCandidates(0)
  physicalTakenMask(2) :=
    physicalTakenCandidates(2) && !physicalTakenCandidates(1) &&
      !physicalTakenCandidates(0)
  physicalTakenMask(3) :=
    physicalTakenCandidates(3) && !physicalTakenCandidates(2) &&
      !physicalTakenCandidates(1) && !physicalTakenCandidates(0)

  val statePhysicalTakenMask = Wire(Vec(fetchWidth, Bool()))
  statePhysicalTakenMask(0) := stateTakenCandidates(0)
  statePhysicalTakenMask(1) :=
    stateTakenCandidates(1) && !stateTakenCandidates(0)
  statePhysicalTakenMask(2) :=
    stateTakenCandidates(2) && !stateTakenCandidates(1) &&
      !stateTakenCandidates(0)
  statePhysicalTakenMask(3) :=
    stateTakenCandidates(3) && !stateTakenCandidates(2) &&
      !stateTakenCandidates(1) && !stateTakenCandidates(0)

  val statePhysicalRedirectPrefixMask = Cat(
    !(stateTakenCandidates(0) || stateTakenCandidates(1) ||
      stateTakenCandidates(2)),
    !(stateTakenCandidates(0) || stateTakenCandidates(1)),
    !stateTakenCandidates(0),
    true.B
  ) & stateQueryBankValid.asUInt
  val stateInstructionMask =
    statePhysicalRedirectPrefixMask >> stateQueryOffset
  val stateTakenMask = statePhysicalTakenMask.asUInt >> stateQueryOffset
  val stateConditionalMask =
    (statePhysicalConditionalBranches.asUInt & statePhysicalRedirectPrefixMask) >>
      stateQueryOffset

  val indexStatePhysicalTakenMask = Wire(Vec(fetchWidth, Bool()))
  indexStatePhysicalTakenMask(0) := indexStateTakenCandidates(0)
  indexStatePhysicalTakenMask(1) :=
    indexStateTakenCandidates(1) && !indexStateTakenCandidates(0)
  indexStatePhysicalTakenMask(2) :=
    indexStateTakenCandidates(2) && !indexStateTakenCandidates(1) &&
      !indexStateTakenCandidates(0)
  indexStatePhysicalTakenMask(3) :=
    indexStateTakenCandidates(3) && !indexStateTakenCandidates(2) &&
      !indexStateTakenCandidates(1) && !indexStateTakenCandidates(0)
  val indexStatePhysicalRedirectPrefixMask = Cat(
    !(indexStateTakenCandidates(0) || indexStateTakenCandidates(1) ||
      indexStateTakenCandidates(2)),
    !(indexStateTakenCandidates(0) || indexStateTakenCandidates(1)),
    !indexStateTakenCandidates(0),
    true.B
  ) & stateQueryBankValid.asUInt
  val indexStateTakenMask =
    indexStatePhysicalTakenMask.asUInt >> stateQueryOffset
  val indexStateConditionalMask =
    (indexStatePhysicalConditionalBranches.asUInt &
      indexStatePhysicalRedirectPrefixMask) >> stateQueryOffset

  val groupLaneValid = Wire(Vec(fetchWidth, Bool()))
  val takenCandidates = Wire(Vec(fetchWidth, Bool()))
  val takenMask = Wire(Vec(fetchWidth, Bool()))
  val conditionalBranches = Wire(Vec(fetchWidth, Bool()))
  val stateConditionalBranches = Wire(Vec(fetchWidth, Bool()))
  val directionBiases = Wire(Vec(fetchWidth, Bool()))
  val logicalTargets = Wire(Vec(fetchWidth, UInt(32.W)))
  for (way <- 0 until fetchWidth) {
    val bank = (queryOffset + way.U)(1, 0)
    groupLaneValid(way) := way.U < (4.U - queryOffset)
    hits(way) := groupLaneValid(way) && physicalHits(bank)
    predictionHits(way) :=
      groupLaneValid(way) && physicalRedirectHits(bank)
    takenCandidates(way) :=
      groupLaneValid(way) && physicalTakenCandidates(bank)
    takenMask(way) := groupLaneValid(way) && physicalTakenMask(bank)
    conditionalBranches(way) :=
      groupLaneValid(way) && physicalConditionalBranches(bank)

    stateConditionalBranches(way) :=
      groupLaneValid(way) && statePhysicalConditionalBranches(bank)

    directionBiases(way) := btb.io.physicalResponse(bank)(0)
    logicalTargets(way) := Cat(physicalTargets(bank), 0.U(2.W))
  }

  val branchMask = hits.asUInt | predictionHits.asUInt
  val redirect = physicalTakenCandidates.asUInt.orR
  val selectedWay = OHToUInt(takenMask.asUInt)
  val selectedTargetLow =
    Mux(
      physicalTakenCandidates(0),
      physicalResolvedTargets(0),
      physicalResolvedTargets(1)
    )
  val selectedTargetHigh =
    Mux(
      physicalTakenCandidates(2),
      physicalResolvedTargets(2),
      physicalResolvedTargets(3)
    )
  val selectedRedirectTarget = Mux(
    physicalTakenCandidates(0) || physicalTakenCandidates(1),
    selectedTargetLow,
    selectedTargetHigh
  )

  val selectedCacheSetLow = Mux(
    cacheSetTakenCandidates(0),
    cacheResolvedTargets(0),
    cacheResolvedTargets(1)
  )
  val selectedCacheSetHigh = Mux(
    cacheSetTakenCandidates(2),
    cacheResolvedTargets(2),
    cacheResolvedTargets(3)
  )
  val selectedRedirectCacheSet = Mux(
    cacheSetTakenCandidates(0) || cacheSetTakenCandidates(1),
    selectedCacheSetLow,
    selectedCacheSetHigh
  )
  val selectedCacheTargetTail = Mux(
    cacheTakenCandidates(1),
    cacheResolvedFullTargets(1),
    Mux(
      cacheTakenCandidates(2),
      cacheResolvedFullTargets(2),
      cacheResolvedFullTargets(3)
    )
  )
  val selectedRedirectCacheTarget = Mux(
    cacheTakenCandidates(0),
    cacheResolvedFullTargets(0),
    selectedCacheTargetTail
  )
  val selectedPhtPcWordLow = Mux(
    stateTakenCandidates(0),
    stateResolvedPhtPcWords(0),
    stateResolvedPhtPcWords(1)
  )
  val selectedPhtPcWordHigh = Mux(
    stateTakenCandidates(2),
    stateResolvedPhtPcWords(2),
    stateResolvedPhtPcWords(3)
  )
  val selectedRedirectPhtPcWord = Mux(
    stateTakenCandidates(0) || stateTakenCandidates(1),
    selectedPhtPcWordLow,
    selectedPhtPcWordHigh
  )
  val selectedStateTargetFragmentLow = Mux(
    stateTakenCandidates(0),
    stateResolvedTargetFragments(0),
    stateResolvedTargetFragments(1)
  )
  val selectedStateTargetFragmentHigh = Mux(
    stateTakenCandidates(2),
    stateResolvedTargetFragments(2),
    stateResolvedTargetFragments(3)
  )
  val selectedStateTargetFragment = Mux(
    stateTakenCandidates(0) || stateTakenCandidates(1),
    selectedStateTargetFragmentLow,
    selectedStateTargetFragmentHigh
  )
  val selectedCall = Mux1H(
    (0 until fetchWidth).map(bank => physicalTakenMask(bank) -> physicalRedirectCalls(bank))
  )
  val selectedReturn = Mux1H(
    (0 until fetchWidth).map(bank => physicalTakenMask(bank) -> physicalRedirectReturns(bank))
  )

  val selectedCallReturnLow = queryPc(5, 2) +& (selectedWay +& 1.U)
  val selectedCallReturnHigh = Mux(
    selectedCallReturnLow(4),
    queryPc(31, 6) + 1.U,
    queryPc(31, 6)
  )
  val selectedCallReturnAddress =
    Cat(selectedCallReturnHigh, selectedCallReturnLow(3, 0))

  val redirectPrefixMask = Cat(
    !(takenCandidates(0) || takenCandidates(1) || takenCandidates(2)),
    !(takenCandidates(0) || takenCandidates(1)),
    !takenCandidates(0),
    true.B
  )
  val instructionMask = redirectPrefixMask & groupLaneValid.asUInt

  calculatedResponse.instructionMask := instructionMask
  calculatedResponse.branchMask := branchMask
  calculatedResponse.conditionalMask := conditionalBranches.asUInt
  calculatedResponse.takenMask := takenMask.asUInt
  calculatedResponse.redirect := redirect
  calculatedResponse.redirectTarget := selectedRedirectTarget
  calculatedResponse.targets := logicalTargets
  calculatedResponse.selectedWay := selectedWay
  calculatedResponse.selectedCall := selectedCall
  calculatedResponse.selectedReturn := selectedReturn
  calculatedResponse.selectedCallReturnAddress := selectedCallReturnAddress
  for (way <- 0 until fetchWidth) {
    calculatedResponse.recovery(way).globalHistory := queryGlobalHistory
    calculatedResponse.recovery(way).precedingBranches :=
      (if (way == 0) 0.U else PopCount(branchMask(way - 1, 0)))
  }

  val physicalPredictionHits = VecInit((0 until fetchWidth).map { bank =>
    physicalQueryBankValid(bank) && physicalRedirectHits(bank)
  })
  val physicalRedirectPrefixMask = Cat(
    !(physicalTakenCandidates(0) || physicalTakenCandidates(1) ||
      physicalTakenCandidates(2)),
    !(physicalTakenCandidates(0) || physicalTakenCandidates(1)),
    !physicalTakenCandidates(0),
    true.B
  )
  val calculatedBranchCount =
    PopCount(physicalRedirectPrefixMask & physicalPredictionHits.asUInt)
  when(responseValid && !io.localInvalidate) {
    assert(
      !redirect || calculatedBranchCount =/= 0.U,
      "a taken prediction must advance at least one history bit"
    )
  }
  val calculatedPostGlobalHistory = BranchPredictorParameters.advanceHistory(
    queryGlobalHistory,
    calculatedBranchCount,
    redirect
  )
  val calculatedPostFoldedHistory = BranchPredictorParameters.advanceFoldedHistory(
    queryGlobalHistory,
    calculatedBranchCount,
    redirect
  )

  val statePhysicalPredictionHits = VecInit((0 until fetchWidth).map { bank =>
    stateQueryBankValid(bank) && stateRedirectHits(bank)
  })
  val calculatedPostFoldedHistoryForPht =
    BranchPredictorParameters.advanceFoldedHistoryByPhysicalLaneWalk(
      queryGlobalHistory,
      statePhysicalPredictionHits.toSeq,
      stateTakenCandidates.toSeq
    )
  when(responseValid) {
    assert(
      cacheQueryPartialTag === queryTag(btbRedirectTagWidth - 1, 0) &&
        stateQueryPartialTag === queryTag(btbRedirectTagWidth - 1, 0),
      "redirect-domain partial tags must match the retained query"
    )
    assert(
      cacheQueryBankValid.asUInt === physicalQueryBankValid.asUInt &&
        stateQueryBankValid.asUInt === physicalQueryBankValid.asUInt,
      "redirect-domain bank masks must match the retained query"
    )
    assert(
      stateRedirectHits.asUInt === cacheRedirectHits.asUInt,
      "state redirect hits must match the live redirect mirror"
    )
    assert(
      stateTakenCandidates.asUInt === cacheTakenCandidates.asUInt,
      "state redirect decisions must match the live redirect mirror"
    )
    assert(
      indexStateTakenCandidates.asUInt === stateTakenCandidates.asUInt,
      "index-state redirect decisions must match the state mirror"
    )
    assert(
      cacheTakenCandidates.asUInt === physicalTakenCandidates.asUInt,
      "cache-local redirect mirror must match the predictor mirror"
    )
    assert(
      cacheSetTakenCandidates.asUInt === cacheTakenCandidates.asUInt,
      "cache-set-local redirect decisions must match the live redirect mirror"
    )
    assert(
      stateInstructionMask === instructionMask,
      "state-local H64 instruction mask must match the predictor response"
    )
    assert(
      stateTakenMask === takenMask.asUInt,
      "state-local H64 taken mask must match the predictor response"
    )
    assert(
      stateConditionalMask ===
        (stateConditionalBranches.asUInt & instructionMask),
      "state-local H64 conditional state must match the predictor response"
    )
    assert(
      indexStateTakenMask === stateTakenMask,
      "index-state taken mask must match the state mirror"
    )
    assert(
      indexStateConditionalMask === stateConditionalMask,
      "index-state conditional mask must match the state mirror"
    )
    assert(
      calculatedPostFoldedHistoryForPht === calculatedPostFoldedHistory,
      "PHT-local history must match the retained predictor response"
    )
  }

  val calculatedPostNeuralHistory = WireDefault(neuralHistory)
  val calculatedPostNeuralPath = WireDefault(neuralPath)
  val calculatedPostNeuralIndexState = WireDefault(neuralIndexState)
  h64Corrector.foreach { corrector =>
    corrector.io.fast.valid := responseValid
    corrector.io.fast.bits.token := queryToken
    corrector.io.fast.bits.epoch := queryEpoch
    corrector.io.fast.bits.instructionMask := stateInstructionMask
    corrector.io.fast.bits.stateConditionalMask := stateConditionalMask
    corrector.io.indexStateConditionalMask := indexStateConditionalMask
    corrector.io.fast.bits.conditionalMask := conditionalBranches.asUInt
    corrector.io.fast.bits.takenMask := stateTakenMask
    corrector.io.indexStateTakenMask := indexStateTakenMask
    corrector.io.fast.bits.directionBias := directionBiases.asUInt
    for (way <- 0 until fetchWidth) {
      val bank = (stateQueryOffset + way.U)(1, 0)
      corrector.io.fast.bits.phtCounters(way) := pht.io.physicalResponse(bank)
    }
    calculatedPostNeuralHistory := corrector.io.fastPostHistory
    calculatedPostNeuralPath := corrector.io.fastPostPath
    calculatedPostNeuralIndexState := corrector.io.fastPostIndexState
  }

  val heldResponseValid = RegInit(false.B)
  val heldResponse = Reg(new FetchPrediction)
  val heldPostGlobalHistory = Reg(UInt(historyWidth.W))
  val heldPostFoldedHistory = Reg(UInt(BranchPredictorParameters.foldedHistoryWidth.W))
  val heldPostNeuralHistory = Reg(UInt(H64CorrectorParameters.historyWidth.W))
  val heldPostNeuralPath = Reg(UInt(H64CorrectorParameters.pathWidth.W))
  val heldPostNeuralIndexState = Reg(UInt(H64CorrectorParameters.indexWidth.W))

  val liveRedirectValid =
    !io.flush && !io.localInvalidate && !heldResponseValid && responseValid && redirect
  val liveCacheRedirectValid =
    !io.flush && !io.localInvalidate && !heldResponseValid && responseValid &&
      cacheTakenCandidates.asUInt.orR

  val phtFeedbackRedirectValid =
    !io.localInvalidate && !heldResponseValid && responseValid &&
      stateTakenCandidates.asUInt.orR

  io.liveRedirectTarget.valid := liveCacheRedirectValid
  io.liveRedirectTarget.bits := selectedRedirectCacheTarget
  io.pcRedirect.valid := !io.flush && !io.localInvalidate && Mux(
    heldResponseValid,
    heldResponse.redirect,
    liveCacheRedirectValid
  )
  io.pcRedirect.bits := Mux(
    heldResponseValid,
    heldResponse.redirectTarget,
    selectedRedirectCacheTarget
  )
  io.liveRedirectReadSet.valid := liveCacheRedirectValid
  io.liveRedirectReadSet.bits := selectedRedirectCacheSet
  when(liveRedirectValid || liveCacheRedirectValid) {
    assert(
      liveRedirectValid === liveCacheRedirectValid,
      "cache-local redirect valid must match the predictor redirect"
    )
  }
  when(liveCacheRedirectValid) {
    assert(
      selectedRedirectCacheSet === selectedRedirectTarget(11, 6),
      "cache-local redirect set must match the selected target"
    )
    assert(
      selectedRedirectCacheTarget === selectedRedirectTarget,
      "cache-local full redirect target must match the selected target"
    )
  }
  val heldStateFeedbackRedirectValid =
    !io.flush && !io.localInvalidate && heldResponseValid && heldResponse.redirect
  when(heldStateFeedbackRedirectValid && io.liveRedirectQueryAllowed) {
    stateFeedbackQueryPcFragment := heldResponse.redirectTarget(17, 2)
    phtReadPcWord := Cat(heldResponse.redirectTarget(14, 4), 0.U(2.W))
  }.elsewhen(phtFeedbackRedirectValid && io.liveRedirectQueryAllowed) {

    phtReadPcWord := selectedRedirectPhtPcWord
    assert(
      selectedRedirectPhtPcWord === Cat(selectedRedirectTarget(14, 4), 0.U(2.W)),
      "PHT-local redirect PC must match the aligned selected target"
    )
    stateFeedbackQueryPcFragment := selectedStateTargetFragment
    assert(
      selectedStateTargetFragment === selectedRedirectTarget(17, 2),
      "state-local redirect fragment must match the selected target"
    )
  }
  when(io.query.valid) {
    assert(
      stateFeedbackQueryPcFragment === io.query.bits.pc(17, 2),
      "state-local query fragment must match the accepted architectural query"
    )
  }

  io.response.valid := !io.flush && !io.localInvalidate && (heldResponseValid || responseValid)
  io.response.bits := Mux(heldResponseValid, heldResponse, calculatedResponse)

  when(io.flush || io.localInvalidate) {
    heldResponseValid := false.B
  }.otherwise {
    when(heldResponseValid) {
      when(io.consumeResponse) {
        when(responseValid) {
          heldResponse := calculatedResponse
          heldPostGlobalHistory := calculatedPostGlobalHistory
          heldPostFoldedHistory := calculatedPostFoldedHistory
          heldPostNeuralHistory := calculatedPostNeuralHistory
          heldPostNeuralPath := calculatedPostNeuralPath
          heldPostNeuralIndexState := calculatedPostNeuralIndexState
          heldResponseValid := true.B
        }.otherwise {
          heldResponseValid := false.B
        }
      }.otherwise {
        assert(!responseValid, "predictor received a new lookup while IF2 response was stalled")
      }
    }.elsewhen(responseValid && !io.consumeResponse) {
      heldResponse := calculatedResponse
      heldPostGlobalHistory := calculatedPostGlobalHistory
      heldPostFoldedHistory := calculatedPostFoldedHistory
      heldPostNeuralHistory := calculatedPostNeuralHistory
      heldPostNeuralPath := calculatedPostNeuralPath
      heldPostNeuralIndexState := calculatedPostNeuralIndexState
      heldResponseValid := true.B
    }
  }

  val consumed = io.response.valid && io.consumeResponse
  val consumedGlobalHistory =
    Mux(heldResponseValid, heldPostGlobalHistory, calculatedPostGlobalHistory)
  val consumedNeuralHistory =
    Mux(heldResponseValid, heldPostNeuralHistory, calculatedPostNeuralHistory)
  val consumedNeuralPath =
    Mux(heldResponseValid, heldPostNeuralPath, calculatedPostNeuralPath)
  val consumedNeuralIndexState =
    Mux(heldResponseValid, heldPostNeuralIndexState, calculatedPostNeuralIndexState)
  when(!(io.update.valid && io.update.bits.mispredict)) {

    when(!io.flush && !io.localInvalidate && heldResponseValid) {
      readGlobalHistory := heldPostGlobalHistory
      readFoldedHistory := heldPostFoldedHistory
      readNeuralHistory := heldPostNeuralHistory
      readNeuralPath := heldPostNeuralPath
      readNeuralIndexState := heldPostNeuralIndexState
    }.elsewhen(!io.flush && !io.localInvalidate && responseValid) {
      readGlobalHistory := calculatedPostGlobalHistory
      readFoldedHistory := calculatedPostFoldedHistory
      readNeuralHistory := calculatedPostNeuralHistory
      readNeuralPath := calculatedPostNeuralPath
      readNeuralIndexState := calculatedPostNeuralIndexState
    }

    when(!io.localInvalidate && !io.backendRedirectPhtPcWord.valid && heldResponseValid) {
      phtReadFoldedHistory := heldPostFoldedHistory
    }.elsewhen(
      !io.localInvalidate && !io.backendRedirectPhtPcWord.valid && responseValid
    ) {
      phtReadFoldedHistory := calculatedPostFoldedHistoryForPht
    }
  }
  when(consumed) {
    globalHistory := consumedGlobalHistory
    neuralHistory := consumedNeuralHistory
    neuralPath := consumedNeuralPath
    neuralIndexState := consumedNeuralIndexState
  }

  when(responseValid && !io.localInvalidate) {
    when(calculatedResponse.redirect && calculatedResponse.selectedCall) {
      ras((rasTop + 1.U)(2, 0)) :=
        calculatedResponse.selectedCallReturnAddress
      rasTop := rasTop + 1.U
    }.elsewhen(calculatedResponse.redirect && calculatedResponse.selectedReturn) {
      rasTop := rasTop - 1.U
    }
  }

  val retiresCall =
    io.update.valid && io.update.bits.branchLike && io.update.bits.isCall
  val retiresReturn =
    io.update.valid && io.update.bits.branchLike && io.update.bits.isReturn
  val committedRasNext = WireDefault(committedRas)
  val committedRasTopNext = WireDefault(committedRasTop)
  when(retiresCall) {
    committedRasTopNext := committedRasTop + 1.U
    committedRasNext((committedRasTop + 1.U)(2, 0)) :=
      (io.update.bits.pc >> 2) + 1.U
  }.elsewhen(retiresReturn) {
    committedRasTopNext := committedRasTop - 1.U
  }
  when(retiresCall || retiresReturn) {
    committedRas := committedRasNext
    committedRasTop := committedRasTopNext
  }

  val rasSnapshots = Mem(1 << H64CorrectorParameters.tokenWidth, new PredictionRasSnapshot)

  val rasSnapshotWritePending = RegNext(io.query.valid, false.B)
  val pendingRasSnapshotWriteToken =
    RegEnable(io.query.bits.token, io.query.valid)
  Seq(rasSnapshotWritePending, pendingRasSnapshotWriteToken).foreach { state =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(state.toTarget, "dont_touch = \"yes\"")
    })
  }

  val pendingCorrectedRasSnapshot =
    rasSnapshots.read(pendingLateCorrection.token)
  val pendingRasSnapshotWriteData = Wire(new PredictionRasSnapshot)
  pendingRasSnapshotWriteData.stack := ras
  pendingRasSnapshotWriteData.top := rasTop
  when(rasSnapshotWritePending) {
    rasSnapshots.write(pendingRasSnapshotWriteToken, pendingRasSnapshotWriteData)
  }

  when(retiresConditional) {
    committedNeuralHistory := committedNeuralHistoryNext
    committedNeuralPath := committedNeuralPathNext
  }

  when(io.update.valid) {
    val update = io.update.bits
    val index = update.pc(11, 2)
    val tag = update.pc(31, 12)

    val directionBias =
      Mux(update.conditionalBranch, update.target <= update.pc, true.B)
    val packedEntry = Cat(
      true.B,
      tag,
      update.target(31, 2),
      update.conditionalBranch,
      update.staticTarget,
      update.isCall,
      update.isReturn,
      directionBias
    )
    val phtAddress =
      BranchPredictorParameters.phtAddress(update.pc, update.recovery.globalHistory)

    val phtOutcome = update.taken ^ directionBias

    when(update.prediction.predictsBranch && !update.branchLike) {
      btb.io.write.valid := true.B
      btb.io.write.bits.address := index
      btb.io.write.bits.data := 0.U
    }.elsewhen(!update.prediction.predictsBranch && update.branchLike) {
      btb.io.write.valid := true.B
      btb.io.write.bits.address := index
      btb.io.write.bits.data := packedEntry
      pht.io.update.valid := !update.staticTarget
      pht.io.update.bits.address := phtAddress
      pht.io.update.bits.taken := phtOutcome
      pht.io.update.bits.set := true.B
      pht.io.update.bits.setValue :=
        Mux(
          update.conditionalBranch,
          Mux(phtOutcome, 2.U, 1.U),
          0.U
        )
    }.elsewhen(update.prediction.predictsBranch && update.branchLike) {
      btb.io.write.valid := true.B
      btb.io.write.bits.address := index
      btb.io.write.bits.data := packedEntry
      pht.io.update.valid := !update.staticTarget
      pht.io.update.bits.address := phtAddress
      pht.io.update.bits.taken := phtOutcome
      pht.io.update.bits.set := !update.conditionalBranch
      pht.io.update.bits.setValue := 0.U
    }

    when(update.mispredict) {
      globalHistory := recoveredHistory
    }
  }

  when(io.flush || (io.update.valid && io.update.bits.mispredict)) {
    ras := committedRasNext
    rasTop := committedRasTopNext
  }

  when(io.flush) {
    neuralHistory := committedNeuralHistoryNext
    neuralPath := committedNeuralPathNext
    neuralIndexState := committedNeuralIndexStateNext
  }.elsewhen(lateCorrectionPending) {
    globalHistory := pendingCorrectedGlobalHistory
    neuralHistory := pendingCorrectedNeuralHistory
    neuralPath := pendingCorrectedNeuralPath
    neuralIndexState := pendingCorrectedNeuralIndexState
    ras := pendingCorrectedRasSnapshot.stack
    rasTop := pendingCorrectedRasSnapshot.top
  }
}
