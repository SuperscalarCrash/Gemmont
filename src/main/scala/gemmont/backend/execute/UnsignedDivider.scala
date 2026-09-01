package gemmont.backend.execute

import chisel3._
import chisel3.util._

class DividerCommand(width: Int) extends Bundle {
  val numerator = UInt(width.W)
  val denominator = UInt(width.W)
}

class DividerResponse(width: Int) extends Bundle {
  val quotient = UInt(width.W)
  val remainder = UInt(width.W)
}

class UnsignedDivider(width: Int) extends Module {
  require(width > 1 && isPow2(width))

  val io = IO(new Bundle {
    val flush = Input(Bool())
    val command = Flipped(Decoupled(new DividerCommand(width)))
    val response = Decoupled(new DividerResponse(width))
  })

  val done = RegInit(true.B)
  val waitResponse = RegInit(false.B)
  val counter = RegInit(0.U(log2Ceil(width).W))
  val numerator = Reg(UInt(width.W))
  val remainder = Reg(UInt(width.W))

  io.command.ready := false.B
  io.response.valid := waitResponse
  io.response.bits.quotient := numerator
  io.response.bits.remainder := remainder

  val canLoad = !waitResponse || io.response.ready
  val remainderShifted = Cat(remainder, numerator(width - 1))
  val remainderMinusDenominator = remainderShifted - Cat(0.U(1.W), io.command.bits.denominator)
  val subtractionSucceeded = !remainderMinusDenominator(width)

  when(io.response.ready) {
    waitResponse := false.B
  }
  when(done) {
    when(canLoad) {
      counter := 0.U
      done := !io.command.valid
      remainder := 0.U
      numerator := io.command.bits.numerator
    }
  }.otherwise {
    counter := counter + 1.U
    remainder := remainderShifted(width - 1, 0)
    numerator := Cat(numerator(width - 2, 0), subtractionSucceeded)
    when(subtractionSucceeded) {
      remainder := remainderMinusDenominator(width - 1, 0)
    }
    when(counter === (width - 1).U) {
      done := true.B
      waitResponse := true.B
    }
  }
  when(io.flush) {
    done := true.B
    waitResponse := false.B
  }
}
