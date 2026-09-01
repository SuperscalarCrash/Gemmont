package gemmont.cache

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.L2DCacheConfig
import gemmont.common.{
  Axi4,
  Axi4Address,
  Axi4Master,
  CacheLineSource,
  LinePrefetchReq,
  LinePrefetchResp,
  LineReadReq,
  LineReadResp,
  LineWriteAck,
  LineWriteReq
}
import gemmont.debug.{CacheInterconnectProfileObservation, L2ProfileObservation}
import scala.annotation.nowarn

private[cache] class L2MissEntry(
    addressWidth: Int,
    txnIdWidth: Int
) extends Bundle {
  val request = new LineReadReq(addressWidth, txnIdWidth)
  val replacementWay = UInt(1.W)
}

private[cache] class L2VictimEntry(
    addressWidth: Int,
    lineBytes: Int
) extends Bundle {
  val lineAddress = UInt(addressWidth.W)
  val data = UInt((lineBytes * 8).W)
}

@nowarn("cat=deprecation")
class L2DataCache(config: L2DCacheConfig = L2DCacheConfig()) extends Module {
  require(config.ways == 2 && config.lineBytes == 64)

  private val addressWidth = 32
  private val dataWidth = 32
  private val txnIdWidth = 4
  private val setWidth = config.indexWidth
  private val tagWidth = addressWidth - config.tagOffset
  private val wordWidth = log2Ceil(config.lineWords)
  private val lineBits = config.lineBytes * 8

  val io = IO(new Bundle {
    val instructionReadReq = Flipped(Decoupled(new LineReadReq(addressWidth, txnIdWidth)))
    val instructionReadResp = Decoupled(
      new LineReadResp(addressWidth, txnIdWidth, config.lineBytes)
    )
    val dataReadReq = Flipped(Decoupled(new LineReadReq(addressWidth, txnIdWidth)))
    val dataReadResp = Decoupled(new LineReadResp(addressWidth, txnIdWidth, config.lineBytes))
    val dataPrefetchReq = Flipped(Decoupled(new LinePrefetchReq(addressWidth)))
    val dataPrefetchResp = Decoupled(new LinePrefetchResp(addressWidth, config.lineBytes))
    val dataWriteReq = Flipped(
      Decoupled(new LineWriteReq(addressWidth, txnIdWidth, config.lineBytes))
    )
    val dataWriteAck = Decoupled(new LineWriteAck(txnIdWidth))
    val uncached = Flipped(new Axi4Master(addressWidth, dataWidth, txnIdWidth))
    val downstream = new Axi4Master(addressWidth, dataWidth, txnIdWidth)
    val profile = Output(new L2ProfileObservation)
    val interconnectProfile = Output(new CacheInterconnectProfileObservation)
  })

  def setOf(address: UInt): UInt =
    address(config.tagOffset - 1, config.offsetWidth)
  def tagOf(address: UInt): UInt = address(addressWidth - 1, config.tagOffset)
  def lineAddress(tag: UInt, set: UInt): UInt =
    Cat(tag, set, 0.U(config.offsetWidth.W))

  private def blockUpstream(bus: Axi4Master): Unit = {
    bus.aw.ready := false.B
    bus.w.ready := false.B
    bus.b.valid := false.B
    bus.b.bits := 0.U.asTypeOf(bus.b.bits)
    bus.ar.ready := false.B
    bus.r.valid := false.B
    bus.r.bits := 0.U.asTypeOf(bus.r.bits)
  }

  blockUpstream(io.uncached)
  Axi4.setIdle(io.downstream)

  val uncachedStates = Enum(13)
  val uncachedIdle = uncachedStates(0)
  val uncachedReadLookupWait = uncachedStates(1)
  val uncachedReadLookup = uncachedStates(2)
  val uncachedWriteCollect = uncachedStates(3)
  val uncachedWriteLookup = uncachedStates(4)
  val uncachedVictimAddress = uncachedStates(5)
  val uncachedVictimData = uncachedStates(6)
  val uncachedVictimResponse = uncachedStates(7)
  val uncachedReadAddress = uncachedStates(8)
  val uncachedReadResponse = uncachedStates(9)
  val uncachedWriteAddress = uncachedStates(10)
  val uncachedWriteData = uncachedStates(11)
  val uncachedWriteResponse = uncachedStates(12)
  val uncachedState = RegInit(uncachedIdle)
  val uncachedActiveRead = Reg(new Axi4Address(addressWidth, txnIdWidth))
  val uncachedActiveWrite = Reg(new Axi4Address(addressWidth, txnIdWidth))
  val uncachedAddress = Reg(UInt(addressWidth.W))
  val uncachedIsWrite = RegInit(false.B)
  val uncachedWriteLine = Reg(Vec(config.lineWords, UInt(dataWidth.W)))
  val uncachedWriteStrobes = Reg(Vec(config.lineWords, UInt((dataWidth / 8).W)))
  val uncachedCounter = RegInit(0.U(wordWidth.W))
  val uncachedVictimLine = Reg(UInt(lineBits.W))
  val uncachedVictimTag = Reg(UInt(tagWidth.W))
  val uncachedVictimSet = Reg(UInt(setWidth.W))
  val uncachedVictimWay = Reg(UInt(1.W))

  val drainRequested =
    uncachedState =/= uncachedIdle || io.uncached.aw.valid || io.uncached.ar.valid ||
      io.uncached.w.valid

  val instructionReqQ =
    Module(new Queue(new LineReadReq(addressWidth, txnIdWidth), 2, pipe = false))

  val dataReqQ = Module(new Queue(new LineReadReq(addressWidth, txnIdWidth), 1, pipe = false))

  val dataPrefetchReqQ =
    Module(new Queue(new LinePrefetchReq(addressWidth), 1, pipe = false))
  val dataWriteAckQ = Module(new Queue(new LineWriteAck(txnIdWidth), 2, pipe = true))
  val missQ = Module(new Queue(new L2MissEntry(addressWidth, txnIdWidth), 2, pipe = true))

  val victimQ = Module(
    new Queue(new L2VictimEntry(addressWidth, config.lineBytes), 1, pipe = false)
  )

