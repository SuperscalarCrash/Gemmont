package gemmont.lsu

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.{DCacheConfig, DesignParams}
import gemmont.backend.PhysicalWrite
import gemmont.backend.issue.{MemoryIssueMicroOp, TypedIssueSlot}
import gemmont.backend.rob.{ExceptionPayloadWithAddress, RobCompletion, RobState}
import gemmont.cache.{DataCache, UncachedAccess}
import gemmont.common.{
  Axi4Master,
  LinePrefetchReq,
  LinePrefetchResp,
  LineReadReq,
  LineReadResp,
  LineWriteAck,
  LineWriteReq
}
import gemmont.debug.{CacheProfileObservation, LsuProfileObservation}
import gemmont.isa.{CacheOperation, CacheSelect, LoadStoreOp, LoongArch, MemoryAccess}
import gemmont.privilege.{AddressTranslator, TlbEntry, TranslationControl}

class MemoryPipelineRecord extends Bundle {
  val issue = Valid(new TypedIssueSlot(new MemoryIssueMicroOp))
  val std = Valid(new StoreBufferSlot)
  val source = Vec(2, UInt(32.W))
  val virtualAddress = UInt(32.W)
  val physicalAddress = UInt(32.W)
  val cached = Bool()
  val byteEnable = UInt(4.W)
  val writeData = UInt(32.W)
  val storeConditionalSuccess = Bool()
  val exception = Valid(new ExceptionPayloadWithAddress)
  val cacheExpected = Bool()
}

