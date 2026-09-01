package gemmont.backend.rob

import chisel3._
import chisel3.util._
import gemmont.DesignParams

class ReorderBuffer extends Module {
  private val depth = DesignParams.robDepth
  private val width = DesignParams.retireWidth

  val io = IO(new Bundle {
    val allocate = Flipped(Vec(width, Decoupled(new RobAllocate)))
    val allocatedIndex = Output(Vec(width, UInt(5.W)))
    val retire = Vec(width, Decoupled(new RobEntry))
    val complete = Input(Vec(5, Valid(new RobCompletion)))
    val flush = Input(Bool())
    val occupancy = Output(UInt(6.W))
    val popPointer = Output(UInt(5.W))
  })

  val info = Reg(Vec(depth, new RobInfo))
  val state = Reg(Vec(depth, new RobState))
  val pushPointer = RegInit(0.U(5.W))
  val popPointer = RegInit(0.U(5.W))

  val prefetchPointer = RegInit(0.U(5.W))
  dontTouch(prefetchPointer)
  val risingOccupancy = RegInit(false.B)

  val retireValid = RegInit(VecInit(Seq.fill(width)(false.B)))
  val prefetched = Reg(Vec(width, new RobEntry))

  val empty = pushPointer === popPointer && !risingOccupancy
  val full = pushPointer === popPointer && risingOccupancy
  val maximumPush = popPointer - pushPointer
  io.popPointer := popPointer
  io.occupancy := Mux(full, depth.U, (pushPointer - popPointer))

  for (lane <- 0 until width) {
    io.allocate(lane).ready := !full && (empty || lane.U < maximumPush)
    io.allocatedIndex(lane) := pushPointer + lane.U
    io.retire(lane).valid := retireValid(lane)
    io.retire(lane).bits := prefetched(lane)
  }

  val pushFires = VecInit(io.allocate.map(_.fire))
  val popFires = VecInit(io.retire.map(_.fire))
  val pushCount = PriorityMux(
    (0 until width).map(lane => !pushFires(lane) -> lane.U(2.W)) :+ (true.B -> width.U(2.W))
  )
  val popCount = PriorityMux(
    (0 until width).map(lane => !popFires(lane) -> lane.U(2.W)) :+ (true.B -> width.U(2.W))
  )

  for (lane <- 0 until width) {
    when(io.allocate(lane).ready) {
      info(pushPointer + lane.U) := io.allocate(lane).bits.info
      state(pushPointer + lane.U) := io.allocate(lane).bits.initialState
    }
  }
  for (port <- 0 until 5) {
    when(io.complete(port).valid) {
      val address = io.complete(port).bits.index
      state(address) := io.complete(port).bits.state

      state(address).mispredict :=
        state(address).mispredict || io.complete(port).bits.state.mispredict
    }
  }

  val nextPushPointer = pushPointer + pushCount
  val nextPopPointer = popPointer + popCount
  val nextPrefetchPointer = prefetchPointer + popCount
  val nextRisingOccupancy =
    Mux(pushCount === popCount, risingOccupancy, pushCount > popCount)
  val nextEmpty = nextPushPointer === nextPopPointer && !nextRisingOccupancy
  val nextFull = nextPushPointer === nextPopPointer && nextRisingOccupancy
  val nextMaximumPop = nextPushPointer - nextPopPointer
  for (lane <- 0 until width) {
    val address = nextPrefetchPointer + lane.U
    val nextInfo = WireDefault(info(address))
    val nextState = WireDefault(state(address))
    for (allocate <- 0 until width) {

      when(io.allocate(allocate).ready && pushPointer + allocate.U === address) {
        nextInfo := io.allocate(allocate).bits.info
        nextState := io.allocate(allocate).bits.initialState
      }
    }
    for (complete <- 0 until 5) {
      when(io.complete(complete).valid && io.complete(complete).bits.index === address) {
        nextState := io.complete(complete).bits.state
        nextState.mispredict :=
          state(address).mispredict || io.complete(complete).bits.state.mispredict
      }
    }
    prefetched(lane).info := nextInfo
    prefetched(lane).state := nextState
  }

  pushPointer := nextPushPointer
  popPointer := nextPopPointer
  prefetchPointer := nextPrefetchPointer
  when(pushCount =/= popCount) {
    risingOccupancy := pushCount > popCount
  }
  for (lane <- 0 until width) {
    retireValid(lane) := !nextEmpty && (nextFull || lane.U < nextMaximumPop)
  }

  when(io.flush) {
    pushPointer := 0.U
    popPointer := 0.U
    prefetchPointer := 0.U
    risingOccupancy := false.B
    for (lane <- 0 until width) retireValid(lane) := false.B
  }

  for (lane <- 1 until width) {
    when(io.allocate(lane).valid) { assert(io.allocate(lane - 1).valid) }
    when(io.retire(lane).ready) { assert(io.retire(lane - 1).ready) }
  }
}
