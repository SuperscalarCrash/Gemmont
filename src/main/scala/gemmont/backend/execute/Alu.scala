package gemmont.backend.execute

import chisel3._
import chisel3.util._
import gemmont.isa.AluOp

class AluInput extends Bundle {
  val source1 = UInt(32.W)
  val source2 = UInt(32.W)
  val shiftAmount = UInt(5.W)
  val operation = AluOp()
}

class Alu extends Module {
  val io = IO(new Bundle {
    val input = Input(new AluInput)
    val result = Output(UInt(32.W))
  })

  io.result := MuxLookup(
    io.input.operation.asUInt,
    0.U
  )(
    Seq(
      AluOp.Add.asUInt -> (io.input.source1 + io.input.source2),
      AluOp.CpuConfig.asUInt -> 0.U(32.W),
      AluOp.Sub.asUInt -> (io.input.source1 - io.input.source2),
      AluOp.And.asUInt -> (io.input.source1 & io.input.source2),
      AluOp.Or.asUInt -> (io.input.source1 | io.input.source2),
      AluOp.Xor.asUInt -> (io.input.source1 ^ io.input.source2),
      AluOp.Nor.asUInt -> ~(io.input.source1 | io.input.source2),
      AluOp.Slt.asUInt -> (io.input.source1.asSInt < io.input.source2.asSInt),
      AluOp.Sltu.asUInt -> (io.input.source1 < io.input.source2),
      AluOp.Sll.asUInt -> (io.input.source1 << io.input.shiftAmount),
      AluOp.Srl.asUInt -> (io.input.source1 >> io.input.shiftAmount),
      AluOp.Sra.asUInt -> (io.input.source1.asSInt >> io.input.shiftAmount).asUInt,
      AluOp.Lu12i.asUInt -> (io.input.source1 + io.input.source2),
      AluOp.PcAddi.asUInt -> (io.input.source1 + io.input.source2),
      AluOp.PcAddu12i.asUInt -> (io.input.source1 + io.input.source2)
    )
  )
}
