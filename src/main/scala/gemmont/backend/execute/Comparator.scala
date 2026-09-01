package gemmont.backend.execute

import chisel3._
import chisel3.util._
import gemmont.isa.CompareOp

class CompareInput extends Bundle {
  val source1 = UInt(32.W)
  val source2 = UInt(32.W)
  val operation = CompareOp()
}

class Comparator extends Module {
  val io = IO(new Bundle {
    val input = Input(new CompareInput)
    val result = Output(Bool())
  })

  val equal = io.input.source1 === io.input.source2
  val lessSigned = io.input.source1.asSInt < io.input.source2.asSInt
  val lessUnsigned = io.input.source1 < io.input.source2
  val lessZero = io.input.source1(31)
  val equalZero = !io.input.source1.orR

  val lessEqualSigned = lessSigned || equal
  val lessEqualUnsigned = lessUnsigned || equal
  val lessEqualZero = lessZero || equalZero

  io.result := MuxLookup(
    io.input.operation.asUInt,
    false.B
  )(
    Seq(
      CompareOp.Eq.asUInt -> equal,
      CompareOp.Ne.asUInt -> !equal,
      CompareOp.Eqz.asUInt -> equalZero,
      CompareOp.Nez.asUInt -> !equalZero,
      CompareOp.Ge.asUInt -> !lessSigned,
      CompareOp.Lt.asUInt -> lessSigned,
      CompareOp.Le.asUInt -> lessEqualSigned,
      CompareOp.Gt.asUInt -> !lessEqualSigned,
      CompareOp.Geu.asUInt -> !lessUnsigned,
      CompareOp.Ltu.asUInt -> lessUnsigned,
      CompareOp.Leu.asUInt -> lessEqualUnsigned,
      CompareOp.Gtu.asUInt -> !lessEqualUnsigned,
      CompareOp.Gez.asUInt -> !lessZero,
      CompareOp.Ltz.asUInt -> lessZero,
      CompareOp.Lez.asUInt -> lessEqualZero,
      CompareOp.Gtz.asUInt -> !lessEqualZero
    )
  )
}
