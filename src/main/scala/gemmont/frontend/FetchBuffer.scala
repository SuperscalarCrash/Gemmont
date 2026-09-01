package gemmont.frontend

import chisel3._
import chisel3.util._
import gemmont.common.MultiPortFifo
import gemmont.decode.ExceptionPayload

class FetchBufferEntry extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val exception = Valid(new ExceptionPayload)
  val prediction = new PredictionInfo
  val recovery = new PredictionRecovery
  val predictionToken = UInt(H64CorrectorParameters.tokenWidth.W)
  val predictionEpoch = UInt(H64CorrectorParameters.epochWidth.W)
  val predictionWay = UInt(2.W)
  val predictionTokenLast = Bool()

  val h64AlternativeTarget = UInt(32.W)
}

class FetchBuffer extends Module {
  val io = IO(new Bundle {
    val push = Flipped(Vec(4, Decoupled(new FetchBufferEntry)))
    val pop = Vec(3, Decoupled(new FetchBufferEntry))
    val flush = Input(Bool())
    val empty = Output(Bool())
    val full = Output(Bool())
  })

  val fifo = Module(
    new MultiPortFifo(
      new FetchBufferEntry,
      depth = 8,
      pushPorts = 4,
      popPorts = 3,
      localizePushControl = true
    )
  )
  fifo.io.push <> io.push
  io.pop <> fifo.io.pop
  fifo.io.flush := io.flush
  io.empty := fifo.io.empty
  io.full := fifo.io.full
}
