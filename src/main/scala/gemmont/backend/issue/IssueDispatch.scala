package gemmont.backend.issue

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.decode.{RenamedEntry, RenamePacket}
import gemmont.isa._

class IssueDispatch extends Module {
  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new RenamePacket))
    val busyAddress = Output(
      Vec(6, UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val busy = Input(Vec(6, Bool()))
    val globalWakeup = Input(
      Vec(5, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val sameCycleWakeup = Input(
      Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val integerSameCycleWakeup = Input(
      Vec(3, Vec(7, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))))
    )
    val integerIssueFire = Input(Bool())
    val mulDivIssueFire = Input(Bool())
    val memoryIssueFire = Input(Bool())
    val flush = Input(Bool())

    val integer = Output(Vec(3, Valid(new TypedIssueSlot(new IntegerIssueMicroOp))))
    val mulDiv = Output(Valid(new TypedIssueSlot(new MulDivIssueMicroOp)))
    val memory = Output(Valid(new TypedIssueSlot(new MemoryIssueMicroOp)))
    val integerOccupancy = Output(UInt(3.W))
    val mulDivOccupancy = Output(UInt(2.W))
    val memoryOccupancy = Output(UInt(3.W))
    val memoryHeadOperandReady = Output(Vec(2, Bool()))
  })

  val integerQueue = Module(new TypedIntegerIssueQueue(new IntegerIssueMicroOp))
  val mulDivQueue = Module(new TypedInOrderIssueQueue(3, new MulDivIssueMicroOp))
  val memoryQueue = Module(new TypedInOrderIssueQueue(5, new MemoryIssueMicroOp))
  integerQueue.io.globalWakeup := io.globalWakeup
  mulDivQueue.io.wakeup := io.globalWakeup
  memoryQueue.io.wakeup := io.globalWakeup
  integerQueue.io.sameCycleWakeup := io.integerSameCycleWakeup
  mulDivQueue.io.sameCycleWakeup := io.sameCycleWakeup
  memoryQueue.io.sameCycleWakeup := io.sameCycleWakeup
  integerQueue.io.issueFire := io.integerIssueFire
  mulDivQueue.io.issueFire := io.mulDivIssueFire
  memoryQueue.io.issueFire := io.memoryIssueFire
  integerQueue.io.flush := io.flush
  mulDivQueue.io.flush := io.flush
  memoryQueue.io.flush := io.flush
  io.integer := integerQueue.io.issued
  io.mulDiv := mulDivQueue.io.issued
  io.memory := memoryQueue.io.issued
  io.integerOccupancy := integerQueue.io.occupancy
  io.mulDivOccupancy := mulDivQueue.io.occupancy
  io.memoryOccupancy := memoryQueue.io.occupancy
  io.memoryHeadOperandReady := memoryQueue.io.headOperandReady

  val entries = io.dispatch.bits.entries
  for (lane <- 0 until 3) {
    io.busyAddress(2 * lane) := entries(lane).bits.rename.source(0)
    io.busyAddress(2 * lane + 1) := entries(lane).bits.rename.source(1)
  }

  def executable(entry: RenamedEntry): Bool = !entry.decoded.microOp.exception.valid
  val integerMatch = VecInit(entries.map { entry =>
    val unit = entry.bits.decoded.microOp.functionalUnit
    entry.valid && executable(entry.bits) && (
      unit === FunctionalUnit.Alu || unit === FunctionalUnit.Compare ||
        unit === FunctionalUnit.Csr || unit === FunctionalUnit.Timer ||
        unit === FunctionalUnit.InvTlb
    )
  })
  val mulDivMatch = VecInit(entries.map { entry =>
    val unit = entry.bits.decoded.microOp.functionalUnit
    entry.valid && executable(entry.bits) && (
      unit === FunctionalUnit.Mul || unit === FunctionalUnit.MulHigh ||
        unit === FunctionalUnit.Div || unit === FunctionalUnit.Mod ||
        unit === FunctionalUnit.Dp4
    )
  })
  val memoryMatch = VecInit(entries.map { entry =>
    entry.valid && executable(entry.bits) &&
    entry.bits.decoded.microOp.functionalUnit === FunctionalUnit.LoadStore
  })

  def commonSlot[T <: Data](target: TypedIssueSlot[T], entry: RenamedEntry): Unit = {
    val microOp = entry.decoded.microOp
    target.functionalUnit := microOp.functionalUnit
    target.operands(0).physical := entry.rename.source(0)
    target.operands(1).physical := entry.rename.source(1)
    target.writeRegister := microOp.writeRegister
    target.writePhysical := entry.rename.write
    target.robIndex := entry.robIndex
  }

  val integerSlots = Wire(Vec(3, new TypedIssueSlot(new IntegerIssueMicroOp)))
  val mulDivSlots = Wire(Vec(3, new TypedIssueSlot(new MulDivIssueMicroOp)))
  val memorySlots = Wire(Vec(3, new TypedIssueSlot(new MemoryIssueMicroOp)))
  for (lane <- 0 until 3) {
    val entry = entries(lane).bits
    val microOp = entry.decoded.microOp

    commonSlot(integerSlots(lane), entry)

    integerSlots(lane).operands(0).ready := !microOp.useRj || !io.busy(2 * lane)
    integerSlots(lane).operands(1).ready :=
      !(microOp.useRk || microOp.useRd) || !io.busy(2 * lane + 1)
    integerSlots(lane).payload.pc := microOp.pc
    integerSlots(lane).payload.instruction := microOp.instruction
    integerSlots(lane).payload.prediction := entry.decoded.prediction
    integerSlots(lane).payload.functionalUnit := microOp.functionalUnit
    integerSlots(lane).payload.useRj := microOp.useRj
    integerSlots(lane).payload.useRk := microOp.useRk
    integerSlots(lane).payload.useRd := microOp.useRd
    integerSlots(lane).payload.writeRegister := microOp.writeRegister
    integerSlots(lane).payload.immediateExtend := microOp.immediateExtend
    integerSlots(lane).payload.aluOperation := microOp.aluOperation
    integerSlots(lane).payload.compareOperation := microOp.compareOperation
    integerSlots(lane).payload.isBranch := microOp.isBranch
    integerSlots(lane).payload.isJump := microOp.isJump
    integerSlots(lane).payload.isJumpRegister := microOp.isJumpRegister
    integerSlots(lane).payload.writeCsr := microOp.writeCsr
    integerSlots(lane).payload.readCsr := microOp.readCsr
    integerSlots(lane).payload.readTimerLow := microOp.readTimerLow
    integerSlots(lane).payload.readTimerHigh := microOp.readTimerHigh
    integerSlots(lane).payload.readTimerId := microOp.readTimerId
    integerSlots(lane).payload.tlbOperation := microOp.tlbOperation

    commonSlot(mulDivSlots(lane), entry)
    mulDivSlots(lane).operands(0).ready := !microOp.useRj || !io.busy(2 * lane)
    mulDivSlots(lane).operands(1).ready := !microOp.useRk || !io.busy(2 * lane + 1)
    mulDivSlots(lane).payload.functionalUnit := microOp.functionalUnit
    mulDivSlots(lane).payload.writeRegister := microOp.writeRegister
    mulDivSlots(lane).payload.signed := microOp.signed

    commonSlot(memorySlots(lane), entry)
    memorySlots(lane).operands(0).ready := !microOp.useRj || !io.busy(2 * lane)
    memorySlots(lane).operands(1).ready :=
      !(microOp.useRk || microOp.useRd) || !io.busy(2 * lane + 1)
    memorySlots(lane).payload.pc := microOp.pc
    memorySlots(lane).payload.instruction := microOp.instruction
    memorySlots(lane).payload.writeRegister := microOp.writeRegister
    memorySlots(lane).payload.operation := microOp.loadStoreOperation
    memorySlots(lane).payload.isLoad := microOp.isLoad
    memorySlots(lane).payload.isStore := microOp.isStore
    memorySlots(lane).payload.isBarrier := microOp.isBarrier
    memorySlots(lane).payload.isLoadLinked := microOp.isLoadLinked
    memorySlots(lane).payload.isStoreConditional := microOp.isStoreConditional
    val normalImmediate = Cat(Fill(20, microOp.instruction(21)), microOp.instruction(21, 10))
    val llscImmediate = Cat(
      Fill(16, microOp.instruction(23)),
      microOp.instruction(23, 10),
      0.U(2.W)
    )
    memorySlots(lane).payload.immediate := Mux(
      microOp.isLoadLinked || microOp.isStoreConditional,
      llscImmediate,
      normalImmediate
    )
    memorySlots(lane).payload.cacheOperation := CacheOperation.None
    memorySlots(lane).payload.cacheSelect := CacheSelect.Data
    when(microOp.loadStoreOperation === LoadStoreOp.CacheOperation) {
      memorySlots(lane).payload.cacheOperation := MuxLookup(
        microOp.instruction(4, 3),
        CacheOperation.None
      )(
        Seq(
          0.U -> CacheOperation.StoreTag,
          1.U -> CacheOperation.IndexInvalidate,
          2.U -> CacheOperation.HitInvalidate
        )
      )
      memorySlots(lane).payload.cacheSelect := MuxLookup(
        microOp.instruction(2, 0),
        CacheSelect.None
      )(Seq(0.U -> CacheSelect.Instruction, 1.U -> CacheSelect.Data))
    }
  }

  def queueReadyForLane(matches: Vec[Bool], ready: Vec[Bool], lane: Int): Bool = {
    val port = if (lane == 0) 0.U else PopCount(matches.take(lane))
    MuxLookup(port, ready(0))((0 until 3).map(index => index.U -> ready(index)))
  }

  val integerReady = VecInit(integerQueue.io.enqueue.map(_.ready))
  val mulDivReady = VecInit(mulDivQueue.io.enqueue.map(_.ready))
  val memoryReady = VecInit(memoryQueue.io.enqueue.map(_.ready))
  val allReady = (0 until 3)
    .map { lane =>
      (!integerMatch(lane) || queueReadyForLane(integerMatch, integerReady, lane)) &&
      (!mulDivMatch(lane) || queueReadyForLane(mulDivMatch, mulDivReady, lane)) &&
      (!memoryMatch(lane) || queueReadyForLane(memoryMatch, memoryReady, lane))
    }
    .reduce(_ && _)
  io.dispatch.ready := allReady

  def connectCompacted[T <: Data](
      matches: Vec[Bool],
      source: Vec[TypedIssueSlot[T]],
      destination: Vec[DecoupledIO[TypedIssueSlot[T]]]
  ): Unit = {
    for (port <- 0 until 3) {
      val selects = VecInit((0 until 3).map { lane =>
        val prior = if (lane == 0) 0.U else PopCount(matches.take(lane))
        matches(lane) && prior === port.U
      })
      destination(port).valid := io.dispatch.valid && allReady && selects.asUInt.orR
      destination(port).bits := 0.U.asTypeOf(destination(port).bits)
      for (lane <- 0 until 3) {
        when(selects(lane)) { destination(port).bits := source(lane) }
      }
    }
  }
  connectCompacted(integerMatch, integerSlots, integerQueue.io.enqueue)
  connectCompacted(mulDivMatch, mulDivSlots, mulDivQueue.io.enqueue)
  connectCompacted(memoryMatch, memorySlots, memoryQueue.io.enqueue)
}
