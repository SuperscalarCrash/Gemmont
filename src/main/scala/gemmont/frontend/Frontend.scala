package gemmont.frontend

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.FrontendConfig
import gemmont.cache.{CacheMaintenanceRequest, InstructionCache}
import gemmont.common.{LineReadReq, LineReadResp}
import gemmont.debug.{CacheProfileObservation, FrontendProfileObservation}
import gemmont.isa.{LoongArch, MemoryAccess}
import gemmont.privilege.{AddressTranslator, TlbEntry, TranslationControl}

private[frontend] class BufferedFetchPacket extends Bundle {
  val pc = UInt(32.W)
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
  val words = Vec(4, UInt(32.W))
  val mask = UInt(4.W)
  val exception = Valid(new gemmont.decode.ExceptionPayload)
  val branchMask = UInt(4.W)
  val takenMask = UInt(4.W)
  val redirectTarget = UInt(32.W)
  val targets = Vec(4, UInt(32.W))
  val recovery = Vec(4, new PredictionRecovery)
}

private[frontend] class H64PredictionSidecar(detailedTrace: Boolean = false) extends Bundle {
  val pc = UInt(32.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
  val history = UInt(H64CorrectorParameters.historyWidth.W)
  val path = UInt(H64CorrectorParameters.pathWidth.W)
  val conditionalMask = UInt(4.W)
  val fastTakenMask = UInt(4.W)
  val neuralTakenMask = UInt(4.W)
  val reliableMask = UInt(4.W)
  val scores = if (detailedTrace) Some(Vec(4, SInt(H64CorrectorParameters.scoreWidth.W))) else None
  val phtCounters = if (detailedTrace) Some(Vec(4, UInt(2.W))) else None
  val directionBias = if (detailedTrace) Some(UInt(4.W)) else None
}

class Frontend(
    config: FrontendConfig = FrontendConfig(),
    tlbEntries: Int = 32,
    phtInitializationFile: String = "src/main/resources/pht-init.hex",
    btbInitializationFile: String = "src/main/resources/btb-init.hex",
    h64InitializationFile: String = "src/main/resources/h64-residual-weights-int4.hex"
) extends Module {
  val io = IO(new Bundle {
    val backendRedirect = Input(Valid(UInt(32.W)))
    val predictorUpdate = Input(Valid(new PredictorUpdate))
    val committedBranches = Input(Vec(3, Valid(new H64CommittedBranch)))
    val flush = Input(Bool())
    val waiting = Input(Bool())
    val translationControl = Input(new TranslationControl)
    val tlb = Input(Vec(tlbEntries, new TlbEntry))
    val maintenance = Input(Valid(new CacheMaintenanceRequest))

    val decode = Vec(3, Decoupled(new FetchBufferEntry))
    val lineReadReq = Decoupled(new LineReadReq)
    val lineReadResp = Flipped(Decoupled(new LineReadResp))
    val debugPc = Output(UInt(32.W))
    val h64LateCorrection = Output(Bool())
    val profileObservation = Output(new FrontendProfileObservation)
    val profile = Output(new CacheProfileObservation)
  })

  val programCounter = Module(new ProgramCounter(config.resetPc))
  val predictor = Module(
    new BranchPredictor(
      phtInitializationFile = phtInitializationFile,
      btbInitializationFile = btbInitializationFile,
      h64InitializationFile = h64InitializationFile,
      h64Config = config.h64
    )
  )
  val translator = Module(new AddressTranslator)
  val instructionCache = Module(new InstructionCache(config.icache, config))
  val fetchBuffer = Module(new FetchBuffer)

  io.lineReadReq <> instructionCache.io.lineReadReq
  instructionCache.io.lineReadResp <> io.lineReadResp
  val lateCorrectionFire = WireDefault(false.B)
  val lateCorrectionTarget = WireDefault(0.U(32.W))
  val predictorLateCorrection = WireDefault(0.U.asTypeOf(Valid(new LatePredictionCorrection)))
  val frontendFlush = io.flush || lateCorrectionFire

  val lateCorrectionTruncatePending = RegNext(lateCorrectionFire, false.B)
  fetchBuffer.io.flush := io.flush || lateCorrectionTruncatePending
  instructionCache.io.maintenance := io.maintenance

  val lookupPc = RegInit(config.resetPc.U(32.W))

  val translationLookupPc = RegInit(config.resetPc.U(32.W))
  dontTouch(translationLookupPc)
  translator.io.request.virtualAddress := translationLookupPc
  translator.io.request.access := MemoryAccess.Fetch
  translator.io.control := io.translationControl
  translator.io.entries := io.tlb
  io.debugPc := lookupPc
  io.profile := instructionCache.io.profile

  predictor.io.update := io.predictorUpdate
  predictor.io.committedBranches := io.committedBranches
  predictor.io.flush := io.flush
  predictor.io.lateCorrection := predictorLateCorrection
  predictor.io.localInvalidate := lateCorrectionTruncatePending
  predictor.io.query.valid := false.B
  predictor.io.query.bits := 0.U.asTypeOf(predictor.io.query.bits)
  predictor.io.backendRedirectPhtPcWord.valid := false.B
  predictor.io.backendRedirectPhtPcWord.bits := 0.U
  predictor.io.backendRedirectPhtUsesRecoveryHistory := false.B

  val misaligned = lookupPc(1, 0) =/= 0.U
  val translationException = translator.io.result.exception
  val translationFault = misaligned || translationException.tlbRefill ||
    translationException.pageInvalidFetch || translationException.pagePrivilege
  val faultCode = WireDefault(LoongArch.ExceptionCode.PagePrivilege.code.U(6.W))
  val faultSubcode = WireDefault(LoongArch.ExceptionCode.PagePrivilege.subcode.U(9.W))
  val faultTlbRefill = WireDefault(false.B)
  when(translationException.pageInvalidFetch) {
    faultCode := LoongArch.ExceptionCode.PageInvalidFetch.code.U
    faultSubcode := LoongArch.ExceptionCode.PageInvalidFetch.subcode.U
  }
  when(translationException.tlbRefill) {
    faultCode := LoongArch.ExceptionCode.TlbRefill.code.U
    faultSubcode := LoongArch.ExceptionCode.TlbRefill.subcode.U
    faultTlbRefill := true.B
  }
  when(misaligned) {
    faultCode := LoongArch.ExceptionCode.AddressErrorFetch.code.U
    faultSubcode := LoongArch.ExceptionCode.AddressErrorFetch.subcode.U
    faultTlbRefill := false.B
  }

  val nextLookupPc = programCounter.io.nextPc
  val nextDirectLookup =
    io.translationControl.directAddress && !io.translationControl.paging
  def directMapValid(
      window: gemmont.privilege.DirectMapWindow,
      virtualAddress: UInt
  ): Bool = {
    val privilegeValid =
      (window.privilege0 && io.translationControl.privilege === 0.U) ||
        (window.privilege3 && io.translationControl.privilege === 3.U)
    privilegeValid && virtualAddress(31, 29) === window.virtualSegment
  }
  val nextDirectMap0Valid =
    directMapValid(io.translationControl.directMap0, nextLookupPc)
  val nextDirectMap1Valid =
    directMapValid(io.translationControl.directMap1, nextLookupPc)
  val nextDirectMapLookup =
    !io.translationControl.directAddress && io.translationControl.paging &&
      (nextDirectMap0Valid || nextDirectMap1Valid)
  val nextFastLookup = nextDirectLookup || nextDirectMapLookup
  val nextFastPhysicalAddress = Mux(
    nextDirectLookup,
    nextLookupPc,
    Mux(
      nextDirectMap0Valid,
      Cat(io.translationControl.directMap0.physicalSegment, nextLookupPc(28, 0)),
      Cat(io.translationControl.directMap1.physicalSegment, nextLookupPc(28, 0))
    )
  )
  val registeredFastLookup = RegInit(true.B)
  val registeredFastPhysicalAddress = RegInit(config.resetPc.U(32.W))
  val registeredPhysicalAddress = Reg(UInt(32.W))
  val registeredTranslationFault = Reg(Bool())
  val registeredFaultCode = Reg(UInt(6.W))
  val registeredFaultSubcode = Reg(UInt(9.W))
  val registeredFaultTlbRefill = Reg(Bool())
  val registeredTranslationReady = RegInit(false.B)
  val nextPredictionToken = RegInit(0.U(H64CorrectorParameters.tokenWidth.W))
  val predictionEpoch = RegInit(0.U(H64CorrectorParameters.epochWidth.W))
  val effectivePhysicalAddress =
    Mux(registeredFastLookup, registeredFastPhysicalAddress, registeredPhysicalAddress)
  val effectiveTranslationFault =
    Mux(registeredFastLookup, misaligned, registeredTranslationFault)
  val effectiveFaultCode = Mux(
    registeredFastLookup,
    LoongArch.ExceptionCode.AddressErrorFetch.code.U(6.W),
    registeredFaultCode
  )
  val effectiveFaultSubcode = Mux(
    registeredFastLookup,
    LoongArch.ExceptionCode.AddressErrorFetch.subcode.U(9.W),
    registeredFaultSubcode
  )
  val effectiveFaultTlbRefill =
    Mux(registeredFastLookup, false.B, registeredFaultTlbRefill)

  val packetValid = instructionCache.io.response.valid
  val packetPc = instructionCache.io.response.bits.pc
  val cacheMask = instructionCache.io.response.bits.validMask
  val predictionValid = predictor.io.response.valid && predictor.io.response.bits.pc === packetPc
  val instructionMask = Mux(predictionValid, predictor.io.response.bits.instructionMask, 0.U)
  val packetMask = cacheMask & instructionMask

  val redirectPending = RegInit(false.B)
  val redirectPendingTarget = Reg(UInt(32.W))

  val redirectPendingUsesRecoveryHistory = RegInit(false.B)
  val redirectCandidate = Wire(Valid(UInt(32.W)))

  val targetlessFlush = io.flush && !io.backendRedirect.valid
  redirectCandidate.valid := redirectPending || io.backendRedirect.valid

  redirectCandidate.bits := Mux(
    io.backendRedirect.valid,
    io.backendRedirect.bits,
    redirectPendingTarget
  )
  val redirectCandidateUsesRecoveryHistory =
    Mux(io.backendRedirect.valid, true.B, redirectPendingUsesRecoveryHistory)

  val bufferedPacket = Reg(new BufferedFetchPacket)
  val bufferedValid = RegInit(false.B)
  val bufferedCanDrain = (0 until 4)
    .map { lane =>
      !bufferedPacket.mask(lane) || fetchBuffer.io.push(lane).ready
    }
    .reduce(_ && _)

  val bufferedFire = bufferedValid && bufferedCanDrain && !io.waiting
  val bufferReady = !bufferedValid || bufferedFire
  val packetCanAdvance = packetValid && predictionValid && bufferReady

  val packetCapture =
    packetValid && predictionValid && bufferReady && !io.flush && !io.waiting &&
      !redirectCandidate.valid
  val packetFire = packetCapture && !lateCorrectionFire

  val redirectPipeReady = !io.waiting && !instructionCache.io.pipelineBlocked &&
    (!packetValid || packetCanAdvance || io.flush || lateCorrectionTruncatePending)
  val applyBackendRedirect =
    redirectCandidate.valid && redirectPipeReady && !targetlessFlush

  val discardForBackendRedirect = applyBackendRedirect
  val normalResponseCapacity =
    predictionValid && bufferReady && !io.flush && !io.waiting &&
      !redirectCandidate.valid

  val backendReplacementCapacity =
    redirectCandidate.valid && predictionValid && bufferReady && !io.waiting
  instructionCache.io.lookupAdvance :=
    normalResponseCapacity || backendReplacementCapacity || io.flush ||
      lateCorrectionTruncatePending
  instructionCache.io.response.ready :=
    normalResponseCapacity || discardForBackendRedirect || lateCorrectionTruncatePending

  when(io.backendRedirect.valid && !redirectPipeReady) {
    redirectPending := true.B
    redirectPendingTarget := io.backendRedirect.bits
    redirectPendingUsesRecoveryHistory := true.B
  }
  when(redirectPending && redirectPipeReady) {
    redirectPending := false.B
  }
  when(targetlessFlush) {
    redirectPending := false.B
  }

  when(lateCorrectionFire) {
    redirectPending := true.B
    redirectPendingTarget := lateCorrectionTarget
    redirectPendingUsesRecoveryHistory := false.B
  }
  when(io.backendRedirect.valid) {
    assert(
      redirectCandidate.bits === io.backendRedirect.bits,
      "backend recovery must override a pending H64 redirect"
    )
  }

  for (lane <- 0 until 4) {
    val push = fetchBuffer.io.push(lane)
    push.valid := bufferedFire && bufferedPacket.mask(lane)
    push.bits := 0.U.asTypeOf(push.bits)
    push.bits.pc := bufferedPacket.pc + (lane * 4).U
    push.bits.instruction := bufferedPacket.words(lane)
    push.bits.exception.valid := bufferedPacket.exception.valid && lane.U === 0.U
    push.bits.exception.bits := bufferedPacket.exception.bits
    push.bits.prediction.predictsBranch := bufferedPacket.branchMask(lane)
    push.bits.prediction.taken := bufferedPacket.takenMask(lane)
    push.bits.prediction.target := Mux(
      bufferedPacket.takenMask(lane),
      bufferedPacket.redirectTarget,
      bufferedPacket.targets(lane)
    )
    push.bits.recovery := bufferedPacket.recovery(lane)
    push.bits.predictionToken := bufferedPacket.token
    push.bits.predictionEpoch := bufferedPacket.epoch
    push.bits.predictionWay := lane.U
    val laterLaneValid =
      if (lane == 3) false.B else bufferedPacket.mask(3, lane + 1).orR
    push.bits.predictionTokenLast :=
      bufferedPacket.mask(lane) && !laterLaneValid
    push.bits.h64AlternativeTarget := Mux(
      bufferedPacket.takenMask(lane),
      bufferedPacket.pc + ((lane + 1) * 4).U,
      bufferedPacket.targets(lane)
    )
  }

  private val h64TokenCount = 1 << H64CorrectorParameters.tokenWidth
  val h64ResultValids = RegInit(VecInit(Seq.fill(h64TokenCount)(false.B)))
  val h64TokenAllocated = RegInit(VecInit(Seq.fill(h64TokenCount)(false.B)))

  val h64ResultsByLane = Seq.fill(3)(
    Mem(h64TokenCount, new H64PredictionSidecar(config.h64.detailedTrace))
  )
  val liveH64Sidecar = Wire(new H64PredictionSidecar(config.h64.detailedTrace))
  liveH64Sidecar.pc := predictor.io.h64Result.bits.pc
  liveH64Sidecar.epoch := predictor.io.h64Result.bits.epoch
  liveH64Sidecar.history := predictor.io.h64Result.bits.history
  liveH64Sidecar.path := predictor.io.h64Result.bits.path
  liveH64Sidecar.conditionalMask := predictor.io.h64Result.bits.conditionalMask
  liveH64Sidecar.fastTakenMask := predictor.io.h64Result.bits.fastTakenMask
  liveH64Sidecar.neuralTakenMask := predictor.io.h64Result.bits.neuralTakenMask
  liveH64Sidecar.reliableMask := predictor.io.h64Result.bits.reliableMask
  if (config.h64.detailedTrace) {
    liveH64Sidecar.scores.get := predictor.io.h64Result.bits.scores
    liveH64Sidecar.phtCounters.get := predictor.io.h64Result.bits.phtCounters
    liveH64Sidecar.directionBias.get := predictor.io.h64Result.bits.directionBias
  }

  when(
    predictor.io.h64Result.valid &&
      h64TokenAllocated(predictor.io.h64Result.bits.token)
  ) {
    h64ResultValids(predictor.io.h64Result.bits.token) := true.B
    h64ResultsByLane.foreach(
      _.write(predictor.io.h64Result.bits.token, liveH64Sidecar)
    )
  }

  val selectedH64Results = Wire(
    Vec(3, new H64PredictionSidecar(config.h64.detailedTrace))
  )
  val selectedH64ResultValid = Wire(Vec(3, Bool()))
  val h64Conditional = Wire(Vec(3, Bool()))
  val h64NeuralTaken = Wire(Vec(3, Bool()))
  val h64Reliable = Wire(Vec(3, Bool()))
  val h64Disagreement = Wire(Vec(3, Bool()))
  val h64HistoryBefore = Wire(Vec(3, UInt(H64CorrectorParameters.historyWidth.W)))
  val h64PathBefore = Wire(Vec(3, UInt(H64CorrectorParameters.pathWidth.W)))

  val h64TokenRetires = Wire(Vec(h64TokenCount, Bool()))
  for (token <- 0 until h64TokenCount) {
    h64TokenRetires(token) := (0 until 3)
      .map { lane =>
        fetchBuffer.io.pop(lane).fire &&
        fetchBuffer.io.pop(lane).bits.predictionTokenLast &&
        fetchBuffer.io.pop(lane).bits.predictionToken === token.U
      }
      .reduce(_ || _)
  }

  for (lane <- 0 until 3) {
    val entry = fetchBuffer.io.pop(lane).bits
    val way = entry.predictionWay
    val stored = h64ResultsByLane(lane).read(entry.predictionToken)
    val storedMatch = h64TokenAllocated(entry.predictionToken) &&
      h64ResultValids(entry.predictionToken) &&
      stored.epoch === entry.predictionEpoch
    val liveMatch = h64TokenAllocated(entry.predictionToken) &&
      predictor.io.h64Result.valid &&
      predictor.io.h64Result.bits.token === entry.predictionToken &&
      predictor.io.h64Result.bits.epoch === entry.predictionEpoch
    selectedH64ResultValid(lane) := storedMatch || liveMatch
    selectedH64Results(lane) := Mux(liveMatch, liveH64Sidecar, stored)

    h64Conditional(lane) :=
      selectedH64ResultValid(lane) && selectedH64Results(lane).conditionalMask(way)
    h64NeuralTaken(lane) :=
      selectedH64ResultValid(lane) && selectedH64Results(lane).neuralTakenMask(way)
    h64Reliable(lane) :=
      selectedH64ResultValid(lane) && selectedH64Results(lane).reliableMask(way)
    h64Disagreement(lane) := config.h64.enabled.B && h64Conditional(lane) &&
      h64Reliable(lane) &&
      h64NeuralTaken(lane) =/= entry.prediction.taken

    val historyByWay = Wire(Vec(5, UInt(H64CorrectorParameters.historyWidth.W)))
    val pathByWay = Wire(Vec(5, UInt(H64CorrectorParameters.pathWidth.W)))
    historyByWay(0) := selectedH64Results(lane).history
    pathByWay(0) := selectedH64Results(lane).path
    for (wayIndex <- 0 until 4) {
      val conditional = selectedH64Results(lane).conditionalMask(wayIndex)
      val taken = selectedH64Results(lane).fastTakenMask(wayIndex)
      val branchPc = selectedH64Results(lane).pc + (wayIndex * 4).U
      historyByWay(wayIndex + 1) := Mux(
        conditional,
        Cat(
          historyByWay(wayIndex)(H64CorrectorParameters.historyWidth - 2, 0),
          taken
        ),
        historyByWay(wayIndex)
      )
      pathByWay(wayIndex + 1) := Mux(
        conditional,
        (pathByWay(wayIndex) << 1) ^ (branchPc >> 2),
        pathByWay(wayIndex)
      )
    }
    h64HistoryBefore(lane) := historyByWay(way)
    h64PathBefore(lane) := pathByWay(way)
  }

  val h64CorrectionSelect = Wire(Vec(3, Bool()))
  for (lane <- 0 until 3) {
    val olderDisagreement =
      if (lane == 0) false.B else h64Disagreement.take(lane).reduce(_ || _)
    h64CorrectionSelect(lane) :=
      fetchBuffer.io.pop(lane).valid &&
        h64Disagreement(lane) && !olderDisagreement &&
        !io.flush && !io.backendRedirect.valid
  }

  for (lane <- 0 until 3) {
    val suppressed =
      if (lane == 0) false.B else h64CorrectionSelect.take(lane).reduce(_ || _)
    io.decode(lane).valid :=
      fetchBuffer.io.pop(lane).valid && !lateCorrectionTruncatePending && !suppressed
    io.decode(lane).bits := fetchBuffer.io.pop(lane).bits
    when(h64CorrectionSelect(lane)) {
      io.decode(lane).bits.prediction.taken := h64NeuralTaken(lane)
    }
    fetchBuffer.io.pop(lane).ready :=
      io.decode(lane).ready && !lateCorrectionTruncatePending && !suppressed
  }

  val h64CorrectionFires = VecInit((0 until 3).map { lane =>
    h64CorrectionSelect(lane) && fetchBuffer.io.pop(lane).fire
  })
  lateCorrectionFire := h64CorrectionFires.asUInt.orR
  io.h64LateCorrection := lateCorrectionFire
  when(lateCorrectionFire) {
    assert(PopCount(h64CorrectionFires) === 1.U, "H64 must correct exactly one oldest lane")
    assert(!io.flush && !io.backendRedirect.valid, "backend recovery must suppress H64 correction")
  }

  val correctedEntry = Mux1H(h64CorrectionSelect, fetchBuffer.io.pop.map(_.bits))
  val correctedTaken = Mux1H(h64CorrectionSelect, h64NeuralTaken)

  val h64CorrectionTargets = VecInit(
    fetchBuffer.io.pop.map(_.bits.h64AlternativeTarget)
  )
  lateCorrectionTarget := Mux1H(h64CorrectionSelect, h64CorrectionTargets)
  predictorLateCorrection.valid := lateCorrectionFire
  predictorLateCorrection.bits.token := correctedEntry.predictionToken
  predictorLateCorrection.bits.recovery := correctedEntry.recovery
  predictorLateCorrection.bits.neuralHistoryBefore := Mux1H(
    h64CorrectionSelect,
    h64HistoryBefore
  )
  predictorLateCorrection.bits.neuralPathBefore := Mux1H(
    h64CorrectionSelect,
    h64PathBefore
  )
  predictorLateCorrection.bits.pc := correctedEntry.pc
  predictorLateCorrection.bits.taken := correctedTaken

  for (token <- 0 until h64TokenCount) {
    when(h64TokenRetires(token)) {
      h64ResultValids(token) := false.B
      h64TokenAllocated(token) := false.B
    }
    when(frontendFlush) {
      h64ResultValids(token) := false.B
      h64TokenAllocated(token) := false.B
    }
  }

  val predictionRedirect = packetCapture && predictor.io.response.bits.redirect

  programCounter.io.prediction := predictor.io.pcRedirect
  programCounter.io.predictionReadSet := predictor.io.liveRedirectReadSet

  programCounter.io.backendRedirect.valid := redirectCandidate.valid
  programCounter.io.backendRedirect.bits := redirectCandidate.bits

  val issueBase = !io.waiting && (!io.flush || applyBackendRedirect) &&
    (!redirectCandidate.valid || applyBackendRedirect)
  instructionCache.io.request.valid := issueBase
  instructionCache.io.request.bits.virtualAddress := programCounter.io.nextPc

  val useLiveRedirectTarget =
    predictor.io.liveRedirectTarget.valid && !redirectCandidate.valid
  predictor.io.liveRedirectQueryAllowed := !redirectCandidate.valid

  predictor.io.backendRedirectPhtPcWord.valid := redirectCandidate.valid
  predictor.io.backendRedirectPhtPcWord.bits :=
    Cat(redirectCandidate.bits(14, 4), 0.U(2.W))
  predictor.io.backendRedirectPhtUsesRecoveryHistory :=
    redirectCandidate.valid && redirectCandidateUsesRecoveryHistory
  predictor.io.nonLiveQueryPcFragment := programCounter.io.nextNonLivePcFragment
  val nextPredictorQueryPc = Mux(
    useLiveRedirectTarget,
    predictor.io.liveRedirectTarget.bits,
    programCounter.io.nextPc
  )

  instructionCache.io.requestReadSet := programCounter.io.nextReadSet

  instructionCache.io.request.bits.physicalAddress := 0.U
  instructionCache.io.lookupPhysicalAddress := effectivePhysicalAddress
  instructionCache.io.lookupFault := effectiveTranslationFault
  instructionCache.io.lookupReady := registeredFastLookup || registeredTranslationReady
  val acceptFetch = instructionCache.io.request.fire
  val acceptedPredictionEpoch = Mux(io.flush, predictionEpoch + 1.U, predictionEpoch)

  val translationLookupAdvance = Wire(Bool())
  translationLookupAdvance := instructionCache.io.request.valid
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      firrtl.AttributeAnnotation(translationLookupAdvance.toTarget, "dont_touch = \"yes\"")
  })

  when(acceptFetch && useLiveRedirectTarget) {
    assert(
      predictor.io.liveRedirectTarget.bits === programCounter.io.nextPc,
      "live predictor target bypass must match the accepted redirect PC"
    )
  }
  when(acceptFetch && predictor.io.liveRedirectReadSet.valid && !redirectCandidate.valid) {
    assert(
      predictor.io.liveRedirectReadSet.bits === programCounter.io.nextPc(11, 6),
      "cache-local set bypass must match the accepted redirect PC"
    )
  }

  predictor.io.query.valid := acceptFetch
  predictor.io.query.bits.pc := nextPredictorQueryPc
  predictor.io.query.bits.token := nextPredictionToken
  predictor.io.query.bits.epoch := acceptedPredictionEpoch

  predictor.io.consumeResponse :=
    packetCapture || (io.flush && predictor.io.response.valid)

  when(acceptFetch) {
    nextPredictionToken := nextPredictionToken + 1.U
  }
  when(acceptFetch && !lateCorrectionFire) {
    assert(
      !h64TokenAllocated(nextPredictionToken) || frontendFlush,
      "H64 token must not be reused while its fetch packet remains resident"
    )
    h64TokenAllocated(nextPredictionToken) := true.B
  }
  when(frontendFlush) {
    predictionEpoch := predictionEpoch + 1.U
  }

  when(!registeredTranslationReady) {
    registeredPhysicalAddress := translator.io.result.physicalAddress
    registeredTranslationFault := translationFault
    registeredFaultCode := faultCode
    registeredFaultSubcode := faultSubcode
    registeredFaultTlbRefill := faultTlbRefill
    registeredTranslationReady := true.B
  }
  when(acceptFetch) {
    lookupPc := nextLookupPc
    registeredFastLookup := nextFastLookup
    registeredFastPhysicalAddress := nextFastPhysicalAddress
    registeredTranslationReady := false.B
  }
  when(translationLookupAdvance) {
    translationLookupPc := programCounter.io.nextPc
  }

  when(frontendFlush) {
    bufferedValid := false.B
  }.otherwise {
    when(bufferedFire) {
      bufferedValid := false.B
    }
    when(packetFire) {
      bufferedValid := packetMask.orR
    }
  }
  when(packetCapture) {
    bufferedPacket.pc := packetPc
    bufferedPacket.token := predictor.io.response.bits.token
    bufferedPacket.epoch := predictor.io.response.bits.epoch
    bufferedPacket.mask := packetMask
    for (lane <- 0 until 4) {
      bufferedPacket.words(lane) :=
        Mux(effectiveTranslationFault, 0.U, instructionCache.io.response.bits.words(lane))
      bufferedPacket.recovery(lane) := predictor.io.response.bits.recovery(lane)
    }
    bufferedPacket.exception.valid := effectiveTranslationFault
    bufferedPacket.exception.bits.code := effectiveFaultCode
    bufferedPacket.exception.bits.subcode := effectiveFaultSubcode
    bufferedPacket.exception.bits.isTlbRefill := effectiveFaultTlbRefill
    bufferedPacket.branchMask := predictor.io.response.bits.branchMask
    bufferedPacket.takenMask := predictor.io.response.bits.takenMask
    bufferedPacket.redirectTarget := predictor.io.response.bits.redirectTarget
    bufferedPacket.targets := predictor.io.response.bits.targets
  }

  val instructionCacheFlush = Wire(Bool())
  instructionCacheFlush :=
    io.flush || applyBackendRedirect || predictionRedirect || lateCorrectionTruncatePending
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      firrtl.AttributeAnnotation(instructionCacheFlush.toTarget, "dont_touch = \"yes\"")
  })
  instructionCache.io.flush := instructionCacheFlush
  programCounter.io.advance := acceptFetch

  io.profileObservation.h64Valid := VecInit((0 until 3).map(h64Conditional)).asUInt
  io.profileObservation.h64Pc := VecInit(fetchBuffer.io.pop.map(_.bits.pc)).asUInt
  io.profileObservation.h64Token := 0.U
  io.profileObservation.h64Epoch := 0.U
  io.profileObservation.h64Way := 0.U
  io.profileObservation.h64History := 0.U
  io.profileObservation.h64Path := 0.U
  io.profileObservation.h64Score := 0.U
  io.profileObservation.h64PhtCounter := 0.U
  io.profileObservation.h64FastTaken := VecInit(
    fetchBuffer.io.pop.map(_.bits.prediction.taken)
  ).asUInt
  io.profileObservation.h64NeuralTaken := h64NeuralTaken.asUInt
  io.profileObservation.h64Reliable := h64Reliable.asUInt
  io.profileObservation.h64Override := h64CorrectionSelect.asUInt
  io.profileObservation.h64DirectionBias := 0.U

  if (config.h64.detailedTrace) {
    io.profileObservation.h64Token := VecInit(
      fetchBuffer.io.pop.map(_.bits.predictionToken)
    ).asUInt
    io.profileObservation.h64Epoch := VecInit(
      fetchBuffer.io.pop.map(_.bits.predictionEpoch)
    ).asUInt
    io.profileObservation.h64Way := VecInit(
      fetchBuffer.io.pop.map(_.bits.predictionWay)
    ).asUInt

    io.profileObservation.h64History := VecInit(
      selectedH64Results.map(_.history)
    ).asUInt
    io.profileObservation.h64Path := VecInit(selectedH64Results.map(_.path)).asUInt
    io.profileObservation.h64Score := VecInit((0 until 3).map { lane =>
      selectedH64Results(lane).scores.get(fetchBuffer.io.pop(lane).bits.predictionWay).asUInt
    }).asUInt
    io.profileObservation.h64PhtCounter := VecInit((0 until 3).map { lane =>
      selectedH64Results(lane).phtCounters.get(fetchBuffer.io.pop(lane).bits.predictionWay)
    }).asUInt
    io.profileObservation.h64DirectionBias := VecInit((0 until 3).map { lane =>
      selectedH64Results(lane).directionBias.get(fetchBuffer.io.pop(lane).bits.predictionWay)
    }).asUInt
  }
}