  instructionReqQ.io.enq.valid := io.instructionReadReq.valid && !drainRequested
  instructionReqQ.io.enq.bits := io.instructionReadReq.bits
  io.instructionReadReq.ready := instructionReqQ.io.enq.ready && !drainRequested
  dataReqQ.io.enq.valid := io.dataReadReq.valid && !drainRequested
  dataReqQ.io.enq.bits := io.dataReadReq.bits
  io.dataReadReq.ready := dataReqQ.io.enq.ready && !drainRequested
  dataPrefetchReqQ.io.enq.valid := io.dataPrefetchReq.valid && !drainRequested
  dataPrefetchReqQ.io.enq.bits := io.dataPrefetchReq.bits
  io.dataPrefetchReq.ready := dataPrefetchReqQ.io.enq.ready && !drainRequested
  io.dataWriteReq.ready := false.B

  io.instructionReadResp.valid := false.B
  io.instructionReadResp.bits := 0.U.asTypeOf(io.instructionReadResp.bits)
  io.dataReadResp.valid := false.B
  io.dataReadResp.bits := 0.U.asTypeOf(io.dataReadResp.bits)
  io.dataPrefetchResp.valid := false.B
  io.dataPrefetchResp.bits := 0.U.asTypeOf(io.dataPrefetchResp.bits)
  io.dataWriteAck <> dataWriteAckQ.io.deq

  dataWriteAckQ.io.enq.valid := false.B
  dataWriteAckQ.io.enq.bits := 0.U.asTypeOf(dataWriteAckQ.io.enq.bits)
  missQ.io.enq.valid := false.B
  missQ.io.enq.bits := 0.U.asTypeOf(missQ.io.enq.bits)
  missQ.io.deq.ready := false.B
  victimQ.io.enq.valid := false.B
  victimQ.io.enq.bits := 0.U.asTypeOf(victimQ.io.enq.bits)
  victimQ.io.deq.ready := false.B

