package gemmont.backend.execute

import chisel3._
import chisel3.util._
import gemmont.DesignParams

class Dp4Unit extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new MulDivRequest))
    val response = Decoupled(new MulDivResponse)
    val flush = Input(Bool())
    val wakeup = Output(
      Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
  })

  val exeValid = RegInit(false.B)
  val exeRequest = Reg(new MulDivRequest)

  io.request.ready := !exeValid

  val wbValid = RegInit(false.B)
  val wbPayload = Reg(new MulDivResponse)
  val wbCanAccept = !wbValid || io.response.ready
  when(io.request.fire) {
    exeValid := true.B
    exeRequest := io.request.bits
  }

  when(wbCanAccept) {
    wbValid := exeValid
    when(exeValid) {
      val a0 = exeRequest.source1(7, 0).asSInt
      val a1 = exeRequest.source1(15, 8).asSInt
      val a2 = exeRequest.source1(23, 16).asSInt
      val a3 = exeRequest.source1(31, 24).asSInt
      val b0 = exeRequest.source2(7, 0).asSInt
      val b1 = exeRequest.source2(15, 8).asSInt
      val b2 = exeRequest.source2(23, 16).asSInt
      val b3 = exeRequest.source2(31, 24).asSInt
      val pair0 = a0 * b0 +& a1 * b1
      val pair1 = a2 * b2 +& a3 * b3
      val sum = Wire(SInt(18.W))
      sum := pair0 +& pair1
      wbPayload.result := sum.pad(32).asUInt
      wbPayload.robIndex := exeRequest.robIndex
      wbPayload.writeRegister := exeRequest.writeRegister
      wbPayload.writePhysical := exeRequest.writePhysical
    }
  }

  when(wbCanAccept && !io.request.fire) {
    exeValid := false.B
  }

  io.response.valid := wbValid
  io.response.bits := wbPayload

  io.wakeup.valid := wbValid && wbPayload.writeRegister
  io.wakeup.bits := wbPayload.writePhysical

  when(io.flush) {
    exeValid := false.B
    wbValid := false.B
  }
}
