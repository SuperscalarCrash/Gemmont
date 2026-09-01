package gemmont.cache

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.DCacheConfig
import gemmont.common.{
  CacheLineSource,
  LinePrefetchReq,
  LinePrefetchResp,
  LineReadReq,
  LineReadResp,
  LineWriteAck,
  LineWriteReq
}
import gemmont.debug.CacheProfileObservation
import gemmont.isa.{CacheOperation, CacheSelect}
import scala.annotation.nowarn

@nowarn("cat=deprecation")
class DataCache(config: DCacheConfig = DCacheConfig()) extends Module {
  require(config.sets == 64 && config.ways == 2 && config.lineBytes == 64)

  private val setWidth = config.indexWidth
  private val tagWidth = 32 - config.tagOffset
  private val lineBits = config.lineBytes * 8
  private val lineAddressWidth = 32 - config.offsetWidth
  private val prefetchEntries = 2
  private val prefetchEnabled = config.useStreamPrefetch.B
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new DataCacheRequest))
    val response = Decoupled(new DataCacheResponse)

    val uncachedProbe = Flipped(Decoupled(UInt(32.W)))
    val uncachedProbeDone = Output(Bool())
    val uncachedProbeNoAlias = Output(Bool())
    val maintenance = Flipped(Decoupled(new CacheMaintenanceRequest))
    val maintenanceDone = Output(Bool())

    val lineReadReq = Decoupled(new LineReadReq)
    val lineReadResp = Flipped(Decoupled(new LineReadResp(lineBytes = config.lineBytes)))
    val linePrefetchReq = Decoupled(new LinePrefetchReq)
    val linePrefetchResp = Flipped(
      Decoupled(new LinePrefetchResp(lineBytes = config.lineBytes))
    )
    val lineWriteReq = Decoupled(new LineWriteReq(lineBytes = config.lineBytes))
    val lineWriteAck = Flipped(Decoupled(new LineWriteAck))
    val writebackIdle = Output(Bool())
    val profile = Output(new CacheProfileObservation)
  })

  val valid = RegInit(VecInit(Seq.fill(config.sets)(0.U(config.ways.W))))
  val dirty = RegInit(VecInit(Seq.fill(config.sets)(0.U(config.ways.W))))

  val tags = Seq.fill(config.ways)(Mem(config.sets, UInt(tagWidth.W)))
  tags.foreach { memory =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"distributed\"")
    })
  }
  val lru = RegInit(VecInit(Seq.fill(config.sets)(0.U(1.W))))

  val data = Seq.fill(config.ways)(Module(new DCacheDataRam))
  val dataWriteEnable = Wire(Vec(config.ways, UInt(config.lineBytes.W)))
  val dataWriteAddress = Wire(Vec(config.ways, UInt(setWidth.W)))
  val dataWriteLine = Wire(Vec(config.ways, UInt(lineBits.W)))
  dataWriteEnable.foreach(_ := 0.U)
  dataWriteAddress.foreach(_ := 0.U)
  dataWriteLine.foreach(_ := 0.U)

  val requestReadAddress =
    io.request.bits.physicalAddress(config.tagOffset - 1, config.offsetWidth)
  val requestReadWord =
    io.request.bits.physicalAddress(config.offsetWidth - 1, 2)
  val dataWordReadEnable = io.request.fire
  val dataLineReadAddress = WireDefault(0.U(setWidth.W))
  val dataLineReadEnable = WireDefault(false.B)
  val dataReadWords = data.map { memory =>
    memory.io.lineReadAddress := dataLineReadAddress
    memory.io.lineReadEnable := dataLineReadEnable
    memory.io.wordReadAddress := Cat(requestReadAddress, requestReadWord)
    memory.io.wordReadEnable := dataWordReadEnable
    memory.io.wordReadData
  }
  val dataReadRawLines = data.map(_.io.lineReadData)
  for (way <- 0 until config.ways) {
    data(way).io.clock := clock
    data(way).io.writeEnable := dataWriteEnable(way)
    data(way).io.writeAddress := dataWriteAddress(way)
    data(way).io.writeData := dataWriteLine(way)
  }

  io.lineReadReq.valid := false.B
  io.lineReadReq.bits := 0.U.asTypeOf(io.lineReadReq.bits)
  io.lineReadResp.ready := false.B
  io.linePrefetchReq.valid := false.B
  io.linePrefetchReq.bits := 0.U.asTypeOf(io.linePrefetchReq.bits)
  io.linePrefetchResp.ready := true.B
  io.lineWriteReq.valid := false.B
  io.lineWriteReq.bits := 0.U.asTypeOf(io.lineWriteReq.bits)
  io.lineWriteAck.ready := false.B
  io.request.ready := false.B
  io.response.valid := false.B
  io.response.bits := 0.U.asTypeOf(io.response.bits)
  io.uncachedProbe.ready := false.B
  io.uncachedProbeDone := false.B
  io.uncachedProbeNoAlias := false.B
  io.maintenance.ready := false.B
  io.maintenanceDone := false.B

  val states = Enum(13)
  val idle = states(0)
  val lookup = states(1)
  val fetchVictim = states(2)
  val waitWriteResponse = states(3)
  val waitReadRequest = states(4)
  val refill = states(5)
  val respondRefill = states(6)
  val probeLookup = states(7)
  val probeFinish = states(8)
  val maintenanceCheck = states(9)
  val maintenanceFinish = states(10)
  val waitPreviousWriteback = states(11)
  val installPrefetch = states(12)
  val state = RegInit(idle)

  val afterNormalMiss :: afterProbe :: afterMaintenance :: Nil = Enum(3)
  val writebackPurpose = RegInit(afterNormalMiss)

  val activeRequest = Reg(new DataCacheRequest)
  val activeProbeAddress = Reg(UInt(32.W))

  val activeProbeHit = Reg(Bool())
  val activeProbeWay = Reg(UInt(1.W))
  val activeProbeDirty = Reg(Bool())
  val activeMaintenance = Reg(new CacheMaintenanceRequest)
  val activeWay = Reg(UInt(1.W))
  val maintenanceWay = RegInit(0.U(1.W))

  val victimLine = Reg(UInt(lineBits.W))
  val victimReadPending = RegInit(false.B)
  val writebackActive = RegInit(false.B)
  val writebackRequestSent = RegInit(false.B)
  val writebackTag = Reg(UInt(tagWidth.W))
  val writebackSet = Reg(UInt(setWidth.W))
  val responseWord = Reg(UInt(32.W))
  val missResponsePending = RegInit(false.B)
  val dirtyWritebackEvent = WireDefault(false.B)
  val dirtyVictimEvent = WireDefault(false.B)
  val directRefillEvent = WireDefault(false.B)
  val earlyLoadResponseEvent = WireDefault(false.B)
  val prefetchCandidateEvent = WireDefault(false.B)
  val prefetchRequestEvent = WireDefault(false.B)
  val prefetchL2HitEvent = WireDefault(false.B)
  val prefetchL2MissEvent = WireDefault(false.B)
  val prefetchBufferHitEvent = WireDefault(false.B)
  val prefetchLateEvent = WireDefault(false.B)
  val prefetchDroppedEvent = WireDefault(false.B)
  val prefetchDuplicateEvent = WireDefault(false.B)
  val prefetchPageSuppressedEvent = WireDefault(false.B)
  val prefetchCancelledEvent = WireDefault(false.B)
  val prefetchUselessEvent = WireDefault(false.B)

  val prefetchValid = RegInit(VecInit(Seq.fill(prefetchEntries)(false.B)))
  val prefetchUsed = RegInit(VecInit(Seq.fill(prefetchEntries)(false.B)))
  val prefetchLineAddress = Reg(Vec(prefetchEntries, UInt(lineAddressWidth.W)))

  val prefetchData = Reg(Vec(prefetchEntries, UInt(lineBits.W)))

  val prefetchCommitValid = RegInit(false.B)
  val prefetchCommitLine = Reg(UInt(lineAddressWidth.W))
  val prefetchCommitSlot = Reg(UInt(1.W))
  val prefetchReplace = RegInit(0.U(1.W))
  val pendingPrefetchValid = RegInit(false.B)
  val pendingPrefetchLine = Reg(UInt(lineAddressWidth.W))
  val inflightPrefetchValid = RegInit(false.B)
  val inflightPrefetchLine = Reg(UInt(lineAddressWidth.W))
  val inflightPrefetchSlot = Reg(UInt(1.W))
  val inflightPrefetchCancelled = RegInit(false.B)

  val victimPrefetchCancelValid = RegInit(false.B)
  val victimPrefetchCancelLine = Reg(UInt(lineAddressWidth.W))

  val activePrefetchInstall = RegInit(false.B)
  val activePrefetchLine = Reg(UInt(lineBits.W))

  val streamValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val streamLastLine = Reg(Vec(2, UInt(lineAddressWidth.W)))
  val streamIsStore = Reg(Vec(2, Bool()))
  val streamReplace = RegInit(0.U(1.W))
  val observeStream = WireDefault(false.B)
  val observedLine = WireDefault(0.U(lineAddressWidth.W))
  val lastLoadMissValid = RegInit(false.B)
  val lastLoadMissLine = Reg(UInt(lineAddressWidth.W))

  def setOf(address: UInt): UInt = address(config.tagOffset - 1, config.offsetWidth)
  def tagOf(address: UInt): UInt = address(31, config.tagOffset)
  def wordOf(address: UInt): UInt = address(config.offsetWidth - 1, 2)
  def lineOf(address: UInt): UInt = address(31, config.offsetWidth)
  def mergeBytes(oldData: UInt, newData: UInt, byteEnable: UInt): UInt = {
    Cat((0 until 4).reverse.map { lane =>
      Mux(byteEnable(lane), newData(8 * lane + 7, 8 * lane), oldData(8 * lane + 7, 8 * lane))
    })
  }
  def wordByteEnable(word: UInt, byteEnable: UInt): UInt =
    FillInterleaved(4, UIntToOH(word, config.lineWords)) &
      Fill(config.lineWords, byteEnable)
  def mergeLineWord(line: UInt, word: UInt, value: UInt, byteEnable: UInt): UInt = {
    val sourceWords = line.asTypeOf(Vec(config.lineWords, UInt(32.W)))
    val words = Wire(Vec(config.lineWords, UInt(32.W)))
    words := sourceWords
    words(word) := mergeBytes(sourceWords(word), value, byteEnable)
    words.asUInt
  }
  def mux4(select: UInt, values: Seq[UInt]): UInt = {
    require(select.getWidth == 2 && values.length == 4)
    Mux(
      select(1),
      Mux(select(0), values(3), values(2)),
      Mux(select(0), values(1), values(0))
    )
  }
  def selectLineWord(line: UInt, word: UInt): UInt = {
    val words = line.asTypeOf(Vec(config.lineWords, UInt(32.W)))
    val selectedWithinQuarter = words.toSeq.grouped(4).map(mux4(word(1, 0), _)).toSeq
    mux4(word(3, 2), selectedWithinQuarter)
  }
  val requestSet = setOf(activeRequest.physicalAddress)
  val requestTag = tagOf(activeRequest.physicalAddress)
  val requestWord = wordOf(activeRequest.physicalAddress)
  val probeSet = setOf(activeProbeAddress)

  val incomingProbeSet = setOf(io.uncachedProbe.bits)
  val incomingProbeTag = tagOf(io.uncachedProbe.bits)
  val incomingRequestSet = setOf(io.request.bits.physicalAddress)
  val incomingRequestTag = tagOf(io.request.bits.physicalAddress)
  val incomingProbeLine = lineOf(io.uncachedProbe.bits)
  val incomingRequestLine = lineOf(io.request.bits.physicalAddress)
  val requestLine = lineOf(activeRequest.physicalAddress)
  val requestHits = Reg(Vec(config.ways, Bool()))
  val requestHit = requestHits.asUInt.orR
  val requestPrefetchHits = Reg(Vec(prefetchEntries, Bool()))
  val requestPrefetchHit = requestPrefetchHits.asUInt.orR
  val requestPrefetchSlot = Mux(requestPrefetchHits(1), 1.U, 0.U)

  val preloadIncomingRequestTags =
    state === idle || (state === lookup && requestHit) || state === respondRefill

  val tagReadSet = WireDefault(requestSet)
  when(state === idle && io.uncachedProbe.valid) {
    tagReadSet := incomingProbeSet
  }.elsewhen(preloadIncomingRequestTags) {
    tagReadSet := incomingRequestSet
  }.elsewhen(state === maintenanceCheck) {
    tagReadSet := setOf(activeMaintenance.address)
  }.elsewhen(state === fetchVictim || state === waitWriteResponse) {
    tagReadSet := writebackSet
  }
  val tagReadByWay = tags.map(_.read(tagReadSet))
  val validRead = valid(tagReadSet)

  val incomingRequestHits = Wire(Vec(config.ways, Bool()))
  for (way <- 0 until config.ways) {
    incomingRequestHits(way) :=
      validRead(way) && tagReadByWay(way) === incomingRequestTag
  }
  val incomingRequestPrefetchHits = Wire(Vec(prefetchEntries, Bool()))
  for (entry <- 0 until prefetchEntries) {
    incomingRequestPrefetchHits(entry) :=
      prefetchEnabled &&
        ((prefetchValid(entry) &&
          prefetchLineAddress(entry) === incomingRequestLine) ||
          (prefetchCommitValid && !inflightPrefetchCancelled &&
            prefetchCommitSlot === entry.U &&
            prefetchCommitLine === incomingRequestLine))
  }

  when(io.request.fire) {
    requestHits := incomingRequestHits
    requestPrefetchHits := incomingRequestPrefetchHits
    activePrefetchInstall := false.B
  }
  val requestHitWay = Mux(requestHits(1), 1.U, 0.U)
  val requestPrefetchLine = Mux(
    requestPrefetchSlot === 1.U,
    prefetchData(1),
    prefetchData(0)
  )

  val capturedPrefetchLine = Mux(
    activeRequest.write,
    mergeLineWord(
      requestPrefetchLine,
      requestWord,
      activeRequest.writeData,
      activeRequest.byteEnable
    ),
    requestPrefetchLine
  )
  when(state === lookup) { activePrefetchLine := capturedPrefetchLine }

  val delayedStoreWriteValid = Reg(Bool())
  val delayedStoreWriteWay = Reg(UInt(1.W))
  val delayedStoreWriteSet = Reg(UInt(setWidth.W))
  val delayedStoreWriteWord = Reg(UInt((config.offsetWidth - 2).W))
  val delayedStoreWriteData = Reg(UInt(32.W))
  val delayedStoreWriteByteEnable = Reg(UInt(4.W))
  when(dataWordReadEnable) {
    delayedStoreWriteValid := state === lookup && requestHit && activeRequest.write
    delayedStoreWriteWay := requestHitWay
    delayedStoreWriteSet := requestSet
    delayedStoreWriteWord := requestWord
    delayedStoreWriteData := activeRequest.writeData
    delayedStoreWriteByteEnable := activeRequest.byteEnable
  }
  val requestHitRamData = Mux(
    requestHitWay === 1.U,
    dataReadWords(1),
    dataReadWords(0)
  )
  val requestHitData = Mux(
    delayedStoreWriteValid && delayedStoreWriteWay === requestHitWay &&
      delayedStoreWriteSet === requestSet && delayedStoreWriteWord === requestWord,
    mergeBytes(requestHitRamData, delayedStoreWriteData, delayedStoreWriteByteEnable),
    requestHitRamData
  )

  val incomingProbeHits = Wire(Vec(config.ways, Bool()))
  for (way <- 0 until config.ways) {
    incomingProbeHits(way) := validRead(way) && tagReadByWay(way) === incomingProbeTag
  }
  val incomingProbeHit = incomingProbeHits.asUInt.orR

  def startWriteback(
      way: UInt,
      purpose: UInt,
      knownLineAddress: Option[UInt] = None,
      captureAddress: Boolean = true
  ): Unit = {
    assert(!writebackActive)
    dirtyWritebackEvent := true.B
    when(purpose === afterNormalMiss) { dirtyVictimEvent := true.B }
    val set = MuxLookup(purpose, requestSet)(
      Seq(
        afterProbe -> probeSet,
        afterMaintenance -> setOf(activeMaintenance.address)
      )
    )
    val victimTag = Mux(way === 1.U, tagReadByWay(1), tagReadByWay(0))
    val victimLineAddress = knownLineAddress.getOrElse(Cat(victimTag, set))
    activeWay := way
    writebackPurpose := purpose
    if (captureAddress) {
      writebackTag := victimTag
      writebackSet := set
    }
    victimPrefetchCancelValid := true.B
    victimPrefetchCancelLine := victimLineAddress
    victimReadPending := false.B
    state := fetchVictim
  }

  def clearLine(set: UInt, way: UInt): Unit = {
    valid(set) := valid(set) & ~UIntToOH(way, config.ways)
    dirty(set) := dirty(set) & ~UIntToOH(way, config.ways)
  }

  def advanceMaintenance(): Unit = {
    val set = setOf(activeMaintenance.address)
    clearLine(set, maintenanceWay)
    when(maintenanceWay === (config.ways - 1).U) {
      state := maintenanceFinish
    }.otherwise {
      maintenanceWay := maintenanceWay + 1.U
      state := maintenanceCheck
    }
  }

  def commitActiveRefill(): Unit = {
    for (way <- 0 until config.ways) {
      when(activeWay === way.U) {
        tags(way).write(requestSet, requestTag)
      }
    }
    valid(requestSet) := valid(requestSet) | UIntToOH(activeWay, config.ways)
    dirty(requestSet) :=
      (dirty(requestSet) & ~UIntToOH(activeWay, config.ways)) |
        Mux(activeRequest.write, UIntToOH(activeWay, config.ways), 0.U)
    lru(requestSet) := ~lru(requestSet)
  }

  when(writebackActive) {
    io.lineWriteReq.valid := !writebackRequestSent
    io.lineWriteReq.bits.txnId := 0.U
    io.lineWriteReq.bits.lineAddress :=
      Cat(writebackTag, writebackSet, 0.U(config.offsetWidth.W))
    io.lineWriteReq.bits.data := victimLine
    io.lineWriteAck.ready := writebackRequestSent
    when(io.lineWriteReq.fire) { writebackRequestSent := true.B }
    when(io.lineWriteAck.fire) {
      assert(io.lineWriteAck.bits.txnId === 0.U)
      writebackActive := false.B
      writebackRequestSent := false.B
    }
  }

  val dataMaintenanceOffered =
    io.maintenance.valid && io.maintenance.bits.select === CacheSelect.Data
  val prefetchVictim = Mux(
    !prefetchValid(0),
    0.U,
    Mux(!prefetchValid(1), 1.U, prefetchReplace)
  )

  io.linePrefetchReq.valid :=
    prefetchEnabled && pendingPrefetchValid && !inflightPrefetchValid &&
      !prefetchCommitValid &&
      !io.request.fire && !(state === lookup && requestPrefetchHit) &&
      !io.uncachedProbe.valid && !dataMaintenanceOffered
  io.linePrefetchReq.bits.lineAddress :=
    Cat(pendingPrefetchLine, 0.U(config.offsetWidth.W))
  when(io.linePrefetchReq.fire) {
    inflightPrefetchValid := true.B
    inflightPrefetchLine := pendingPrefetchLine
    inflightPrefetchSlot := prefetchVictim
    inflightPrefetchCancelled := false.B
    pendingPrefetchValid := false.B
    for (entry <- 0 until prefetchEntries) {
      when(prefetchVictim === entry.U) {
        when(prefetchValid(entry) && !prefetchUsed(entry)) {
          prefetchUselessEvent := true.B
        }
        prefetchValid(entry) := false.B
        prefetchUsed(entry) := false.B
      }
    }
    prefetchReplace := ~prefetchVictim
    prefetchRequestEvent := true.B
  }

  when(prefetchCommitValid) {

    when(!inflightPrefetchCancelled) {
      for (entry <- 0 until prefetchEntries) {
        when(prefetchCommitSlot === entry.U) {
          prefetchData(entry) := io.linePrefetchResp.bits.data
          prefetchLineAddress(entry) := prefetchCommitLine
          prefetchValid(entry) := true.B
          prefetchUsed(entry) := false.B
        }
      }
    }
    prefetchCommitValid := false.B
    inflightPrefetchCancelled := false.B
  }

  when(io.linePrefetchResp.fire) {
    assert(inflightPrefetchValid)
    assert(!prefetchCommitValid)
    assert(
      io.linePrefetchResp.bits.lineAddress ===
        Cat(inflightPrefetchLine, 0.U(config.offsetWidth.W))
    )
    prefetchL2HitEvent := io.linePrefetchResp.bits.hit
    prefetchL2MissEvent := !io.linePrefetchResp.bits.hit

    when(io.linePrefetchResp.bits.hit) {
      prefetchCommitValid := true.B
      prefetchCommitLine := inflightPrefetchLine
      prefetchCommitSlot := inflightPrefetchSlot
    }
    inflightPrefetchValid := false.B
  }

  val loadMissEvent = state === lookup && !requestHit && !activeRequest.write
  val storeMissEvent = state === lookup && !requestHit && activeRequest.write
  val demandMissEvent = loadMissEvent || storeMissEvent
  val demandMissMatchesPending =
    pendingPrefetchValid && pendingPrefetchLine === requestLine
  val demandMissMatchesInflight =
    inflightPrefetchValid && inflightPrefetchLine === requestLine
  when(demandMissEvent) {
    observeStream := true.B
    observedLine := requestLine
    when(
      !requestPrefetchHit &&
        (demandMissMatchesPending || demandMissMatchesInflight)
    ) {
      prefetchLateEvent := true.B
      when(demandMissMatchesPending) { pendingPrefetchValid := false.B }
      when(demandMissMatchesInflight) {
        inflightPrefetchCancelled := true.B
      }
    }
  }
  when(loadMissEvent) {
    lastLoadMissValid := true.B
    lastLoadMissLine := requestLine
  }

  val streamMatches = Wire(Vec(2, Bool()))
  for (stream <- 0 until 2) {
    streamMatches(stream) :=
      streamValid(stream) && streamIsStore(stream) === activeRequest.write &&
        observedLine === streamLastLine(stream) + 1.U
  }
  val observedStream = Mux(streamMatches(1), 1.U, 0.U)
  val predictedLine = observedLine + 1.U
  val predictionAlreadyBuffered =
    VecInit((0 until prefetchEntries).map { entry =>
      prefetchValid(entry) && prefetchLineAddress(entry) === predictedLine
    }).asUInt.orR
  val predictionAlreadyInflight =
    inflightPrefetchValid && inflightPrefetchLine === predictedLine
  val predictionAlreadyPending =
    pendingPrefetchValid && pendingPrefetchLine === predictedLine
  val predictionDuplicate =
    predictionAlreadyBuffered || predictionAlreadyInflight || predictionAlreadyPending
  val pendingSlotAvailable = !pendingPrefetchValid || io.linePrefetchReq.fire
  when(prefetchEnabled && observeStream) {
    when(streamMatches.asUInt.orR) {
      for (stream <- 0 until 2) {
        when(observedStream === stream.U) {
          streamLastLine(stream) := observedLine
        }
      }
      prefetchCandidateEvent := true.B
      when(observedLine(5, 0).andR) {
        prefetchPageSuppressedEvent := true.B
      }.elsewhen(predictionDuplicate) {
        prefetchDuplicateEvent := true.B
      }.elsewhen(pendingSlotAvailable) {
        pendingPrefetchValid := true.B
        pendingPrefetchLine := predictedLine
      }.otherwise {
        prefetchDroppedEvent := true.B
      }
    }.otherwise {
      for (stream <- 0 until 2) {
        when(streamReplace === stream.U) {
          streamValid(stream) := true.B
          streamLastLine(stream) := observedLine
          streamIsStore(stream) := activeRequest.write
        }
      }
      streamReplace := ~streamReplace
    }
  }

  when(io.request.fire && io.request.bits.write) {
    for (entry <- 0 until prefetchEntries) {
      when(
        prefetchValid(entry) &&
          prefetchLineAddress(entry) === incomingRequestLine &&
          (incomingRequestHits.asUInt.orR || !incomingRequestPrefetchHits(entry))
      ) {
        when(!prefetchUsed(entry)) { prefetchUselessEvent := true.B }
        prefetchValid(entry) := false.B
      }
    }
    when(pendingPrefetchValid && pendingPrefetchLine === incomingRequestLine) {
      pendingPrefetchValid := false.B
      prefetchCancelledEvent := true.B
    }
    when(
      inflightPrefetchValid &&
        inflightPrefetchLine === incomingRequestLine
    ) {
      inflightPrefetchCancelled := true.B
      prefetchCancelledEvent := true.B
    }
    when(
      prefetchCommitValid && prefetchCommitLine === incomingRequestLine &&
        (incomingRequestHits.asUInt.orR ||
          !incomingRequestPrefetchHits(prefetchCommitSlot))
    ) {
      for (entry <- 0 until prefetchEntries) {
        when(prefetchCommitSlot === entry.U) { prefetchValid(entry) := false.B }
      }
      prefetchCancelledEvent := true.B
    }
  }

  val refillWords = io.lineReadResp.bits.data.asTypeOf(Vec(config.lineWords, UInt(32.W)))
  val refillDemandWord = refillWords(requestWord)
  val refillInstalledLine = Mux(
    activeRequest.write,
    mergeLineWord(
      io.lineReadResp.bits.data,
      requestWord,
      activeRequest.writeData,
      activeRequest.byteEnable
    ),
    io.lineReadResp.bits.data
  )
  val refillWrite = io.lineReadResp.fire
  val prefetchInstallWrite = state === installPrefetch
  val lineFillWrite = refillWrite || prefetchInstallWrite
  val hitStoreWrite = state === lookup && requestHit && activeRequest.write

  val selectedWriteLine = Mux(
    requestHit && activeRequest.write,
    Fill(config.lineWords, activeRequest.writeData),
    Mux(activePrefetchInstall, activePrefetchLine, refillInstalledLine)
  )
  for (way <- 0 until config.ways) {
    val hitStoreWriteWay = hitStoreWrite && requestHitWay === way.U
    val lineFillWriteWay = lineFillWrite && activeWay === way.U
    dataWriteEnable(way) := Mux(
      lineFillWriteWay,
      Fill(config.lineBytes, 1.B),
      Mux(hitStoreWriteWay, wordByteEnable(requestWord, activeRequest.byteEnable), 0.U)
    )
    dataWriteAddress(way) := requestSet
    dataWriteLine(way) := selectedWriteLine
  }

  switch(state) {
    is(idle) {
      val dataMaintenancePending =
        io.maintenance.valid && io.maintenance.bits.select === CacheSelect.Data

      when(writebackActive && (dataMaintenancePending || io.uncachedProbe.valid)) {
        assert(writebackPurpose === afterNormalMiss)
      }.elsewhen(dataMaintenancePending) {
        io.maintenance.ready := true.B
        when(io.maintenance.fire) {
          when(
            VecInit((0 until prefetchEntries).map { entry =>
              prefetchValid(entry) && !prefetchUsed(entry)
            }).asUInt.orR
          ) {
            prefetchUselessEvent := true.B
          }
          prefetchValid.foreach(_ := false.B)
          prefetchUsed.foreach(_ := false.B)
          streamValid.foreach(_ := false.B)
          pendingPrefetchValid := false.B
          when(inflightPrefetchValid) {
            inflightPrefetchCancelled := true.B
            prefetchCancelledEvent := true.B
          }
          activeMaintenance := io.maintenance.bits
          maintenanceWay := 0.U
          when(io.maintenance.bits.operation === CacheOperation.StoreTag) {
            clearLine(setOf(io.maintenance.bits.address), io.maintenance.bits.address(0))
            state := maintenanceFinish
          }.elsewhen(
            io.maintenance.bits.operation === CacheOperation.IndexInvalidate ||
              io.maintenance.bits.operation === CacheOperation.HitInvalidate
          ) {
            state := maintenanceCheck
          }.otherwise {
            state := maintenanceFinish
          }
        }
      }.elsewhen(io.uncachedProbe.valid) {
        io.uncachedProbe.ready := true.B
        when(io.uncachedProbe.fire) {
          for (entry <- 0 until prefetchEntries) {
            when(
              prefetchValid(entry) &&
                prefetchLineAddress(entry) === incomingProbeLine
            ) {
              when(!prefetchUsed(entry)) { prefetchUselessEvent := true.B }
              prefetchValid(entry) := false.B
            }
          }
          when(pendingPrefetchValid && pendingPrefetchLine === incomingProbeLine) {
            pendingPrefetchValid := false.B
            prefetchCancelledEvent := true.B
          }
          when(
            inflightPrefetchValid &&
              inflightPrefetchLine === incomingProbeLine
          ) {
            inflightPrefetchCancelled := true.B
            prefetchCancelledEvent := true.B
          }
          when(
            prefetchCommitValid && prefetchCommitLine === incomingProbeLine
          ) {
            for (entry <- 0 until prefetchEntries) {
              when(prefetchCommitSlot === entry.U) { prefetchValid(entry) := false.B }
            }
            prefetchCancelledEvent := true.B
          }
          assert(
            PopCount(incomingProbeHits) <= 1.U,
            "an uncached probe must not hit more than one data-cache way"
          )

          activeProbeAddress := io.uncachedProbe.bits
          activeProbeHit := incomingProbeHit
          activeProbeWay := Mux(incomingProbeHits(1), 1.U, 0.U)
          activeProbeDirty := Mux(
            incomingProbeHits(1),
            dirty(incomingProbeSet)(1),
            dirty(incomingProbeSet)(0)
          )

          writebackTag := incomingProbeTag
          writebackSet := incomingProbeSet
          state := probeLookup
        }
      }.otherwise {
        io.request.ready := true.B
        when(io.request.fire) {
          activeRequest := io.request.bits
          state := lookup
        }
      }
    }

    is(lookup) {
      when(requestHit) {
        io.response.valid := true.B
        io.request.ready := io.response.ready
        io.response.bits.readData := requestHitData

        when(activeRequest.write) {
          dirty(requestSet) := dirty(requestSet) | UIntToOH(requestHitWay, config.ways)
        }
        when(io.response.fire) {
          lru(requestSet) := requestHits(0)
          when(io.request.fire) {
            activeRequest := io.request.bits
            state := lookup
          }.otherwise {
            state := idle
          }
        }
      }.elsewhen(requestPrefetchHit) {
        activePrefetchInstall := true.B
        for (entry <- 0 until prefetchEntries) {
          when(requestPrefetchHits(entry)) {
            prefetchUsed(entry) := true.B
            prefetchValid(entry) := false.B
          }
        }
        prefetchBufferHitEvent := true.B
        val victimWay = lru(requestSet)
        activeWay := victimWay
        when(writebackActive) {
          assert(writebackPurpose === afterNormalMiss)
          state := waitPreviousWriteback
        }.elsewhen(validRead(victimWay) && dirty(requestSet)(victimWay)) {
          startWriteback(victimWay, afterNormalMiss)
        }.otherwise {
          state := installPrefetch
        }
      }.otherwise {
        val victimWay = lru(requestSet)
        activeWay := victimWay
        when(writebackActive) {
          assert(writebackPurpose === afterNormalMiss)
          state := waitPreviousWriteback
        }.elsewhen(validRead(victimWay) && dirty(requestSet)(victimWay)) {
          startWriteback(victimWay, afterNormalMiss)
        }.otherwise {
          state := waitReadRequest
        }
      }
    }

    is(waitPreviousWriteback) {

      when(!writebackActive) {
        val victimWay = lru(requestSet)
        activeWay := victimWay
        when(validRead(victimWay) && dirty(requestSet)(victimWay)) {
          startWriteback(victimWay, afterNormalMiss)
        }.otherwise {
          state := Mux(activePrefetchInstall, installPrefetch, waitReadRequest)
        }
      }
    }

    is(probeLookup) {
      when(!activeProbeHit) {

        io.uncachedProbeDone := true.B
        io.uncachedProbeNoAlias := true.B
        state := idle
      }.elsewhen(activeProbeDirty) {
        startWriteback(
          activeProbeWay,
          afterProbe,
          knownLineAddress = Some(lineOf(activeProbeAddress)),
          captureAddress = false
        )
      }.otherwise {
        clearLine(probeSet, activeProbeWay)
        state := probeFinish
      }
    }

    is(maintenanceCheck) {
      val set = setOf(activeMaintenance.address)
      activeWay := maintenanceWay
      when(valid(set)(maintenanceWay) && dirty(set)(maintenanceWay)) {
        startWriteback(maintenanceWay, afterMaintenance)
      }.otherwise {
        advanceMaintenance()
      }
    }

    is(fetchVictim) {
      dataLineReadAddress := writebackSet
      dataLineReadEnable := !victimReadPending
      val victimReadLine = Mux(
        activeWay === 1.U,
        dataReadRawLines(1),
        dataReadRawLines(0)
      )
      when(!victimReadPending) { victimReadPending := true.B }
      when(victimReadPending) {
        victimLine := victimReadLine
        victimReadPending := false.B
        writebackActive := true.B
        writebackRequestSent := false.B
        when(writebackPurpose === afterNormalMiss) {
          state := Mux(activePrefetchInstall, installPrefetch, waitReadRequest)
        }.otherwise {
          state := waitWriteResponse
        }
      }
    }

    is(waitWriteResponse) {
      when(io.lineWriteAck.fire) {
        switch(writebackPurpose) {
          is(afterProbe) {
            clearLine(probeSet, activeWay)
            state := probeFinish
          }
          is(afterMaintenance) { advanceMaintenance() }
        }
      }
    }

    is(waitReadRequest) {
      io.lineReadReq.valid := true.B
      io.lineReadReq.bits.source := CacheLineSource.Data
      io.lineReadReq.bits.txnId := 0.U
      io.lineReadReq.bits.lineAddress := Cat(
        activeRequest.physicalAddress(31, config.offsetWidth),
        0.U(config.offsetWidth.W)
      )
      when(io.lineReadReq.fire) { state := refill }
    }

    is(refill) {
      io.lineReadResp.ready := true.B
      when(io.lineReadResp.fire) {
        for (entry <- 0 until prefetchEntries) {
          when(prefetchValid(entry) && prefetchLineAddress(entry) === requestLine) {
            when(!prefetchUsed(entry)) { prefetchUselessEvent := true.B }
            prefetchValid(entry) := false.B
          }
        }
        responseWord := Mux(
          activeRequest.write,
          mergeBytes(refillDemandWord, activeRequest.writeData, activeRequest.byteEnable),
          refillDemandWord
        )
        directRefillEvent := true.B
        commitActiveRefill()
        state := respondRefill
        assert(io.lineReadResp.bits.source === CacheLineSource.Data)
        assert(io.lineReadResp.bits.txnId === 0.U)
        assert(
          io.lineReadResp.bits.lineAddress ===
            Cat(activeRequest.physicalAddress(31, config.offsetWidth), 0.U(config.offsetWidth.W))
        )
      }
    }

    is(respondRefill) {
      io.response.valid := true.B
      io.response.bits.readData := responseWord
      io.request.ready := io.response.ready
      when(io.response.fire) {
        when(io.request.fire) {
          activeRequest := io.request.bits
          state := lookup
        }.otherwise {
          state := idle
        }
      }
    }

    is(installPrefetch) {
      responseWord := selectLineWord(activePrefetchLine, requestWord)
      commitActiveRefill()
      activePrefetchInstall := false.B
      state := respondRefill
    }

    is(probeFinish) {
      io.uncachedProbeDone := true.B
      state := idle
    }

    is(maintenanceFinish) {
      io.maintenanceDone := true.B
      state := idle
    }
  }

  when(victimPrefetchCancelValid) {
    for (entry <- 0 until prefetchEntries) {
      when(
        prefetchValid(entry) &&
          prefetchLineAddress(entry) === victimPrefetchCancelLine
      ) {
        when(!prefetchUsed(entry)) { prefetchUselessEvent := true.B }
        prefetchValid(entry) := false.B
      }
    }
    when(
      pendingPrefetchValid &&
        pendingPrefetchLine === victimPrefetchCancelLine
    ) {
      pendingPrefetchValid := false.B
      prefetchCancelledEvent := true.B
    }
    when(
      inflightPrefetchValid &&
        inflightPrefetchLine === victimPrefetchCancelLine
    ) {
      inflightPrefetchCancelled := true.B
      prefetchCancelledEvent := true.B
    }
    when(
      prefetchCommitValid &&
        prefetchCommitLine === victimPrefetchCancelLine
    ) {
      for (entry <- 0 until prefetchEntries) {
        when(prefetchCommitSlot === entry.U) { prefetchValid(entry) := false.B }
      }
      prefetchCancelledEvent := true.B
    }
    victimPrefetchCancelValid := false.B
  }

  when(io.profile.miss) {
    missResponsePending := true.B
  }
  when(io.response.fire) {
    missResponsePending := false.B
  }
  when(io.lineReadResp.valid) {
    assert(state === refill)
  }
  when(io.linePrefetchReq.fire) {
    assert(io.linePrefetchReq.bits.lineAddress(config.offsetWidth - 1, 0) === 0.U)
  }
  when(dataWordReadEnable && dataWriteEnable.asUInt.orR) {
    assert(state === lookup && requestHit && activeRequest.write)
  }
  when(dataLineReadEnable) {
    assert(!dataWriteEnable.asUInt.orR)
  }

  io.writebackIdle := !writebackActive && state =/= fetchVictim &&
    state =/= waitWriteResponse
  io.profile.request := io.request.fire
  io.profile.hit := state === lookup && requestHit && io.response.fire
  io.profile.miss := state === lookup && !requestHit
  io.profile.missBusy := io.profile.miss || missResponsePending
  io.profile.refillBusy :=
    io.profile.miss ||
      (state === fetchVictim && writebackPurpose === afterNormalMiss) ||
      state === waitReadRequest || state === refill || state === installPrefetch ||
      state === respondRefill
  io.profile.postResponseRefillBusy := false.B
  io.profile.directRefill := directRefillEvent
  io.profile.earlyResponse := earlyLoadResponseEvent
  io.profile.dirtyWriteback := dirtyWritebackEvent
  io.profile.dirtyVictim := dirtyVictimEvent
  io.profile.tailBlockedWouldHit := false.B
  io.profile.tailBlockedSameFillLine := false.B
  io.profile.tailBlockedNewMiss := false.B
  io.profile.tailBlockedStore := false.B
  io.profile.dirtyVictimCaptureBusy :=
    state === fetchVictim && writebackPurpose === afterNormalMiss
  io.profile.dirtyVictimReadAddressWait :=
    state === waitReadRequest && writebackActive && writebackPurpose === afterNormalMiss
  io.profile.dirtyVictimResponseWait := false.B
  io.profile.loadMiss := loadMissEvent
  io.profile.storeMiss := storeMissEvent
  io.profile.loadMissPlusOne :=
    loadMissEvent && lastLoadMissValid && requestLine === lastLoadMissLine + 1.U
  io.profile.loadMissMinusOne :=
    loadMissEvent && lastLoadMissValid && requestLine + 1.U === lastLoadMissLine
  io.profile.loadMissRepeat :=
    loadMissEvent && lastLoadMissValid && requestLine === lastLoadMissLine
  io.profile.prefetchCandidate := prefetchCandidateEvent
  io.profile.prefetchRequest := prefetchRequestEvent
  io.profile.prefetchL2Hit := prefetchL2HitEvent
  io.profile.prefetchL2Miss := prefetchL2MissEvent
  io.profile.prefetchBufferHit := prefetchBufferHitEvent
  io.profile.prefetchLate := prefetchLateEvent
  io.profile.prefetchDropped := prefetchDroppedEvent
  io.profile.prefetchDuplicate := prefetchDuplicateEvent
  io.profile.prefetchPageSuppressed := prefetchPageSuppressedEvent
  io.profile.prefetchCancelled := prefetchCancelledEvent
  io.profile.prefetchUseless := prefetchUselessEvent
}