  val tags = Seq.fill(config.ways)(Mem(config.sets, UInt(tagWidth.W)))
  val validBits = Seq.fill(config.ways)(Mem(config.sets, Bool()))
  val dirtyBits = Seq.fill(config.ways)(Mem(config.sets, Bool()))
  val lruBits = Mem(config.sets, Bool())
  (tags ++ validBits ++ dirtyBits :+ lruBits).foreach { memory =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"distributed\"")
    })
  }

  val data = Seq.fill(config.ways)(Module(new L2DCacheDataRam(setWidth, lineBits)))
  val dataWriteEnable = Wire(Vec(config.ways, Bool()))
  val dataWriteSet = Wire(Vec(config.ways, UInt(setWidth.W)))
  val dataWriteLine = Wire(Vec(config.ways, UInt(lineBits.W)))
  dataWriteEnable.foreach(_ := false.B)
  dataWriteSet.foreach(_ := 0.U)
  dataWriteLine.foreach(_ := 0.U)
  val dataReadEnable = WireDefault(false.B)
  val dataReadSet = WireDefault(0.U(setWidth.W))
  val dataReadByWay = data.map { memory =>
    memory.io.clock := clock
    memory.io.writeEnable := false.B
    memory.io.writeAddress := 0.U
    memory.io.writeData := 0.U
    memory.io.readEnable := dataReadEnable
    memory.io.readAddress := dataReadSet
    memory.io.readData
  }
  for (way <- 0 until config.ways) {
    data(way).io.writeEnable := dataWriteEnable(way)
    data(way).io.writeAddress := dataWriteSet(way)
    data(way).io.writeData := dataWriteLine(way)
  }

  val tagWriteEnable = Wire(Vec(config.ways, Bool()))
  val tagWriteSet = Wire(Vec(config.ways, UInt(setWidth.W)))
  val tagWriteData = Wire(Vec(config.ways, UInt(tagWidth.W)))
  val validWriteEnable = Wire(Vec(config.ways, Bool()))
  val validWriteSet = Wire(Vec(config.ways, UInt(setWidth.W)))
  val validWriteData = Wire(Vec(config.ways, Bool()))
  val dirtyWriteEnable = Wire(Vec(config.ways, Bool()))
  val dirtyWriteSet = Wire(Vec(config.ways, UInt(setWidth.W)))
  val dirtyWriteData = Wire(Vec(config.ways, Bool()))
  for (way <- 0 until config.ways) {
    tagWriteEnable(way) := false.B
    tagWriteSet(way) := 0.U
    tagWriteData(way) := 0.U
    validWriteEnable(way) := false.B
    validWriteSet(way) := 0.U
    validWriteData(way) := false.B
    dirtyWriteEnable(way) := false.B
    dirtyWriteSet(way) := 0.U
    dirtyWriteData(way) := false.B
    when(tagWriteEnable(way)) { tags(way).write(tagWriteSet(way), tagWriteData(way)) }
    when(validWriteEnable(way)) {
      validBits(way).write(validWriteSet(way), validWriteData(way))
    }
    when(dirtyWriteEnable(way)) {
      dirtyBits(way).write(dirtyWriteSet(way), dirtyWriteData(way))
    }
  }
  val lruWriteEnable = WireDefault(false.B)
  val lruWriteSet = WireDefault(0.U(setWidth.W))
  val lruWriteData = WireDefault(false.B)
  when(lruWriteEnable) { lruBits.write(lruWriteSet, lruWriteData) }

  val metadataRequestValid = WireDefault(false.B)
  val metadataRequestWriteState = WireDefault(false.B)
  val metadataRequestWriteTag = WireDefault(false.B)
  val metadataRequestWriteLru = WireDefault(false.B)
  val metadataRequestSet = WireDefault(0.U(setWidth.W))
  val metadataRequestWay = WireDefault(0.U(1.W))
  val metadataRequestTag = WireDefault(0.U(tagWidth.W))
  val metadataRequestValidData = WireDefault(false.B)
  val metadataRequestDirtyData = WireDefault(false.B)
  val metadataRequestLruData = WireDefault(false.B)

  val metadataCommitValid = RegInit(false.B)
  val metadataCommitWriteState = Reg(Bool())
  val metadataCommitWriteTag = Reg(Bool())
  val metadataCommitWriteLru = Reg(Bool())
  val metadataCommitSet = Reg(UInt(setWidth.W))
  val metadataCommitWay = Reg(UInt(1.W))
  val metadataCommitTag = Reg(UInt(tagWidth.W))
  val metadataCommitValidData = Reg(Bool())
  val metadataCommitDirtyData = Reg(Bool())
  val metadataCommitLruData = Reg(Bool())

  metadataCommitValid := metadataRequestValid
  metadataCommitWriteState := metadataRequestWriteState
  metadataCommitWriteTag := metadataRequestWriteTag
  metadataCommitWriteLru := metadataRequestWriteLru
  metadataCommitSet := metadataRequestSet
  metadataCommitWay := metadataRequestWay
  metadataCommitTag := metadataRequestTag
  metadataCommitValidData := metadataRequestValidData
  metadataCommitDirtyData := metadataRequestDirtyData
  metadataCommitLruData := metadataRequestLruData

  when(metadataCommitValid) {
    for (index <- 0 until config.ways) {
      when(metadataCommitWay === index.U && metadataCommitWriteState) {
        tagWriteEnable(index) := metadataCommitWriteTag
        tagWriteSet(index) := metadataCommitSet
        tagWriteData(index) := metadataCommitTag
        validWriteEnable(index) := true.B
        validWriteSet(index) := metadataCommitSet
        validWriteData(index) := metadataCommitValidData
        dirtyWriteEnable(index) := true.B
        dirtyWriteSet(index) := metadataCommitSet
        dirtyWriteData(index) := metadataCommitDirtyData
      }
    }
    when(metadataCommitWriteLru) {
      lruWriteEnable := true.B
      lruWriteSet := metadataCommitSet
      lruWriteData := metadataCommitLruData
    }
  }

  def writeWayState(set: UInt, way: UInt, tag: UInt, valid: Bool, dirty: Bool): Unit = {
    metadataRequestValid := true.B
    metadataRequestWriteState := true.B
    metadataRequestWriteTag := true.B
    metadataRequestSet := set
    metadataRequestWay := way
    metadataRequestTag := tag
    metadataRequestValidData := valid
    metadataRequestDirtyData := dirty
  }

  def invalidateWay(set: UInt, way: UInt): Unit = {
    metadataRequestValid := true.B
    metadataRequestWriteState := true.B
    metadataRequestWriteTag := false.B
    metadataRequestWriteLru := true.B
    metadataRequestSet := set
    metadataRequestWay := way
    metadataRequestValidData := false.B
    metadataRequestDirtyData := false.B
    metadataRequestLruData := way.asBool
  }

  def touchWay(set: UInt, way: UInt): Unit = {
    metadataRequestValid := true.B
    metadataRequestWriteLru := true.B
    metadataRequestSet := set
    metadataRequestWay := way
    metadataRequestLruData := !way.asBool
  }

  val metadataInitializing = RegInit(true.B)
  val metadataInitSet = RegInit(0.U(setWidth.W))

  val lookupKinds = Enum(4)
  val lookupInstructionRead = lookupKinds(0)
  val lookupDataRead = lookupKinds(1)
  val lookupDataWrite = lookupKinds(2)
  val lookupDataPrefetch = lookupKinds(3)
  val lookupValid = RegInit(false.B)
  val lookupKind = Reg(UInt(2.W))
  val lookupReadReq = Reg(new LineReadReq(addressWidth, txnIdWidth))
  val lookupWriteReq = Reg(new LineWriteReq(addressWidth, txnIdWidth, config.lineBytes))
  val lookupPrefetchReq = Reg(new LinePrefetchReq(addressWidth))
  val lookupSet = Reg(UInt(setWidth.W))
  val lookupTag = Reg(UInt(tagWidth.W))

  val lookupDataReady = RegInit(false.B)

  val hitResponseValid = RegInit(false.B)
  val hitResponseRequest = Reg(new LineReadReq(addressWidth, txnIdWidth))
  val hitResponseWay = Reg(UInt(1.W))
  val refillResponseValid = RegInit(false.B)
  val refillResponseRequest = Reg(new LineReadReq(addressWidth, txnIdWidth))
  val prefetchResponseValid = RegInit(false.B)
  val prefetchResponseRequest = Reg(new LinePrefetchReq(addressWidth))
  val prefetchResponseHit = RegInit(false.B)

  val prefetchResponseData = Reg(UInt(lineBits.W))

  val instructionReservationValid = RegInit(false.B)
  val instructionReservationSet = Reg(UInt(setWidth.W))
  val dReservationValid = RegInit(false.B)
  val dReservationSet = Reg(UInt(setWidth.W))

  when(!instructionReservationValid) {
    instructionReservationSet := lookupSet
  }
  when(!dReservationValid) {
    dReservationSet := lookupSet
  }

  val refillStates = Enum(3)
  val refillIdle = refillStates(0)
  val refillAddress = refillStates(1)
  val refillReceive = refillStates(2)
  val refillState = RegInit(refillIdle)
  val activeMiss = Reg(new L2MissEntry(addressWidth, txnIdWidth))
  val refillCounter = RegInit(0.U(wordWidth.W))
  val refillLine = Reg(Vec(config.lineWords, UInt(dataWidth.W)))

  val dataInstallPending = RegInit(false.B)
  val dataInstallFromRefill = Reg(Bool())
  val dataInstallSetReg = Reg(UInt(setWidth.W))
  val dataInstallWayReg = Reg(UInt(1.W))

  val victimStates = Enum(4)
  val victimIdle = victimStates(0)
  val victimAddress = victimStates(1)
  val victimData = victimStates(2)
  val victimResponse = victimStates(3)
  val victimState = RegInit(victimIdle)
  val activeVictim = Reg(new L2VictimEntry(addressWidth, config.lineBytes))
  val victimCounter = RegInit(0.U(wordWidth.W))

  val refillLastPresent =
    refillState === refillReceive && io.downstream.r.valid && io.downstream.r.bits.last

  val metadataReadSet = Reg(UInt(setWidth.W))
  val metadataLookupTag = Reg(UInt(tagWidth.W))
  val tagReadByWay = tags.map(_.read(metadataReadSet))
  val validReadByWay = validBits.map(_.read(metadataReadSet))
  val dirtyReadByWay = dirtyBits.map(_.read(metadataReadSet))
  val lruRead = lruBits.read(metadataReadSet)
  val metadataHits = Wire(Vec(config.ways, Bool()))
  for (way <- 0 until config.ways) {
    metadataHits(way) :=
      validReadByWay(way) && tagReadByWay(way) === metadataLookupTag
  }
  val metadataHit = metadataHits.asUInt.orR
  val metadataHitWay = Mux(metadataHits(1), 1.U, 0.U)
  val metadataReplacementWay = Mux(
    !validReadByWay(0),
    0.U,
    Mux(!validReadByWay(1), 1.U, lruRead)
  )
  val metadataReplacementTag = Mux(
    metadataReplacementWay === 1.U,
    tagReadByWay(1),
    tagReadByWay(0)
  )
  val metadataReplacementValid = Mux(
    metadataReplacementWay === 1.U,
    validReadByWay(1),
    validReadByWay(0)
  )
  val metadataReplacementDirty = Mux(
    metadataReplacementWay === 1.U,
    dirtyReadByWay(1),
    dirtyReadByWay(0)
  )

  val lookupHit = Reg(Bool())
  val lookupHitWay = Reg(UInt(1.W))
  val replacementWay = Reg(UInt(1.W))
  val selectedReplacementTag = Reg(UInt(tagWidth.W))
  val selectedReplacementValid = Reg(Bool())
  val selectedReplacementDirty = Reg(Bool())
  when(lookupValid && !lookupDataReady) {
    lookupHit := metadataHit
    lookupHitWay := metadataHitWay
    replacementWay := metadataReplacementWay
    selectedReplacementTag := metadataReplacementTag
    selectedReplacementValid := metadataReplacementValid
    selectedReplacementDirty := metadataReplacementDirty
  }
  when(lookupValid && lookupDataReady) {
    assert(lookupHit === metadataHit)
    assert(lookupHitWay === metadataHitWay)
    assert(replacementWay === metadataReplacementWay)
    assert(selectedReplacementTag === metadataReplacementTag)
    assert(selectedReplacementValid === metadataReplacementValid)
    assert(selectedReplacementDirty === metadataReplacementDirty)
  }
  val selectedReplacementLine =
    Mux(replacementWay === 1.U, dataReadByWay(1), dataReadByWay(0))

  val hitResponseLine = Mux(hitResponseWay === 1.U, dataReadByWay(1), dataReadByWay(0))
  val hitResponseInstruction =
    hitResponseValid && hitResponseRequest.source === CacheLineSource.Instruction
  val hitResponseData =
    hitResponseValid && hitResponseRequest.source === CacheLineSource.Data
  val refillResponseInstruction =
    refillResponseValid && refillResponseRequest.source === CacheLineSource.Instruction
  val refillResponseData =
    refillResponseValid && refillResponseRequest.source === CacheLineSource.Data

  io.instructionReadResp.valid := hitResponseInstruction || refillResponseInstruction
  io.instructionReadResp.bits.source := CacheLineSource.Instruction
  io.instructionReadResp.bits.txnId := Mux(
    refillResponseInstruction,
    refillResponseRequest.txnId,
    hitResponseRequest.txnId
  )
  io.instructionReadResp.bits.lineAddress := Mux(
    refillResponseInstruction,
    refillResponseRequest.lineAddress,
    hitResponseRequest.lineAddress
  )
  io.instructionReadResp.bits.data := Mux(
    refillResponseInstruction,
    refillLine.asUInt,
    hitResponseLine
  )
  io.dataReadResp.valid := hitResponseData || refillResponseData
  io.dataReadResp.bits.source := CacheLineSource.Data
  io.dataReadResp.bits.txnId := Mux(
    refillResponseData,
    refillResponseRequest.txnId,
    hitResponseRequest.txnId
  )
  io.dataReadResp.bits.lineAddress := Mux(
    refillResponseData,
    refillResponseRequest.lineAddress,
    hitResponseRequest.lineAddress
  )
  io.dataReadResp.bits.data := Mux(refillResponseData, refillLine.asUInt, hitResponseLine)
  io.dataPrefetchResp.valid := prefetchResponseValid
  io.dataPrefetchResp.bits.lineAddress := prefetchResponseRequest.lineAddress
  io.dataPrefetchResp.bits.hit := prefetchResponseHit
  io.dataPrefetchResp.bits.data := prefetchResponseData

  val hitResponseReady = Mux(
    hitResponseRequest.source === CacheLineSource.Instruction,
    io.instructionReadResp.ready,
    io.dataReadResp.ready
  )
  val refillResponseReady = Mux(
    refillResponseRequest.source === CacheLineSource.Instruction,
    io.instructionReadResp.ready,
    io.dataReadResp.ready
  )
  val hitResponseSlotReady = !hitResponseValid || hitResponseReady
  val refillResponseSlotReady = !refillResponseValid || refillResponseReady
  val prefetchResponseSlotReady = !prefetchResponseValid || io.dataPrefetchResp.ready
  when(hitResponseValid && hitResponseReady) { hitResponseValid := false.B }
  when(refillResponseValid && refillResponseReady) { refillResponseValid := false.B }
  when(prefetchResponseValid && io.dataPrefetchResp.ready) {
    prefetchResponseValid := false.B
  }
  assert(!(hitResponseInstruction && refillResponseInstruction))
  assert(!(hitResponseData && refillResponseData))

  val resultInstructionRead = lookupKind === lookupInstructionRead
  val resultDataRead = lookupKind === lookupDataRead
  val resultDataWrite = lookupKind === lookupDataWrite
  val resultDataPrefetch = lookupKind === lookupDataPrefetch
  val writeInstallWay = Mux(lookupHit, lookupHitWay, replacementWay)
  val writeNeedsVictim =
    resultDataWrite && !lookupHit && selectedReplacementValid && selectedReplacementDirty
  val dataMissNeedsVictim =
    resultDataRead && !lookupHit && selectedReplacementValid && selectedReplacementDirty
  val instructionMissNeedsVictim =
    resultInstructionRead && !lookupHit && selectedReplacementValid && selectedReplacementDirty
  val lookupHitBlockedByRefillResponse =
    refillResponseValid && refillResponseRequest.source === lookupReadReq.source

  val resultQueueReady = WireDefault(false.B)
  when(resultInstructionRead) {
    resultQueueReady := Mux(
      lookupHit,
      hitResponseSlotReady && !lookupHitBlockedByRefillResponse,
      missQ.io.enq.ready && (!instructionMissNeedsVictim || victimQ.io.enq.ready) &&
        !instructionReservationValid
    )
  }.elsewhen(resultDataRead) {
    resultQueueReady := Mux(
      lookupHit,
      hitResponseSlotReady && !lookupHitBlockedByRefillResponse,
      missQ.io.enq.ready && (!dataMissNeedsVictim || victimQ.io.enq.ready) &&
        !dReservationValid
    )
  }.elsewhen(resultDataWrite) {
    resultQueueReady :=
      dataWriteAckQ.io.enq.ready && (!writeNeedsVictim || victimQ.io.enq.ready)
  }.elsewhen(resultDataPrefetch) {
    resultQueueReady := prefetchResponseSlotReady
  }
  val resultReady =
    resultQueueReady && lookupDataReady && !refillLastPresent && !metadataInitializing
  val lookupResultFire = lookupValid && resultReady

  when(lookupValid && lookupDataReady && resultDataPrefetch) {
    prefetchResponseData := Mux(
      lookupHitWay === 1.U,
      dataReadByWay(1),
      dataReadByWay(0)
    )
  }

  def pendingVictimAddress(address: UInt): Bool = {
    val activeHazard =
      victimState =/= victimIdle && activeVictim.lineAddress === address
    val queuedHazard =
      victimQ.io.deq.valid && victimQ.io.deq.bits.lineAddress === address
    activeHazard || queuedHazard
  }
  def pendingL1WriteAddress(address: UInt): Bool =
    io.dataWriteReq.valid && io.dataWriteReq.bits.lineAddress === address
  def lookupHeadBlocked(address: UInt): Bool = {
    val set = setOf(address)
    (instructionReservationValid && set === instructionReservationSet) ||
    (dReservationValid && set === dReservationSet) ||
    pendingVictimAddress(address)
  }
  val dataHeadBlocked = lookupHeadBlocked(dataReqQ.io.deq.bits.lineAddress) ||
    pendingL1WriteAddress(dataReqQ.io.deq.bits.lineAddress)
  val writeHeadBlocked = lookupHeadBlocked(io.dataWriteReq.bits.lineAddress)
  val instructionHeadBlocked = lookupHeadBlocked(instructionReqQ.io.deq.bits.lineAddress) ||
    pendingL1WriteAddress(instructionReqQ.io.deq.bits.lineAddress)
  val prefetchHeadBlocked =
    lookupHeadBlocked(dataPrefetchReqQ.io.deq.bits.lineAddress) ||
      pendingL1WriteAddress(dataPrefetchReqQ.io.deq.bits.lineAddress)
  val dataEligible = dataReqQ.io.deq.valid && !dataHeadBlocked
  val writeEligible = io.dataWriteReq.valid && !drainRequested && !writeHeadBlocked
  val instructionEligible = instructionReqQ.io.deq.valid && !instructionHeadBlocked
  val prefetchEligible = dataPrefetchReqQ.io.deq.valid && !prefetchHeadBlocked
  val chooseData = dataEligible
  val chooseWrite = !chooseData && writeEligible
  val chooseInstruction = !chooseData && !chooseWrite && instructionEligible
  val choosePrefetch =
    !chooseData && !chooseWrite && !chooseInstruction && prefetchEligible
  val lookupIssueValid = chooseData || chooseWrite || chooseInstruction || choosePrefetch

  val lookupCanAdvance =
    !lookupValid && hitResponseSlotReady && prefetchResponseSlotReady && !dataInstallPending
  val lookupIssueFire =
    lookupCanAdvance && lookupIssueValid && !metadataInitializing && uncachedState === uncachedIdle
  dataReqQ.io.deq.ready := lookupIssueFire && chooseData
  io.dataWriteReq.ready := lookupIssueFire && chooseWrite
  instructionReqQ.io.deq.ready := lookupIssueFire && chooseInstruction
  dataPrefetchReqQ.io.deq.ready := lookupIssueFire && choosePrefetch

  val issueAddress = Mux(
    chooseData,
    dataReqQ.io.deq.bits.lineAddress,
    Mux(
      chooseWrite,
      io.dataWriteReq.bits.lineAddress,
      Mux(
        chooseInstruction,
        instructionReqQ.io.deq.bits.lineAddress,
        dataPrefetchReqQ.io.deq.bits.lineAddress
      )
    )
  )
  val uncachedLookupLaunch = WireDefault(false.B)
  val uncachedLookupSet = WireDefault(0.U(setWidth.W))
  dataReadEnable := lookupIssueFire || uncachedLookupLaunch
  dataReadSet := Mux(uncachedLookupLaunch, uncachedLookupSet, setOf(issueAddress))

  when(lookupValid && !lookupDataReady) { lookupDataReady := true.B }
  when(lookupResultFire) {
    lookupValid := false.B
    lookupDataReady := false.B
  }
  when(lookupIssueFire) {
    lookupValid := true.B
    lookupDataReady := false.B
    lookupSet := setOf(issueAddress)
    lookupTag := tagOf(issueAddress)
    metadataReadSet := setOf(issueAddress)
    metadataLookupTag := tagOf(issueAddress)
    when(chooseData) {
      lookupKind := lookupDataRead
      lookupReadReq := dataReqQ.io.deq.bits
    }.elsewhen(chooseWrite) {
      lookupKind := lookupDataWrite
      lookupWriteReq := io.dataWriteReq.bits
    }.elsewhen(chooseInstruction) {
      lookupKind := lookupInstructionRead
      lookupReadReq := instructionReqQ.io.deq.bits
    }.otherwise {
      lookupKind := lookupDataPrefetch
      lookupPrefetchReq := dataPrefetchReqQ.io.deq.bits
    }
  }

  val writeAckEnq =
    lookupValid && lookupDataReady && resultDataWrite && !refillLastPresent
  val instructionMissEnq =
    lookupValid && lookupDataReady && resultInstructionRead && !lookupHit &&
      !refillLastPresent &&
      !instructionReservationValid
  val dataMissEnq =
    lookupValid && lookupDataReady && resultDataRead && !lookupHit &&
      !refillLastPresent && !dReservationValid
  val victimEnq =
    (instructionMissEnq && instructionMissNeedsVictim) ||
      (dataMissEnq && dataMissNeedsVictim) || (writeAckEnq && writeNeedsVictim)

  val instructionMissForkReady = !instructionMissNeedsVictim || victimQ.io.enq.ready
  val dataMissForkReady = !dataMissNeedsVictim || victimQ.io.enq.ready
  val dataWriteForkReady = !writeNeedsVictim || victimQ.io.enq.ready
  dataWriteAckQ.io.enq.valid := writeAckEnq && dataWriteForkReady
  dataWriteAckQ.io.enq.bits.txnId := lookupWriteReq.txnId

  missQ.io.enq.valid := (instructionMissEnq && instructionMissForkReady) ||
    (dataMissEnq && dataMissForkReady)
  missQ.io.enq.bits.request := lookupReadReq
  missQ.io.enq.bits.replacementWay := replacementWay

  val victimPeerReady = Mux(resultDataWrite, dataWriteAckQ.io.enq.ready, missQ.io.enq.ready)
  victimQ.io.enq.valid := victimEnq && victimPeerReady
  victimQ.io.enq.bits.lineAddress := lineAddress(selectedReplacementTag, lookupSet)
  victimQ.io.enq.bits.data := selectedReplacementLine

  when(lookupResultFire) {
    when((resultInstructionRead || resultDataRead) && lookupHit) {
      hitResponseValid := true.B
      hitResponseRequest := lookupReadReq
      hitResponseWay := lookupHitWay
    }
    when(resultInstructionRead && !lookupHit) {
      assert(!instructionReservationValid)
      instructionReservationValid := true.B
    }
    when((resultInstructionRead || resultDataRead) && lookupHit) {
      touchWay(lookupSet, lookupHitWay)
    }
    when(resultDataRead && !lookupHit) {
      assert(!dReservationValid)
      dReservationValid := true.B
    }
    when(resultDataWrite) {
      writeWayState(lookupSet, writeInstallWay, lookupTag, true.B, true.B)
      touchWay(lookupSet, writeInstallWay)
    }
    when(resultDataPrefetch) {
      prefetchResponseValid := true.B
      prefetchResponseRequest := lookupPrefetchReq
      prefetchResponseHit := lookupHit
    }
  }

  switch(refillState) {
    is(refillIdle) {
      missQ.io.deq.ready := refillResponseSlotReady
      when(missQ.io.deq.fire) {
        activeMiss := missQ.io.deq.bits
        refillState := refillAddress
      }
    }
    is(refillAddress) {
      io.downstream.ar.valid := true.B
      io.downstream.ar.bits.id := Mux(
        activeMiss.request.source === CacheLineSource.Data,
        1.U,
        0.U
      )
      io.downstream.ar.bits.addr := activeMiss.request.lineAddress
      io.downstream.ar.bits.len := (config.lineWords - 1).U
      io.downstream.ar.bits.size := 2.U
      io.downstream.ar.bits.burst := Axi4.IncrementingBurst
      when(io.downstream.ar.fire) {
        refillCounter := 0.U
        refillState := refillReceive
      }
    }
    is(refillReceive) {
      val refillBlockedByHitResponse =
        hitResponseValid && hitResponseRequest.source === activeMiss.request.source
      io.downstream.r.ready := !io.downstream.r.bits.last ||
        (refillResponseSlotReady && !refillBlockedByHitResponse)
      when(io.downstream.r.fire) {
        refillLine(refillCounter) := io.downstream.r.bits.data
        when(io.downstream.r.bits.last) {
          refillResponseValid := true.B
          refillResponseRequest := activeMiss.request
          val set = setOf(activeMiss.request.lineAddress)
          val tag = tagOf(activeMiss.request.lineAddress)
          writeWayState(set, activeMiss.replacementWay, tag, true.B, false.B)
          touchWay(set, activeMiss.replacementWay)
          when(activeMiss.request.source === CacheLineSource.Data) {
            assert(dReservationValid && dReservationSet === set)
            dReservationValid := false.B
          }.otherwise {
            assert(instructionReservationValid && instructionReservationSet === set)
            instructionReservationValid := false.B
          }
          refillState := refillIdle
        }.otherwise {
          refillCounter := refillCounter + 1.U
        }
      }
    }
  }

  val refillInstall =
    refillState === refillReceive && io.downstream.r.fire && io.downstream.r.bits.last
  val lookupInstall = lookupResultFire && resultDataWrite
  val dataInstallValid = lookupInstall || refillInstall
  val dataInstallSet = Mux(
    refillInstall,
    setOf(activeMiss.request.lineAddress),
    lookupSet
  )
  val dataInstallWay = Mux(refillInstall, activeMiss.replacementWay, writeInstallWay)
  assert(!(lookupInstall && refillInstall))
  dataInstallPending := dataInstallValid
  when(dataInstallValid) {
    dataInstallFromRefill := refillInstall
    dataInstallSetReg := dataInstallSet
    dataInstallWayReg := dataInstallWay
  }
  val committedInstallLine =
    Mux(dataInstallFromRefill, refillLine.asUInt, lookupWriteReq.data)
  for (way <- 0 until config.ways) {
    dataWriteEnable(way) := dataInstallPending && dataInstallWayReg === way.U
    dataWriteSet(way) := dataInstallSetReg
    dataWriteLine(way) := committedInstallLine
  }

  switch(victimState) {
    is(victimIdle) {
      victimQ.io.deq.ready := true.B
      when(victimQ.io.deq.fire) {
        activeVictim := victimQ.io.deq.bits
        victimState := victimAddress
      }
    }
    is(victimAddress) {
      io.downstream.aw.valid := true.B
      io.downstream.aw.bits.id := 3.U
      io.downstream.aw.bits.addr := activeVictim.lineAddress
      io.downstream.aw.bits.len := (config.lineWords - 1).U
      io.downstream.aw.bits.size := 2.U
      io.downstream.aw.bits.burst := Axi4.IncrementingBurst
      when(io.downstream.aw.fire) {
        victimCounter := 0.U
        victimState := victimData
      }
    }
    is(victimData) {
      val words = activeVictim.data.asTypeOf(Vec(config.lineWords, UInt(dataWidth.W)))
      io.downstream.w.valid := true.B
      io.downstream.w.bits.data := words(victimCounter)
      io.downstream.w.bits.strb := "b1111".U
      io.downstream.w.bits.last := victimCounter === (config.lineWords - 1).U
      when(io.downstream.w.fire) {
        when(io.downstream.w.bits.last) {
          victimState := victimResponse
        }.otherwise {
          victimCounter := victimCounter + 1.U
        }
      }
    }
    is(victimResponse) {
      io.downstream.b.ready := true.B
      when(io.downstream.b.fire) { victimState := victimIdle }
    }
  }

  val cachedCompletelyIdle =
    !metadataInitializing && !lookupValid && !lookupDataReady && !dataInstallPending &&
      !instructionReqQ.io.deq.valid &&
      !dataReqQ.io.deq.valid && !dataPrefetchReqQ.io.deq.valid && !missQ.io.deq.valid &&
      refillState === refillIdle && !victimQ.io.deq.valid && victimState === victimIdle &&
      !hitResponseValid && !refillResponseValid && !prefetchResponseValid &&
      !dataWriteAckQ.io.deq.valid &&
      !instructionReservationValid && !dReservationValid && !metadataCommitValid

  val uncachedHits = Wire(Vec(config.ways, Bool()))
  for (way <- 0 until config.ways) {
    uncachedHits(way) := validReadByWay(way) && tagReadByWay(way) === metadataLookupTag
  }
  val uncachedHit = uncachedHits.asUInt.orR
  val uncachedHitWay = Mux(uncachedHits(1), 1.U, 0.U)
  val uncachedHitDirty = Mux(uncachedHitWay === 1.U, dirtyReadByWay(1), dirtyReadByWay(0))
  val uncachedHitLine = Mux(uncachedHitWay === 1.U, dataReadByWay(1), dataReadByWay(0))
  val uncachedHitTag = Mux(uncachedHitWay === 1.U, tagReadByWay(1), tagReadByWay(0))
  val uncachedDirtyVictimStart = WireDefault(false.B)
  val uncachedLookupResult =
    uncachedState === uncachedReadLookup || uncachedState === uncachedWriteLookup

  when(uncachedLookupResult) {
    uncachedVictimLine := uncachedHitLine
    uncachedVictimTag := uncachedHitTag
    uncachedVictimSet := setOf(uncachedAddress)
    uncachedVictimWay := uncachedHitWay
  }

  switch(uncachedState) {
    is(uncachedIdle) {
      when(cachedCompletelyIdle) {
        when(io.uncached.aw.valid) {
          io.uncached.aw.ready := true.B
          when(io.uncached.aw.fire) {
            uncachedActiveWrite := io.uncached.aw.bits
            uncachedAddress := io.uncached.aw.bits.addr
            uncachedIsWrite := true.B
            uncachedCounter := 0.U
            uncachedLookupLaunch := true.B
            uncachedLookupSet := setOf(io.uncached.aw.bits.addr)
            metadataReadSet := setOf(io.uncached.aw.bits.addr)
            metadataLookupTag := tagOf(io.uncached.aw.bits.addr)
            uncachedState := uncachedWriteCollect
          }
        }.elsewhen(io.uncached.ar.valid) {
          io.uncached.ar.ready := true.B
          when(io.uncached.ar.fire) {
            uncachedActiveRead := io.uncached.ar.bits
            uncachedAddress := io.uncached.ar.bits.addr
            uncachedIsWrite := false.B
            uncachedLookupLaunch := true.B
            uncachedLookupSet := setOf(io.uncached.ar.bits.addr)
            metadataReadSet := setOf(io.uncached.ar.bits.addr)
            metadataLookupTag := tagOf(io.uncached.ar.bits.addr)
            uncachedState := uncachedReadLookupWait
          }
        }
      }
    }
    is(uncachedWriteCollect) {
      io.uncached.w.ready := true.B
      when(io.uncached.w.fire) {
        uncachedWriteLine(uncachedCounter) := io.uncached.w.bits.data
        uncachedWriteStrobes(uncachedCounter) := io.uncached.w.bits.strb
        when(io.uncached.w.bits.last) {
          uncachedState := uncachedWriteLookup
        }.otherwise {
          uncachedCounter := uncachedCounter + 1.U
        }
      }
    }
    is(uncachedReadLookupWait) {

      uncachedState := uncachedReadLookup
    }
    is(uncachedReadLookup) {
      when(uncachedHit && uncachedHitDirty) {
        uncachedDirtyVictimStart := true.B
        uncachedState := uncachedVictimAddress
      }.otherwise {
        when(uncachedHit) { invalidateWay(setOf(uncachedAddress), uncachedHitWay) }
        uncachedState := uncachedReadAddress
      }
    }
    is(uncachedWriteLookup) {
      when(uncachedHit && uncachedHitDirty) {
        uncachedCounter := 0.U
        uncachedDirtyVictimStart := true.B
        uncachedState := uncachedVictimAddress
      }.otherwise {
        when(uncachedHit) { invalidateWay(setOf(uncachedAddress), uncachedHitWay) }
        uncachedState := uncachedWriteAddress
      }
    }
    is(uncachedVictimAddress) {
      io.downstream.aw.valid := true.B
      io.downstream.aw.bits.id := 3.U
      io.downstream.aw.bits.addr := lineAddress(uncachedVictimTag, uncachedVictimSet)
      io.downstream.aw.bits.len := (config.lineWords - 1).U
      io.downstream.aw.bits.size := 2.U
      io.downstream.aw.bits.burst := Axi4.IncrementingBurst
      when(io.downstream.aw.fire) {
        uncachedCounter := 0.U
        uncachedState := uncachedVictimData
      }
    }
    is(uncachedVictimData) {
      val words = uncachedVictimLine.asTypeOf(Vec(config.lineWords, UInt(dataWidth.W)))
      io.downstream.w.valid := true.B
      io.downstream.w.bits.data := words(uncachedCounter)
      io.downstream.w.bits.strb := "b1111".U
      io.downstream.w.bits.last := uncachedCounter === (config.lineWords - 1).U
      when(io.downstream.w.fire) {
        when(io.downstream.w.bits.last) {
          uncachedState := uncachedVictimResponse
        }.otherwise {
          uncachedCounter := uncachedCounter + 1.U
        }
      }
    }
    is(uncachedVictimResponse) {
      io.downstream.b.ready := true.B
      when(io.downstream.b.fire) {
        invalidateWay(uncachedVictimSet, uncachedVictimWay)
        uncachedState := Mux(uncachedIsWrite, uncachedWriteAddress, uncachedReadAddress)
      }
    }
    is(uncachedReadAddress) {
      io.downstream.ar.valid := true.B
      io.downstream.ar.bits := uncachedActiveRead
      when(io.downstream.ar.fire) { uncachedState := uncachedReadResponse }
    }
    is(uncachedReadResponse) {
      io.uncached.r.valid := io.downstream.r.valid
      io.uncached.r.bits := io.downstream.r.bits
      io.downstream.r.ready := io.uncached.r.ready
      when(io.downstream.r.fire && io.downstream.r.bits.last) { uncachedState := uncachedIdle }
    }
    is(uncachedWriteAddress) {
      io.downstream.aw.valid := true.B
      io.downstream.aw.bits := uncachedActiveWrite
      when(io.downstream.aw.fire) {
        uncachedCounter := 0.U
        uncachedState := uncachedWriteData
      }
    }
    is(uncachedWriteData) {
      io.downstream.w.valid := true.B
      io.downstream.w.bits.data := uncachedWriteLine(uncachedCounter)
      io.downstream.w.bits.strb := uncachedWriteStrobes(uncachedCounter)
      io.downstream.w.bits.last := uncachedCounter === uncachedActiveWrite.len
      when(io.downstream.w.fire) {
        when(io.downstream.w.bits.last) {
          uncachedState := uncachedWriteResponse
        }.otherwise {
          uncachedCounter := uncachedCounter + 1.U
        }
      }
    }
    is(uncachedWriteResponse) {
      io.uncached.b.valid := io.downstream.b.valid
      io.uncached.b.bits := io.downstream.b.bits
      io.downstream.b.ready := io.uncached.b.ready
      when(io.downstream.b.fire) { uncachedState := uncachedIdle }
    }
  }

  when(metadataInitializing) {
    for (way <- 0 until config.ways) {
      validWriteEnable(way) := true.B
      validWriteSet(way) := metadataInitSet
      validWriteData(way) := false.B
      dirtyWriteEnable(way) := true.B
      dirtyWriteSet(way) := metadataInitSet
      dirtyWriteData(way) := false.B
    }
    lruWriteEnable := true.B
    lruWriteSet := metadataInitSet
    lruWriteData := false.B
    when(metadataInitSet === (config.sets - 1).U) {
      metadataInitializing := false.B
    }.otherwise {
      metadataInitSet := metadataInitSet + 1.U
    }
  }

  assert(!lookupDataReady || lookupValid)

  val instructionMissOutstanding = RegInit(false.B)
  val dataMissOutstanding = RegInit(false.B)
  when(lookupResultFire && resultInstructionRead && !lookupHit) {
    assert(!instructionMissOutstanding)
    instructionMissOutstanding := true.B
  }
  when(lookupResultFire && resultDataRead && !lookupHit) {
    assert(!dataMissOutstanding)
    dataMissOutstanding := true.B
  }
  when(io.downstream.r.fire && io.downstream.r.bits.last && refillState === refillReceive) {
    when(activeMiss.request.source === CacheLineSource.Instruction) {
      instructionMissOutstanding := false.B
    }.otherwise {
      dataMissOutstanding := false.B
    }
  }

  io.profile.instructionRead := lookupResultFire && resultInstructionRead
  io.profile.instructionHit := io.profile.instructionRead && lookupHit
  io.profile.instructionMiss := io.profile.instructionRead && !lookupHit
  io.profile.instructionMissBusy := instructionMissOutstanding || io.profile.instructionMiss
  io.profile.dataRead := lookupResultFire && resultDataRead
  io.profile.dataHit := io.profile.dataRead && lookupHit
  io.profile.dataDirectHit := io.profile.dataHit
  io.profile.dataMiss := io.profile.dataRead && !lookupHit
  io.profile.dataMissBusy := dataMissOutstanding || io.profile.dataMiss
  io.profile.dataWrite := lookupResultFire && resultDataWrite
  io.profile.dirtyWriteback := victimQ.io.enq.fire || uncachedDirtyVictimStart
  io.profile.readAddress := lookupReadReq.lineAddress
  io.profile.dataWriteAddress := lookupWriteReq.lineAddress
  io.profile.readMissBusy := instructionMissOutstanding || dataMissOutstanding
  io.profile.writeBusy :=
    io.dataWriteReq.valid || (lookupValid && resultDataWrite) || dataWriteAckQ.io.deq.valid
  io.profile.dataPrefetchRead := lookupResultFire && resultDataPrefetch
  io.profile.dataPrefetchHit := io.profile.dataPrefetchRead && lookupHit
  io.profile.dataPrefetchMiss := io.profile.dataPrefetchRead && !lookupHit
  io.profile.dataPrefetchWait :=
    dataPrefetchReqQ.io.deq.valid && !dataPrefetchReqQ.io.deq.ready

  io.interconnectProfile.instructionReadWait :=
    io.instructionReadReq.valid && !io.instructionReadReq.ready
  io.interconnectProfile.instructionReadWaitAddress := io.instructionReadReq.bits.lineAddress
  io.interconnectProfile.dataReadWait := io.dataReadReq.valid && !io.dataReadReq.ready
  io.interconnectProfile.dataReadWaitAddress := io.dataReadReq.bits.lineAddress

  when(io.instructionReadReq.fire) {
    assert(io.instructionReadReq.bits.source === CacheLineSource.Instruction)
    assert(io.instructionReadReq.bits.lineAddress(config.offsetWidth - 1, 0) === 0.U)
  }
  when(io.dataReadReq.fire) {
    assert(io.dataReadReq.bits.source === CacheLineSource.Data)
    assert(io.dataReadReq.bits.lineAddress(config.offsetWidth - 1, 0) === 0.U)
  }
  when(io.dataPrefetchReq.fire) {
    assert(io.dataPrefetchReq.bits.lineAddress(config.offsetWidth - 1, 0) === 0.U)
  }
  when(io.dataWriteReq.fire) {
    assert(io.dataWriteReq.bits.lineAddress(config.offsetWidth - 1, 0) === 0.U)
  }
  when(io.downstream.r.fire && refillState === refillReceive) {
    assert(
      io.downstream.r.bits.id ===
        Mux(activeMiss.request.source === CacheLineSource.Data, 1.U, 0.U)
    )
  }
  when(io.downstream.b.fire && victimState === victimResponse) {
    assert(io.downstream.b.bits.id === 3.U)
  }
  assert(PopCount(dataWriteEnable) <= 1.U)
  for (way <- 0 until config.ways) {
    when(dataReadEnable && dataWriteEnable(way)) {
      assert(dataReadSet =/= dataWriteSet(way))
    }
  }
}
