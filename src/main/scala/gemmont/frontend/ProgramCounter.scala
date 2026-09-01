package gemmont.frontend

import chisel3._
import chisel3.util.Valid

class ProgramCounter(resetPc: BigInt = BigInt("1c000000", 16)) extends Module {
  val io = IO(new Bundle {
    val advance = Input(Bool())
    val prediction = Flipped(Valid(UInt(32.W)))

    val predictionReadSet = Flipped(Valid(UInt(6.W)))
    val backendRedirect = Flipped(Valid(UInt(32.W)))
    val pc = Output(UInt(32.W))
    val nextPc = Output(UInt(32.W))
    val nextReadSet = Output(UInt(6.W))
    val nextNonLivePcFragment = Output(UInt(16.W))
  })

  val pc = RegInit(resetPc.U(32.W))
  val hasLaunched = RegInit(false.B)
  val pendingRedirectValid = RegInit(false.B)
  val pendingRedirectTarget = Reg(UInt(32.W))

  val wordOffset = pc(3, 2)
  val wordsUntilGroupEnd = 4.U - wordOffset
  val sequential = pc + (wordsUntilGroupEnd << 2)

  val liveRedirectValid = io.backendRedirect.valid || io.prediction.valid
  val liveRedirectTarget = Mux(
    io.backendRedirect.valid,
    io.backendRedirect.bits,
    io.prediction.bits
  )
  val sequentialCandidate = Mux(hasLaunched, sequential, resetPc.U(32.W))
  val retainedCandidate =
    Mux(pendingRedirectValid, pendingRedirectTarget, sequentialCandidate)
  val selected =
    Mux(liveRedirectValid, liveRedirectTarget, retainedCandidate)
  val liveReadSetValid = io.backendRedirect.valid || io.predictionReadSet.valid
  val liveReadSet = Mux(
    io.backendRedirect.valid,
    io.backendRedirect.bits(11, 6),
    io.predictionReadSet.bits
  )

  io.pc := pc
  io.nextPc := selected
  io.nextReadSet := Mux(liveReadSetValid, liveReadSet, retainedCandidate(11, 6))
  io.nextNonLivePcFragment := Mux(
    io.backendRedirect.valid,
    io.backendRedirect.bits(17, 2),
    retainedCandidate(17, 2)
  )
  when(io.advance) {
    pc := selected
    hasLaunched := true.B
    pendingRedirectValid := false.B
  }.elsewhen(liveRedirectValid) {
    pendingRedirectTarget := liveRedirectTarget
    pendingRedirectValid := true.B
  }
}
