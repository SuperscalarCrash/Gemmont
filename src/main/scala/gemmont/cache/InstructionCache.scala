package gemmont.cache

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.{FrontendConfig, ICacheConfig}
import gemmont.common.{CacheLineSource, LineReadReq, LineReadResp}
import gemmont.debug.CacheProfileObservation
import gemmont.isa.{CacheOperation, CacheSelect}
import scala.annotation.nowarn

@nowarn("cat=deprecation")
class InstructionCache(
    cacheConfig: ICacheConfig = ICacheConfig(),
    frontendConfig: FrontendConfig = FrontendConfig()
) extends Module {
  require(cacheConfig.sets == 64 && cacheConfig.ways == 2 && cacheConfig.lineBytes == 64)
  require(frontendConfig.fetchWidth == 4)

  private val setWidth = log2Ceil(cacheConfig.sets)
  private val wordWidth = log2Ceil(cacheConfig.lineWords)
  private val tagWidth = 32 - cacheConfig.tagOffset
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new InstructionCacheRequest))

    val lookupPhysicalAddress = Input(UInt(32.W))
    val lookupFault = Input(Bool())
    val lookupReady = Input(Bool())

    val lookupAdvance = Input(Bool())

    val requestReadSet = Input(UInt(setWidth.W))
    val response = Decoupled(new InstructionCacheResponse(frontendConfig.fetchWidth))
    val maintenance = Input(Valid(new CacheMaintenanceRequest))
    val flush = Input(Bool())
    val pipelineBlocked = Output(Bool())
    val lineReadReq = Decoupled(new LineReadReq)
    val lineReadResp = Flipped(Decoupled(new LineReadResp(lineBytes = cacheConfig.lineBytes)))
    val profile = Output(new CacheProfileObservation)
  })

  val valid = RegInit(VecInit(Seq.fill(cacheConfig.sets)(0.U(cacheConfig.ways.W))))

  val tags = Seq.fill(cacheConfig.ways)(Mem(cacheConfig.sets, UInt(tagWidth.W)))
  tags.foreach { memory =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"distributed\"")
    })
  }
  val lru = RegInit(VecInit(Seq.fill(cacheConfig.sets)(0.U(1.W))))

  val data = Seq.fill(cacheConfig.ways)(
    SyncReadMem(cacheConfig.sets, UInt((cacheConfig.lineWords * 32).W))
  )
  data.foreach { memory =>
    annotate(new ChiselAnnotation {
      override def toFirrtl = firrtl.AttributeAnnotation(memory.toTarget, "ram_style = \"block\"")
    })
  }
  val dataWriteValid = Wire(Vec(cacheConfig.ways, Bool()))
  val dataWriteSet = Wire(Vec(cacheConfig.ways, UInt(setWidth.W)))
  val dataWriteLine = Wire(Vec(cacheConfig.ways, Vec(cacheConfig.lineWords, UInt(32.W))))
  dataWriteValid.foreach(_ := false.B)
  dataWriteSet.foreach(_ := 0.U)
  dataWriteLine.foreach(_ := VecInit(Seq.fill(cacheConfig.lineWords)(0.U)))

  val requestReadSetByWay = Seq.fill(cacheConfig.ways)(io.requestReadSet)
  val ramReadAdvance = Wire(Bool())

  val dataReadLines = data.zip(requestReadSetByWay).map { case (memory, requestReadSet) =>
    memory
      .read(requestReadSet, ramReadAdvance)
      .asTypeOf(Vec(cacheConfig.lineWords, UInt(32.W)))
  }
  for (way <- 0 until cacheConfig.ways) {
    when(dataWriteValid(way)) {
      data(way).write(dataWriteSet(way), dataWriteLine(way).asUInt)
    }
  }

  io.lineReadReq.valid := false.B
  io.lineReadReq.bits := 0.U.asTypeOf(io.lineReadReq.bits)
  io.lineReadResp.ready := false.B
  io.request.ready := false.B
  io.response.valid := false.B
  io.response.bits := 0.U.asTypeOf(io.response.bits)

  val idle :: lookup :: waitAddress :: refill :: commit :: discardFinish :: respondRefill :: Nil =
    Enum(7)
  val state = RegInit(idle)
  val active = Reg(new InstructionCacheRequest)

  io.response.bits.pc := active.virtualAddress
  val lookupSetByWay = Seq.fill(cacheConfig.ways)(Reg(UInt(setWidth.W)))
  lookupSetByWay.foreach { lookupSet =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(lookupSet.toTarget, "dont_touch = \"yes\"")
    })
  }
  val replacementWay = Reg(UInt(1.W))
  val refillPacket = Reg(Vec(frontendConfig.fetchWidth, UInt(32.W)))
  val refillLine = Reg(Vec(cacheConfig.lineWords, UInt(32.W)))
  val discardRefill = RegInit(false.B)
  val cancelRefillCommit = RegInit(false.B)

  val activeSet = active.virtualAddress(cacheConfig.tagOffset - 1, cacheConfig.offsetWidth)
  val lookupTag = io.lookupPhysicalAddress(31, cacheConfig.tagOffset)
  val refillTag = active.physicalAddress(31, cacheConfig.tagOffset)
  val activeWord = active.virtualAddress(cacheConfig.offsetWidth - 1, 2)
  val instructionMaintenance =
    io.maintenance.valid &&
      io.maintenance.bits.select === CacheSelect.Instruction &&
      io.maintenance.bits.operation =/= CacheOperation.None
  val maintenanceIndex =
    io.maintenance.bits.address(cacheConfig.tagOffset - 1, cacheConfig.offsetWidth)

  val maintenanceConflictsWithRefill =
    instructionMaintenance && maintenanceIndex === activeSet
  val lookupHits = Wire(Vec(cacheConfig.ways, Bool()))
  for (way <- 0 until cacheConfig.ways) {
    lookupHits(way) :=
      valid(lookupSetByWay(way))(way) && tags(way).read(lookupSetByWay(way)) === lookupTag
  }
  val lookupHit = lookupHits.asUInt.orR
  val lookupWay = Mux(lookupHits(1), 1.U, 0.U)
  ramReadAdvance :=
    state === idle ||
      (state === lookup && io.lookupReady && io.lookupAdvance)

  io.pipelineBlocked :=
    (state === lookup && (!io.lookupReady || (!io.lookupFault && !lookupHit))) ||
      state === waitAddress || state === refill || state === commit
  io.profile.request := io.request.fire
  io.profile.hit :=
    state === lookup && io.lookupReady && !io.lookupFault && lookupHit && io.response.fire
  io.profile.miss :=
    state === lookup && io.lookupReady && !io.lookupFault && !lookupHit
  io.profile.missBusy :=
    io.profile.miss || state === waitAddress || state === refill || state === commit
  io.profile.refillBusy := io.profile.missBusy
  io.profile.postResponseRefillBusy := false.B
  io.profile.directRefill := false.B
  io.profile.earlyResponse := false.B
  io.profile.dirtyWriteback := false.B
  io.profile.dirtyVictim := false.B
  io.profile.tailBlockedWouldHit := false.B
  io.profile.tailBlockedSameFillLine := false.B
  io.profile.tailBlockedNewMiss := false.B
  io.profile.tailBlockedStore := false.B
  io.profile.dirtyVictimCaptureBusy := false.B
  io.profile.dirtyVictimReadAddressWait := false.B
  io.profile.dirtyVictimResponseWait := false.B
  io.profile.loadMiss := false.B
  io.profile.storeMiss := false.B
  io.profile.loadMissPlusOne := false.B
  io.profile.loadMissMinusOne := false.B
  io.profile.loadMissRepeat := false.B
  io.profile.prefetchCandidate := false.B
  io.profile.prefetchRequest := false.B
  io.profile.prefetchL2Hit := false.B
  io.profile.prefetchL2Miss := false.B
  io.profile.prefetchBufferHit := false.B
  io.profile.prefetchLate := false.B
  io.profile.prefetchDropped := false.B
  io.profile.prefetchDuplicate := false.B
  io.profile.prefetchPageSuppressed := false.B
  io.profile.prefetchCancelled := false.B
  io.profile.prefetchUseless := false.B

  def drivePacketFromCache(way: UInt): Unit = {
    io.response.bits.pc := active.virtualAddress
    for (lane <- 0 until frontendConfig.fetchWidth) {
      val inLine = activeWord < (cacheConfig.lineWords - lane).U
      val word = (activeWord + lane.U)(wordWidth - 1, 0)
      val selectedLine = Mux(way === 1.U, dataReadLines(1), dataReadLines(0))
      io.response.bits.words(lane) := Mux(inLine, selectedLine(word), 0.U)
    }
    io.response.bits.validMask := VecInit((0 until frontendConfig.fetchWidth).map { lane =>
      activeWord < (cacheConfig.lineWords - lane).U
    }).asUInt
  }

  def driveFaultPacket(): Unit = {
    io.response.bits.pc := active.virtualAddress
    for (lane <- 0 until frontendConfig.fetchWidth) {
      io.response.bits.words(lane) := 0.U
    }
    io.response.bits.validMask := VecInit((0 until frontendConfig.fetchWidth).map { lane =>
      activeWord < (cacheConfig.lineWords - lane).U
    }).asUInt
  }

  when(ramReadAdvance) {
    for (way <- 0 until cacheConfig.ways) {
      lookupSetByWay(way) := requestReadSetByWay(way)
    }
  }

  when(io.request.ready) {
    active := io.request.bits
  }

  switch(state) {
    is(idle) {
      io.request.ready := true.B
      when(io.request.fire) {
        discardRefill := false.B
        cancelRefillCommit := false.B
        state := lookup
      }
    }
    is(lookup) {
      when(!io.lookupReady) {}
        .elsewhen(io.lookupFault) {
          io.response.valid := true.B

          io.request.ready := io.response.ready && ramReadAdvance
          driveFaultPacket()
          when(io.response.fire) {
            state := Mux(io.request.fire, lookup, idle)
          }
        }
        .elsewhen(lookupHit) {
          io.response.valid := true.B
          io.request.ready := io.response.ready && ramReadAdvance
          drivePacketFromCache(lookupWay)
          when(io.response.fire) {

            lru(activeSet) := lookupHits(0)
            when(io.request.fire) {
              state := lookup
            }.otherwise {
              state := idle
            }
          }
        }
        .otherwise {

          active.physicalAddress := io.lookupPhysicalAddress
          replacementWay := lru(activeSet)
          state := waitAddress
        }
    }
    is(waitAddress) {
      io.lineReadReq.valid := true.B
      io.lineReadReq.bits.source := CacheLineSource.Instruction
      io.lineReadReq.bits.txnId := 0.U
      io.lineReadReq.bits.lineAddress := Cat(
        active.physicalAddress(31, cacheConfig.offsetWidth),
        0.U(cacheConfig.offsetWidth.W)
      )
      when(io.lineReadReq.fire) { state := refill }
    }
    is(refill) {
      io.lineReadResp.ready := true.B
      when(io.lineReadResp.fire) {
        val words = io.lineReadResp.bits.data.asTypeOf(Vec(cacheConfig.lineWords, UInt(32.W)))
        refillLine := words
        for (lane <- 0 until frontendConfig.fetchWidth) {
          refillPacket(lane) := words((activeWord + lane.U)(wordWidth - 1, 0))
        }
        state := commit
        assert(io.lineReadResp.bits.source === CacheLineSource.Instruction)
        assert(io.lineReadResp.bits.txnId === 0.U)
        assert(
          io.lineReadResp.bits.lineAddress ===
            Cat(active.physicalAddress(31, cacheConfig.offsetWidth), 0.U(cacheConfig.offsetWidth.W))
        )
      }
    }
    is(commit) {
      val cancelInstall = cancelRefillCommit || maintenanceConflictsWithRefill

      for (way <- 0 until cacheConfig.ways) {
        when(replacementWay === way.U) {
          dataWriteValid(way) := true.B
          dataWriteSet(way) := activeSet
          dataWriteLine(way) := refillLine
          tags(way).write(activeSet, refillTag)
        }
      }
      when(!cancelInstall) {
        lru(activeSet) := ~lru(activeSet)
        valid(activeSet) := valid(activeSet) | UIntToOH(replacementWay, cacheConfig.ways)
      }
      cancelRefillCommit := false.B
      val discardResponse = discardRefill || io.flush || cancelInstall
      state := Mux(discardResponse, discardFinish, respondRefill)
    }
    is(discardFinish) {

      state := idle
    }
    is(respondRefill) {
      io.response.valid := true.B
      io.response.bits.pc := active.virtualAddress
      for (lane <- 0 until frontendConfig.fetchWidth) {
        val inLine = activeWord < (cacheConfig.lineWords - lane).U
        io.response.bits.words(lane) := Mux(inLine, refillPacket(lane), 0.U)
      }
      io.response.bits.validMask := VecInit((0 until frontendConfig.fetchWidth).map { lane =>
        activeWord < (cacheConfig.lineWords - lane).U
      }).asUInt
      when(io.response.fire) { state := idle }
    }
  }

  when(io.flush) {
    switch(state) {
      is(lookup) {

        state := Mux(io.request.fire, lookup, idle)
      }
      is(waitAddress) {

        discardRefill := true.B
        state := Mux(io.lineReadReq.fire, refill, idle)
      }
      is(refill) {
        discardRefill := true.B
      }
      is(commit) {
        discardRefill := true.B
        state := discardFinish
      }
      is(respondRefill) {
        state := idle
      }
      is(discardFinish) {
        state := idle
      }
    }
  }

  when(instructionMaintenance) {
    val way = io.maintenance.bits.address(0)
    val maintenanceInvalidWays = WireDefault(0.U(cacheConfig.ways.W))
    switch(io.maintenance.bits.operation) {
      is(CacheOperation.IndexInvalidate) {
        maintenanceInvalidWays := UIntToOH(way, cacheConfig.ways)
      }
      is(CacheOperation.HitInvalidate) {
        maintenanceInvalidWays := Fill(cacheConfig.ways, 1.B)
      }
      is(CacheOperation.StoreTag) {
        maintenanceInvalidWays := UIntToOH(way, cacheConfig.ways)
      }
    }

    val cancelRefillState = state === waitAddress || state === refill || state === commit
    val cancelledReplacement = Mux(
      maintenanceConflictsWithRefill && cancelRefillState,
      UIntToOH(replacementWay, cacheConfig.ways),
      0.U
    )
    valid(maintenanceIndex) :=
      valid(maintenanceIndex) & ~(maintenanceInvalidWays | cancelledReplacement)
    when(state === waitAddress || state === refill) {
      when(maintenanceConflictsWithRefill) {
        cancelRefillCommit := true.B
      }
    }
  }
}
