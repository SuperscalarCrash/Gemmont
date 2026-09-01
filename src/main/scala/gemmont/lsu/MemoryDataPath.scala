package gemmont.lsu

import chisel3._
import chisel3.util._
import gemmont.isa.LoadStoreOp

class StoreAlignmentInput extends Bundle {
  val address = UInt(32.W)
  val data = UInt(32.W)
  val operation = LoadStoreOp()
  val isLoad = Bool()
}

class StoreAlignmentOutput extends Bundle {
  val byteEnable = UInt(4.W)
  val writeData = UInt(32.W)
  val alignmentException = Bool()
}

class StoreAligner extends Module {
  val io = IO(new Bundle {
    val input = Input(new StoreAlignmentInput)
    val output = Output(new StoreAlignmentOutput)
  })

  io.output := 0.U.asTypeOf(io.output)
  io.output.writeData := io.input.data
  switch(io.input.operation) {
    is(
      LoadStoreOp.Byte,
      LoadStoreOp.ByteUnsigned,
      LoadStoreOp.CacheOperation,
      LoadStoreOp.Preload
    ) {
      io.output.byteEnable := 1.U << io.input.address(1, 0)
      io.output.writeData := io.input.data << (io.input.address(1, 0) << 3)
    }
    is(LoadStoreOp.Half, LoadStoreOp.HalfUnsigned) {
      io.output.alignmentException := io.input.address(0)
      io.output.byteEnable := Mux(io.input.address(1), "b1100".U, "b0011".U)
      io.output.writeData := Mux(io.input.address(1), io.input.data << 16, io.input.data)
    }
    is(LoadStoreOp.Word) {
      io.output.alignmentException := io.input.address(1, 0) =/= 0.U
      io.output.byteEnable := "b1111".U
      io.output.writeData := io.input.data
    }
  }
  when(io.input.isLoad) {
    io.output.writeData := io.input.data
  }
}

class LoadPostprocessInput extends Bundle {
  val readWord = UInt(32.W)
  val byteOffset = UInt(2.W)
  val operation = LoadStoreOp()
}

class LoadPostprocessor extends Module {
  val io = IO(new Bundle {
    val input = Input(new LoadPostprocessInput)
    val result = Output(UInt(32.W))
  })

  val selectedByte = (io.input.readWord >> (io.input.byteOffset << 3))(7, 0)
  val selectedHalf =
    Mux(io.input.byteOffset(1), io.input.readWord(31, 16), io.input.readWord(15, 0))
  io.result := MuxLookup(io.input.operation.asUInt, io.input.readWord)(
    Seq(
      LoadStoreOp.Byte.asUInt -> Cat(Fill(24, selectedByte(7)), selectedByte),
      LoadStoreOp.ByteUnsigned.asUInt -> Cat(0.U(24.W), selectedByte),
      LoadStoreOp.Half.asUInt -> Cat(Fill(16, selectedHalf(15)), selectedHalf),
      LoadStoreOp.HalfUnsigned.asUInt -> Cat(0.U(16.W), selectedHalf),
      LoadStoreOp.Word.asUInt -> io.input.readWord
    )
  )
}