class LoadStoreUnit(
    tlbEntries: Int = DesignParams.tlbEntries,
    dcacheConfig: DCacheConfig = DCacheConfig()
) extends Module {
  val io = IO(new Bundle {
    val issue = Input(Valid(new TypedIssueSlot(new MemoryIssueMicroOp)))
    val allowIssue = Output(Bool())
    val issueFire = Output(Bool())
    val readAddress = Output(
      Vec(2, UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val readData = Input(Vec(2, UInt(32.W)))
    val translationControl = Input(new TranslationControl)
    val tlb = Input(Vec(tlbEntries, new TlbEntry))
    val robHeadIndex = Input(UInt(5.W))
    val commitStore = Input(Bool())
    val loadLinked = Input(Bool())
    val stallRead = Input(Bool())
    val flush = Input(Bool())

    val clearBusy = Output(
      Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val write = Output(Valid(new PhysicalWrite))
    val bypassResultBit0ForLane0 = Output(Bool())
    val bypassResultBit1ForLane0 = Output(Bool())
    val bypassResultBit2 = Output(Bool())
    val bypassResultBit2ForMdu = Output(Bool())
    val bypassResultBit3ForLane1 = Output(Bool())
    val bypassResultBit7ForLane0 = Output(Bool())
    val bypassResultBit30ForMdu = Output(Bool())
    val completion = Output(Valid(new RobCompletion))
    val uncachedLoadCompleted = Output(Bool())
    val inhibitInterrupt = Output(Bool())
    val speculativeWakeupFailed = Output(Bool())
    val dataLineReadReq = Decoupled(new LineReadReq)
    val dataLineReadResp = Flipped(Decoupled(new LineReadResp))
    val dataLinePrefetchReq = Decoupled(new LinePrefetchReq)
    val dataLinePrefetchResp = Flipped(Decoupled(new LinePrefetchResp))
    val dataLineWriteReq = Decoupled(new LineWriteReq)
    val dataLineWriteAck = Flipped(Decoupled(new LineWriteAck))
    val uncachedAxi = new Axi4Master
    val storeBufferOccupancy = Output(UInt(4.W))
    val profileObservation = Output(new LsuProfileObservation)
    val profile = Output(new CacheProfileObservation)
  })

  val storeBuffer = Module(new StoreBuffer)
  val dataCache = Module(new DataCache(dcacheConfig))
  val uncached = Module(new UncachedAccess)
  val translator = Module(new AddressTranslator)
  val storeAligner = Module(new StoreAligner)
  val uncachedLoadPostprocessor = Module(new LoadPostprocessor)

  io.dataLineReadReq <> dataCache.io.lineReadReq
  dataCache.io.lineReadResp <> io.dataLineReadResp
  io.dataLinePrefetchReq <> dataCache.io.linePrefetchReq
  dataCache.io.linePrefetchResp <> io.dataLinePrefetchResp
  io.dataLineWriteReq <> dataCache.io.lineWriteReq
  dataCache.io.lineWriteAck <> io.dataLineWriteAck
  io.uncachedAxi <> uncached.io.axi
  uncached.io.dataCacheWritebackIdle := dataCache.io.writebackIdle
  storeBuffer.io.commitStore := io.commitStore
  storeBuffer.io.flush := io.flush
  io.storeBufferOccupancy := storeBuffer.io.occupancy
  io.profile := dataCache.io.profile

  val rrdValid = RegInit(false.B)
  val rrd = Reg(new MemoryPipelineRecord)
  val addressValid = RegInit(false.B)
  val address = Reg(new MemoryPipelineRecord)
  val mem1Valid = RegInit(false.B)
  val mem1 = Reg(new MemoryPipelineRecord)
  val mem2Valid = RegInit(false.B)
  val mem2 = Reg(new MemoryPipelineRecord)
  val mem2Killed = RegInit(false.B)
  val wbValid = RegInit(false.B)
  val wb = Reg(new MemoryPipelineRecord)

  val wbUncachedLoadCompletedForCommit = RegInit(false.B)
  dontTouch(wbUncachedLoadCompletedForCommit)
  val wbWriteValid = RegInit(false.B)
  val wbWritePhysical = Reg(UInt(DesignParams.physicalRegisterAddressWidth.W))

  val wbBypassResultBit2 = Reg(Bool())

  val wbBypassResultBit0ForLane0 = Reg(Bool())
  val wbBypassResultBit2ForMdu = Reg(Bool())
  val wbBypassResultBit3ForLane1 = Reg(Bool())
  val wbBypassResultBit7ForLane0 = Reg(Bool())
  Seq(
    wbBypassResultBit0ForLane0,
    wbBypassResultBit2ForMdu,
    wbBypassResultBit3ForLane1,
    wbBypassResultBit7ForLane0
  ).foreach { copy =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(copy.toTarget, "dont_touch = \"yes\"")
    })
  }

  val wbBypassResultBit30ForMdu = Reg(Bool())
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      firrtl.AttributeAnnotation(
        wbBypassResultBit30ForMdu.toTarget,
        "dont_touch = \"yes\""
      )
  })

  val wbCachedWord = Reg(UInt(32.W))
  val wbCachedHalfLow = Reg(UInt(16.W))
  val wbCachedHalfHigh = Reg(UInt(16.W))
  val wbCachedByte = Reg(UInt(8.W))
  val wbUncachedLoadResult = Reg(UInt(32.W))
  val mem2ForwardData = Reg(UInt(32.W))
  val mem2ForwardByteEnable = Reg(UInt(4.W))
  val wb2Valid = RegInit(false.B)
  val wb2 = Reg(new MemoryPipelineRecord)

  io.readAddress(0) := rrd.issue.bits.operands(0).physical
  io.readAddress(1) := rrd.issue.bits.operands(1).physical

  val addressCombinational = WireDefault(address)

  val issueAddress = address.virtualAddress
  storeAligner.io.input.address := issueAddress
  storeAligner.io.input.data := address.source(1)
  storeAligner.io.input.operation := address.issue.bits.payload.operation
  storeAligner.io.input.isLoad := address.issue.bits.payload.isLoad
  addressCombinational.byteEnable := storeAligner.io.output.byteEnable
  addressCombinational.writeData := storeAligner.io.output.writeData

  addressCombinational.storeConditionalSuccess :=
    address.issue.bits.payload.isStoreConditional && io.loadLinked

  translator.io.request.virtualAddress := addressCombinational.virtualAddress
  translator.io.request.access := Mux(
    address.issue.bits.payload.isStore,
    MemoryAccess.Store,
    MemoryAccess.Load
  )
  translator.io.control := io.translationControl
  translator.io.entries := io.tlb

  val mem1Combinational = WireDefault(addressCombinational)

  val cacheOperationUsesVirtualIndex = address.issue.bits.payload.cacheOperation ===
    CacheOperation.StoreTag || address.issue.bits.payload.cacheOperation ===
    CacheOperation.IndexInvalidate
  mem1Combinational.physicalAddress := Mux(
    cacheOperationUsesVirtualIndex,
    addressCombinational.virtualAddress,
    translator.io.result.physicalAddress
  )
  mem1Combinational.cached := translator.io.result.cached
  mem1Combinational.exception := 0.U.asTypeOf(mem1Combinational.exception)
  val translationException = WireDefault(translator.io.result.exception)
  val addressIsBarrier = address.issue.valid && address.issue.bits.payload.isBarrier
  when(cacheOperationUsesVirtualIndex || addressIsBarrier) {
    translationException := 0.U.asTypeOf(translationException)
  }
  val exceptionCode = WireDefault(0.U(6.W))
  val exceptionSubcode = WireDefault(0.U(9.W))
  val exceptionValid = WireDefault(false.B)
  val exceptionTlbRefill = WireDefault(false.B)
  when(translationException.pageModified) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.PageModified.code.U
    exceptionSubcode := LoongArch.ExceptionCode.PageModified.subcode.U
  }
  when(translationException.pagePrivilege) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.PagePrivilege.code.U
    exceptionSubcode := LoongArch.ExceptionCode.PagePrivilege.subcode.U
  }
  when(translationException.pageInvalidStore) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.PageInvalidStore.code.U
    exceptionSubcode := LoongArch.ExceptionCode.PageInvalidStore.subcode.U
  }
  when(translationException.pageInvalidLoad) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.PageInvalidLoad.code.U
    exceptionSubcode := LoongArch.ExceptionCode.PageInvalidLoad.subcode.U
  }
  when(translationException.tlbRefill) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.TlbRefill.code.U
    exceptionSubcode := LoongArch.ExceptionCode.TlbRefill.subcode.U
    exceptionTlbRefill := true.B
  }
  when(storeAligner.io.output.alignmentException) {
    exceptionValid := true.B
    exceptionCode := LoongArch.ExceptionCode.AddressAlignment.code.U
    exceptionSubcode := LoongArch.ExceptionCode.AddressAlignment.subcode.U
    exceptionTlbRefill := false.B
  }

  val failedStoreConditional =
    address.issue.bits.payload.isStoreConditional &&
      !addressCombinational.storeConditionalSuccess
  mem1Combinational.exception.valid :=
    address.issue.valid && exceptionValid && !failedStoreConditional && !addressIsBarrier
  mem1Combinational.exception.bits.code := exceptionCode
  mem1Combinational.exception.bits.subcode := exceptionSubcode
  mem1Combinational.exception.bits.isTlbRefill := exceptionTlbRefill
  mem1Combinational.exception.bits.badAddress := addressCombinational.virtualAddress

  val noMem1Exception = !mem1.exception.valid
  val mem1CachedLoad = mem1.issue.valid && mem1.issue.bits.payload.isLoad && mem1.cached &&
    noMem1Exception
  val mem1CachedStd = mem1.std.valid && mem1.std.bits.isStore && mem1.std.bits.cached
  val mem1NeedsCache = mem1CachedLoad || mem1CachedStd
  val mem1CacheAddress = Mux(mem1.std.valid, mem1.std.bits.address, mem1.physicalAddress)

  val auxiliaryStates = Enum(8)
  val auxIdle = auxiliaryStates(0)
  val probeRequest = auxiliaryStates(1)
  val probeWait = auxiliaryStates(2)
  val uncachedRequest = auxiliaryStates(3)
  val uncachedLoadWait = auxiliaryStates(4)
  val maintenanceRequest = auxiliaryStates(5)
  val maintenanceWait = auxiliaryStates(6)
  val auxDone = auxiliaryStates(7)
  val auxState = RegInit(auxIdle)
  val uncachedReadData = Reg(UInt(32.W))
  val mem2Uncached = mem2.std.valid && !mem2.std.bits.cached
  val mem2Maintenance = mem2.issue.valid &&
    mem2.issue.bits.payload.cacheOperation =/= CacheOperation.None &&
    mem2.issue.bits.payload.cacheSelect === CacheSelect.Data &&
    !mem2.exception.valid
  dataCache.io.uncachedProbe.valid := mem2Valid && auxState === probeRequest
  dataCache.io.uncachedProbe.bits := mem2.std.bits.address
  dataCache.io.maintenance.valid :=
    mem2Valid && mem2Maintenance && auxState === maintenanceRequest
  dataCache.io.maintenance.bits.address := mem2.physicalAddress
  dataCache.io.maintenance.bits.operation := mem2.issue.bits.payload.cacheOperation
  dataCache.io.maintenance.bits.select := mem2.issue.bits.payload.cacheSelect

  val registeredNoAliasProbeDone =
    auxState === probeWait && dataCache.io.uncachedProbeNoAlias
  uncached.io.request.valid :=
    mem2Valid && (auxState === uncachedRequest || registeredNoAliasProbeDone)
  uncached.io.request.bits.address := mem2.std.bits.address
  uncached.io.request.bits.write := mem2.std.bits.isStore
  uncached.io.request.bits.data := mem2.std.bits.data
  uncached.io.request.bits.byteEnable := mem2.std.bits.byteEnable
  uncached.io.request.bits.operation := mem2.std.bits.operation
  uncached.io.loadResponse.ready := mem2Valid && auxState === uncachedLoadWait

  val uncachedLoadFinishes = mem2Uncached && !mem2.std.bits.isStore &&
    uncached.io.loadResponse.fire
  val auxiliaryDone = Mux(
    mem2Uncached,
    auxState === auxDone || uncachedLoadFinishes,
    Mux(mem2Maintenance, auxState === auxDone, true.B)
  )

  when(dataCache.io.uncachedProbe.fire) { auxState := probeWait }
  when(auxState === probeWait && dataCache.io.uncachedProbeDone) { auxState := uncachedRequest }
  when(uncached.io.request.fire) {
    auxState := Mux(mem2.std.bits.isStore, auxDone, uncachedLoadWait)
  }
  when(uncached.io.loadResponse.fire) {
    uncachedReadData := uncached.io.loadResponse.bits
    auxState := auxDone
  }
  when(dataCache.io.maintenance.fire) { auxState := maintenanceWait }
  when(auxState === maintenanceWait && dataCache.io.maintenanceDone) { auxState := auxDone }

  val mem2StoreConditionalCanWrite =
    !mem2.issue.bits.payload.isStoreConditional || mem2.storeConditionalSuccess
  val mem2NeedsPush = mem2.issue.valid && !mem2Killed && !mem2.exception.valid &&
    ((mem2.issue.bits.payload.isStore && mem2StoreConditionalCanWrite) ||
      (mem2.issue.bits.payload.isLoad && !mem2.cached))
  val cacheReady = !mem2.cacheExpected || dataCache.io.response.valid
  val pushReady = !mem2NeedsPush || storeBuffer.io.push.ready
  val mem2OperationReady = cacheReady && pushReady && auxiliaryDone
  val mem2Ready = !mem2Valid || mem2OperationReady

  dataCache.io.response.ready := mem2Valid && mem2.cacheExpected && pushReady && auxiliaryDone
  storeBuffer.io.push.valid := mem2Valid && mem2NeedsPush && cacheReady && auxiliaryDone
  storeBuffer.io.push.bits := 0.U.asTypeOf(storeBuffer.io.push.bits)
  storeBuffer.io.push.bits.retired := false.B
  storeBuffer.io.push.bits.address := mem2.physicalAddress
  storeBuffer.io.push.bits.byteEnable := mem2.byteEnable
  storeBuffer.io.push.bits.data := mem2.writeData
  storeBuffer.io.push.bits.isStore := mem2.issue.bits.payload.isStore
  storeBuffer.io.push.bits.cached := mem2.cached
  storeBuffer.io.push.bits.writeRegister.valid := mem2.issue.bits.writeRegister
  storeBuffer.io.push.bits.writeRegister.bits := mem2.issue.bits.writePhysical
  storeBuffer.io.push.bits.operation := mem2.issue.bits.payload.operation
  storeBuffer.io.push.bits.robIndex := mem2.issue.bits.robIndex

  val mem1LaunchReady = !mem1NeedsCache || dataCache.io.request.ready

  val mem1Ready = mem2Ready && (!mem1Valid || mem1LaunchReady)
  val addressReady = mem1Ready
  val rrdReady = addressReady
  val mem1Fire = mem1Valid && mem2Ready && mem1LaunchReady
  val mem2Fire = mem2Valid && mem2OperationReady

  dataCache.io.request.valid := mem1Valid && mem2Ready && mem1NeedsCache
  dataCache.io.request.bits.physicalAddress := mem1CacheAddress
  dataCache.io.request.bits.write := mem1CachedStd
  dataCache.io.request.bits.writeData := mem1.std.bits.data
  dataCache.io.request.bits.byteEnable := mem1.std.bits.byteEnable

  val mem1IsUncachedLoadStd = mem1.std.valid && !mem1.std.bits.isStore
  val mem1WriteRegister = Mux(
    mem1IsUncachedLoadStd,
    mem1.std.bits.writeRegister.valid,
    mem1.issue.bits.writeRegister
  )
  val mem1WritePhysical = Mux(
    mem1IsUncachedLoadStd,
    mem1.std.bits.writeRegister.bits,
    mem1.issue.bits.writePhysical
  )
  io.clearBusy.valid := mem1Fire && mem1WriteRegister &&
    ((mem1.issue.valid && (mem1.cached || mem1.issue.bits.payload.isStoreConditional)) ||
      mem1IsUncachedLoadStd) && !io.flush
  io.clearBusy.bits := mem1WritePhysical
  val mem2IsUncachedLoadStd = mem2.std.valid && !mem2.std.bits.isStore
  val mem2WriteRegister = Mux(
    mem2IsUncachedLoadStd,
    mem2.std.bits.writeRegister.valid,
    mem2.issue.bits.writeRegister
  )
  val mem2WritePhysical = Mux(
    mem2IsUncachedLoadStd,
    mem2.std.bits.writeRegister.bits,
    mem2.issue.bits.writePhysical
  )

  io.speculativeWakeupFailed := mem2Valid && !mem2Killed &&
    !mem2OperationReady && mem2WriteRegister

  val mem1ForwardBytes = Wire(Vec(4, UInt(8.W)))
  val mem1ForwardEnable = Wire(Vec(4, Bool()))
  for (byte <- 0 until 4) {
    mem1ForwardBytes(byte) := 0.U
    mem1ForwardEnable(byte) := false.B
  }
  def snapshotStd(recordValid: Bool, record: MemoryPipelineRecord): Unit = {
    when(
      recordValid && record.std.valid && record.std.bits.isStore && record.std.bits.cached &&
        record.std.bits.address(31, 2) === mem1.physicalAddress(31, 2)
    ) {
      for (byte <- 0 until 4) {
        when(record.std.bits.byteEnable(byte)) {
          mem1ForwardBytes(byte) := record.std.bits.data(8 * byte + 7, 8 * byte)
          mem1ForwardEnable(byte) := true.B
        }
      }
    }
  }
  snapshotStd(wbValid, wb)
  snapshotStd(mem2Valid, mem2)
  snapshotStd(addressValid, address)
  snapshotStd(rrdValid, rrd)
  storeBuffer.io.queryAddress := mem1.physicalAddress
  for (byte <- 0 until 4) {
    when(storeBuffer.io.queryByteEnable(byte)) {
      mem1ForwardBytes(byte) := storeBuffer.io.queryData(8 * byte + 7, 8 * byte)
      mem1ForwardEnable(byte) := true.B
    }
  }

  when(
    mem2Valid && mem2NeedsPush && mem2.issue.valid &&
      mem2.issue.bits.payload.isStore && mem2.cached &&
      mem2.physicalAddress(31, 2) === mem1.physicalAddress(31, 2)
  ) {
    for (byte <- 0 until 4) {
      when(mem2.byteEnable(byte)) {
        mem1ForwardBytes(byte) := mem2.writeData(8 * byte + 7, 8 * byte)
        mem1ForwardEnable(byte) := true.B
      }
    }
  }
  val mem1ForwardData = Cat(mem1ForwardBytes.reverse)
  val mem1ForwardByteEnable = mem1ForwardEnable.asUInt

  val baseReadWord = dataCache.io.response.bits.readData
  val forwardedBytes = Wire(Vec(4, UInt(8.W)))
  for (byte <- 0 until 4) {
    forwardedBytes(byte) := Mux(
      mem2ForwardByteEnable(byte),
      mem2ForwardData(8 * byte + 7, 8 * byte),
      baseReadWord(8 * byte + 7, 8 * byte)
    )
  }
  val forwardedWord = Cat(forwardedBytes.reverse)
  val forwardedByte = forwardedBytes(mem2.virtualAddress(1, 0))
  uncachedLoadPostprocessor.io.input.readWord := Mux(
    uncached.io.loadResponse.fire,
    uncached.io.loadResponse.bits,
    uncachedReadData
  )
  uncachedLoadPostprocessor.io.input.byteOffset := mem2.std.bits.address(1, 0)
  uncachedLoadPostprocessor.io.input.operation := mem2.std.bits.operation
  val mem2Sc = mem2.issue.valid && mem2.issue.bits.payload.isStoreConditional
  val forwardedHalfForBypass = Mux(
    mem2.virtualAddress(1),
    forwardedWord(31, 16),
    forwardedWord(15, 0)
  )
  val mem2CachedResultBit2 = MuxLookup(
    mem2.issue.bits.payload.operation.asUInt,
    forwardedWord(2)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> forwardedByte(2),
      LoadStoreOp.ByteUnsigned.asUInt -> forwardedByte(2),
      LoadStoreOp.Half.asUInt -> forwardedHalfForBypass(2),
      LoadStoreOp.HalfUnsigned.asUInt -> forwardedHalfForBypass(2),
      LoadStoreOp.Word.asUInt -> forwardedWord(2)
    )
  )
  val mem2BypassResultBit2 = Mux(
    mem2IsUncachedLoadStd,
    uncachedLoadPostprocessor.io.result(2),
    Mux(mem2Sc, false.B, mem2CachedResultBit2)
  )
  val mem2CachedResultBit0 = MuxLookup(
    mem2.issue.bits.payload.operation.asUInt,
    forwardedWord(0)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> forwardedByte(0),
      LoadStoreOp.ByteUnsigned.asUInt -> forwardedByte(0),
      LoadStoreOp.Half.asUInt -> forwardedHalfForBypass(0),
      LoadStoreOp.HalfUnsigned.asUInt -> forwardedHalfForBypass(0),
      LoadStoreOp.Word.asUInt -> forwardedWord(0)
    )
  )
  val mem2BypassResultBit0 = Mux(
    mem2IsUncachedLoadStd,
    uncachedLoadPostprocessor.io.result(0),
    Mux(mem2Sc, mem2.storeConditionalSuccess, mem2CachedResultBit0)
  )
  val mem2CachedResultBit3 = MuxLookup(
    mem2.issue.bits.payload.operation.asUInt,
    forwardedWord(3)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> forwardedByte(3),
      LoadStoreOp.ByteUnsigned.asUInt -> forwardedByte(3),
      LoadStoreOp.Half.asUInt -> forwardedHalfForBypass(3),
      LoadStoreOp.HalfUnsigned.asUInt -> forwardedHalfForBypass(3),
      LoadStoreOp.Word.asUInt -> forwardedWord(3)
    )
  )
  val mem2BypassResultBit3 = Mux(
    mem2IsUncachedLoadStd,
    uncachedLoadPostprocessor.io.result(3),
    Mux(mem2Sc, false.B, mem2CachedResultBit3)
  )
  val mem2CachedResultBit7 = MuxLookup(
    mem2.issue.bits.payload.operation.asUInt,
    forwardedWord(7)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> forwardedByte(7),
      LoadStoreOp.ByteUnsigned.asUInt -> forwardedByte(7),
      LoadStoreOp.Half.asUInt -> forwardedHalfForBypass(7),
      LoadStoreOp.HalfUnsigned.asUInt -> forwardedHalfForBypass(7),
      LoadStoreOp.Word.asUInt -> forwardedWord(7)
    )
  )
  val mem2BypassResultBit7 = Mux(
    mem2IsUncachedLoadStd,
    uncachedLoadPostprocessor.io.result(7),
    Mux(mem2Sc, false.B, mem2CachedResultBit7)
  )
  val mem2CachedResultBit30 = MuxLookup(
    mem2.issue.bits.payload.operation.asUInt,
    forwardedWord(30)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> forwardedByte(7),
      LoadStoreOp.ByteUnsigned.asUInt -> false.B,
      LoadStoreOp.Half.asUInt -> forwardedHalfForBypass(15),
      LoadStoreOp.HalfUnsigned.asUInt -> false.B,
      LoadStoreOp.Word.asUInt -> forwardedWord(30)
    )
  )
  val mem2BypassResultBit30ForMdu = Mux(
    mem2IsUncachedLoadStd,
    uncachedLoadPostprocessor.io.result(30),
    Mux(mem2Sc, false.B, mem2CachedResultBit30)
  )

  val mem2WriteHasException = mem2.exception.valid && !mem2IsUncachedLoadStd
  val mem2WriteAllowed = mem2Fire && !mem2Killed && mem2WriteRegister &&
    !mem2WriteHasException &&
    ((mem2.issue.valid && (mem2.cached || mem2Sc)) || mem2IsUncachedLoadStd)

  def recordHasStore(valid: Bool, record: MemoryPipelineRecord): Bool = {
    valid && (
      (record.issue.valid && record.issue.bits.payload.isStore) ||
        (record.std.valid && record.std.bits.isStore)
    )
  }
  val storePipelineBusy =
    recordHasStore(rrdValid, rrd) ||
      recordHasStore(addressValid, address) ||
      recordHasStore(mem1Valid, mem1) ||
      recordHasStore(mem2Valid, mem2)
  val issueIsOrderingOperation =
    io.issue.valid && (
      io.issue.bits.payload.isBarrier ||
        io.issue.bits.payload.cacheOperation =/= CacheOperation.None
    )
  val issueIsDataMaintenance =
    io.issue.valid &&
      io.issue.bits.payload.cacheOperation =/= CacheOperation.None &&
      io.issue.bits.payload.cacheSelect === CacheSelect.Data
  val orderingReady =
    storeBuffer.io.occupancy === 0.U && !storePipelineBusy && !uncached.io.busy &&
      dataCache.io.writebackIdle

  val dataMaintenanceNonSpeculative =
    !issueIsDataMaintenance || io.issue.bits.robIndex === io.robHeadIndex
  val issuePermittedByStoreBuffer = storeBuffer.io.occupancy < 4.U
  io.allowIssue := rrdReady && issuePermittedByStoreBuffer &&
    (!issueIsOrderingOperation || orderingReady) && dataMaintenanceNonSpeculative &&
    !io.flush && !io.stallRead
  io.issueFire := io.issue.valid && io.allowIssue

  val issueValidForStoreDrain = Wire(Bool())
  val allowIssueForStoreDrain = Wire(Bool())
  issueValidForStoreDrain := io.issue.valid
  allowIssueForStoreDrain := io.allowIssue
  val issueFireForStoreDrain = issueValidForStoreDrain && allowIssueForStoreDrain
  Seq(issueValidForStoreDrain, allowIssueForStoreDrain, issueFireForStoreDrain).foreach { local =>
    dontTouch(local)
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(local.toTarget, "dont_touch = \"yes\"")
    })
  }

  val dataMaintenanceInFlight = RegInit(false.B)
  when(io.flush) {
    dataMaintenanceInFlight := false.B
  }.elsewhen(io.issueFire && issueIsDataMaintenance) {
    dataMaintenanceInFlight := true.B
  }
  io.inhibitInterrupt := dataMaintenanceInFlight
  val stdEligible = storeBuffer.io.pop.valid && storeBuffer.io.pop.bits.retired && rrdReady &&
    !io.stallRead &&
    ((issueFireForStoreDrain && io.issue.bits.payload.isStore &&
      !io.issue.bits.payload.isStoreConditional) || !issueFireForStoreDrain)
  storeBuffer.io.pop.ready := stdEligible

  when(rrdReady && !io.stallRead) {
    rrd.issue.bits := io.issue.bits
    rrd.std.bits := storeBuffer.io.pop.bits
  }

  when(!io.flush) {
    wb2Valid := wbValid
    when(wbValid) { wb2 := wb }
    wbValid := mem2Fire && !mem2Killed
    wbUncachedLoadCompletedForCommit :=
      mem2Fire && !mem2Killed && mem2.std.valid && !mem2.std.bits.isStore
    wbWriteValid := mem2WriteAllowed
    when(mem2Fire && !mem2Killed) {
      wb := mem2
      wbWritePhysical := mem2WritePhysical
      wbBypassResultBit0ForLane0 := mem2BypassResultBit0
      wbBypassResultBit2 := mem2BypassResultBit2
      wbBypassResultBit2ForMdu := mem2BypassResultBit2
      wbBypassResultBit3ForLane1 := mem2BypassResultBit3
      wbBypassResultBit7ForLane0 := mem2BypassResultBit7
      wbBypassResultBit30ForMdu := mem2BypassResultBit30ForMdu

      wbCachedWord := forwardedWord

      wbCachedHalfLow := forwardedWord(15, 0)
      wbCachedHalfHigh := forwardedWord(31, 16)
      wbCachedByte := forwardedByte
      wbUncachedLoadResult := uncachedLoadPostprocessor.io.result
    }

    when(mem2Ready) {
      mem2Valid := mem1Fire
      when(mem1Fire) {
        mem2 := mem1
        mem2.cacheExpected := mem1NeedsCache
        mem2Killed := false.B
        mem2ForwardData := mem1ForwardData
        mem2ForwardByteEnable := mem1ForwardByteEnable
        when(mem1.std.valid && !mem1.std.bits.cached) {
          auxState := probeRequest
        }.elsewhen(
          mem1.issue.valid && mem1.issue.bits.payload.cacheOperation =/= CacheOperation.None &&
            mem1.issue.bits.payload.cacheSelect === CacheSelect.Data &&
            !mem1.exception.valid
        ) {
          auxState := maintenanceRequest
        }.otherwise {
          auxState := auxIdle
        }
      }.otherwise {
        mem2Killed := false.B
      }
    }
    when(mem1Ready) {
      mem1Valid := addressValid
      when(addressValid) { mem1 := mem1Combinational }
    }
    when(addressReady) {
      addressValid := rrdValid && !io.stallRead
      when(rrdValid && !io.stallRead) {
        address := rrd
        address.source(0) := io.readData(0)
        address.source(1) := io.readData(1)
        address.virtualAddress := io.readData(0) + rrd.issue.bits.payload.immediate
      }
    }

    when(rrdReady && !io.stallRead) {
      rrdValid := io.issueFire || storeBuffer.io.pop.fire
      rrd.issue.valid := io.issueFire
      rrd.std.valid := storeBuffer.io.pop.fire
    }
  }.otherwise {

    val launchMem1 = mem1Fire && (mem1NeedsCache || mem1.std.valid)
    val keepMem2 = mem2Valid && !mem2OperationReady
    when(launchMem1) {
      mem2Valid := true.B
      mem2 := mem1
      mem2.cacheExpected := mem1NeedsCache
      mem2.issue.valid := false.B
      mem2Killed := !mem1.std.valid
      mem2ForwardData := mem1ForwardData
      mem2ForwardByteEnable := mem1ForwardByteEnable
      when(mem1.std.valid && !mem1.std.bits.cached) {
        auxState := probeRequest
      }.otherwise {
        auxState := auxIdle
      }
    }.elsewhen(keepMem2) {
      mem2Valid := true.B

      mem2.issue.valid := false.B
      mem2Killed := mem2Killed || !mem2.std.valid
    }.otherwise {
      mem2Valid := false.B
      mem2Killed := false.B
      auxState := auxIdle
    }

    when(mem1Ready) {
      mem1Valid := addressValid && address.std.valid
      when(addressValid && address.std.valid) {
        mem1 := mem1Combinational
        mem1.issue.valid := false.B
      }
    }.otherwise {
      mem1Valid := mem1Valid && mem1.std.valid
      when(mem1Valid && mem1.std.valid) { mem1.issue.valid := false.B }
    }
    when(addressReady) {
      addressValid := rrdValid && rrd.std.valid
      when(rrdValid && rrd.std.valid) {
        address := rrd
        address.issue.valid := false.B
      }
    }.otherwise {
      addressValid := addressValid && address.std.valid
      when(addressValid && address.std.valid) { address.issue.valid := false.B }
    }
    when(addressReady) {

      rrdValid := storeBuffer.io.pop.fire
      rrd.issue.valid := false.B
      rrd.std.valid := storeBuffer.io.pop.fire
    }.otherwise {
      rrdValid := rrdValid && rrd.std.valid
      when(rrdValid && rrd.std.valid) { rrd.issue.valid := false.B }
    }
    wbValid := false.B
    wbUncachedLoadCompletedForCommit := false.B
    wbWriteValid := false.B
    wb2Valid := false.B
  }

  val wbIsUncachedLoadStd = wb.std.valid && !wb.std.bits.isStore
  assert(
    wbUncachedLoadCompletedForCommit === (wbValid && wbIsUncachedLoadStd)
  )
  val wbIsStoreConditional = wb.issue.valid && wb.issue.bits.payload.isStoreConditional
  val wbCachedHalf = Mux(wb.virtualAddress(1), wbCachedHalfHigh, wbCachedHalfLow)
  val wbLane0CachedHalfBit1 = Mux(
    wb.virtualAddress(1),
    wbCachedHalfHigh(1),
    wbCachedHalfLow(1)
  )
  val wbLane0CachedResultBit1 = MuxLookup(
    wb.issue.bits.payload.operation.asUInt,
    wbCachedWord(1)
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> wbCachedByte(1),
      LoadStoreOp.ByteUnsigned.asUInt -> wbCachedByte(1),
      LoadStoreOp.Half.asUInt -> wbLane0CachedHalfBit1,
      LoadStoreOp.HalfUnsigned.asUInt -> wbLane0CachedHalfBit1,
      LoadStoreOp.Word.asUInt -> wbCachedWord(1)
    )
  )
  val wbLane0BypassResultBit1 = Mux(
    wbIsUncachedLoadStd,
    wbUncachedLoadResult(1),
    Mux(wbIsStoreConditional, false.B, wbLane0CachedResultBit1)
  )
  Seq(
    wbLane0CachedHalfBit1,
    wbLane0CachedResultBit1,
    wbLane0BypassResultBit1
  ).foreach { local =>
    dontTouch(local)
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(local.toTarget, "dont_touch = \"yes\"")
    })
  }
  val wbCachedLoadResult = MuxLookup(
    wb.issue.bits.payload.operation.asUInt,
    wbCachedWord
  )(
    Seq(
      LoadStoreOp.Byte.asUInt -> Cat(Fill(24, wbCachedByte(7)), wbCachedByte),
      LoadStoreOp.ByteUnsigned.asUInt -> Cat(0.U(24.W), wbCachedByte),
      LoadStoreOp.Half.asUInt -> Cat(Fill(16, wbCachedHalf(15)), wbCachedHalf),
      LoadStoreOp.HalfUnsigned.asUInt -> Cat(0.U(16.W), wbCachedHalf),
      LoadStoreOp.Word.asUInt -> wbCachedWord
    )
  )
  val wbResult = Mux(
    wbIsUncachedLoadStd,
    wbUncachedLoadResult,
    Mux(wbIsStoreConditional, wb.storeConditionalSuccess.asUInt, wbCachedLoadResult)
  )

  io.write.valid := wbWriteValid
  io.write.bits.address := wbWritePhysical
  io.write.bits.data := wbResult
  io.write.bits.bypass := true.B
  io.bypassResultBit0ForLane0 := wbBypassResultBit0ForLane0
  io.bypassResultBit1ForLane0 := wbLane0BypassResultBit1
  io.bypassResultBit2 := wbBypassResultBit2
  io.bypassResultBit2ForMdu := wbBypassResultBit2ForMdu
  io.bypassResultBit3ForLane1 := wbBypassResultBit3ForLane1
  io.bypassResultBit7ForLane0 := wbBypassResultBit7ForLane0
  io.bypassResultBit30ForMdu := wbBypassResultBit30ForMdu
  when(wbWriteValid) {
    assert(wbBypassResultBit0ForLane0 === wbResult(0))
    assert(wbLane0BypassResultBit1 === wbResult(1))
    assert(wbBypassResultBit2 === wbResult(2))
    assert(wbBypassResultBit2ForMdu === wbResult(2))
    assert(wbBypassResultBit3ForLane1 === wbResult(3))
    assert(wbBypassResultBit7ForLane0 === wbResult(7))
    assert(wbBypassResultBit30ForMdu === wbResult(30))
  }

  io.uncachedLoadCompleted := wbUncachedLoadCompletedForCommit

  val completionState = WireDefault(0.U.asTypeOf(new RobState))
  completionState.complete := true.B
  completionState.loadStoreOperation := wb.issue.bits.payload.operation
  completionState.exception := wb.exception
  completionState.lsuUncached := !wb.cached
  completionState.integerResult := wbResult
  when(wb.issue.bits.payload.cacheOperation =/= CacheOperation.None) {
    completionState.integerResult := Cat(
      0.U(28.W),
      wb.issue.bits.payload.cacheSelect.asUInt,
      wb.issue.bits.payload.cacheOperation.asUInt
    )
  }
  completionState.isLoad := wb.issue.bits.payload.isLoad
  completionState.isStore := wb.issue.bits.payload.isStore
  completionState.isLoadLinked := wb.issue.bits.payload.isLoadLinked
  completionState.isStoreConditional := wb.issue.bits.payload.isStoreConditional
  completionState.virtualAddress := wb.virtualAddress
  completionState.physicalAddress := wb.physicalAddress
  completionState.storeData := wb.writeData
  when(!wb.exception.valid) {
    completionState.exception.bits.badAddress := wb.physicalAddress
  }
  io.completion.valid := wbValid && wb.issue.valid && !io.flush
  io.completion.bits.index := wb.issue.bits.robIndex
  io.completion.bits.state := completionState

  io.profileObservation.issue := io.issueFire
  io.profileObservation.mem2Valid := mem2Valid && mem2.issue.valid && !mem2Killed
  io.profileObservation.mem2Pc := mem2.issue.bits.payload.pc
  io.profileObservation.mem2CacheWait := !cacheReady
  io.profileObservation.mem2StoreBufferWait := !pushReady
  io.profileObservation.mem2AuxWait := !auxiliaryDone
}
