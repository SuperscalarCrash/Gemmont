package gemmont.backend.execute

import chisel3._
import chisel3.util._
import gemmont.isa._

class IntegerExecuteInput extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val source = Vec(2, UInt(32.W))
  val useRj = Bool()
  val useRk = Bool()
  val useRd = Bool()
  val immediateExtend = ExtendOp()
  val aluOperation = AluOp()
  val compareOperation = CompareOp()
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val predictedTaken = Bool()
  val predictedTarget = UInt(32.W)
  val readCsr = Bool()
  val writeCsr = Bool()
  val csrReadData = UInt(32.W)
  val readTimerLow = Bool()
  val readTimerHigh = Bool()
  val readTimerId = Bool()
  val timerValue = UInt(64.W)
  val timerId = UInt(32.W)
  val tlbOperation = TlbOperation()
}

class IntegerExecuteOutput extends Bundle {
  val result = UInt(32.W)
  val actualTarget = UInt(32.W)
  val actualTaken = Bool()
  val mispredict = Bool()
  val csrWriteData = UInt(32.W)
  val isCounter = Bool()
}

class IntegerExecute extends Module {
  val io = IO(new Bundle {
    val input = Input(new IntegerExecuteInput)
    val output = Output(new IntegerExecuteOutput)
  })

  val immediate12 = Mux(
    io.input.immediateExtend === ExtendOp.Sign,
    InstructionFields.signExtendedImmediate12(io.input.instruction),
    InstructionFields.zeroExtendedImmediate12(io.input.instruction)
  )

  val alternateSource1 = MuxLookup(io.input.aluOperation.asUInt, "hdeadbeef".U)(
    Seq(
      AluOp.Lu12i.asUInt -> 0.U,
      AluOp.PcAddi.asUInt -> io.input.pc,
      AluOp.PcAddu12i.asUInt -> io.input.pc
    )
  )
  val alternateSource2 = MuxLookup(io.input.aluOperation.asUInt, immediate12)(
    Seq(
      AluOp.Lu12i.asUInt -> InstructionFields.immediate20Shift12(io.input.instruction),
      AluOp.PcAddi.asUInt -> InstructionFields.signExtendedImmediate20Shift2(io.input.instruction),
      AluOp.PcAddu12i.asUInt -> InstructionFields.immediate20Shift12(io.input.instruction)
    )
  )

  val alu = Module(new Alu)
  alu.io.input.source1 := Mux(io.input.useRj, io.input.source(0), alternateSource1)
  alu.io.input.source2 := Mux(io.input.useRk, io.input.source(1), alternateSource2)
  alu.io.input.shiftAmount := Mux(
    io.input.useRk,
    io.input.source(1)(4, 0),
    InstructionFields.shiftAmount(io.input.instruction)
  )
  alu.io.input.operation := io.input.aluOperation

  val comparator = Module(new Comparator)
  comparator.io.input.source1 := io.input.source(0)
  comparator.io.input.source2 := Mux(io.input.useRd, io.input.source(1), immediate12)
  comparator.io.input.operation := io.input.compareOperation

  val branch = Module(new BranchUnit)
  branch.io.input.predictedTaken := io.input.predictedTaken
  branch.io.input.predictedTarget := io.input.predictedTarget
  branch.io.input.isBranch := io.input.isBranch
  branch.io.input.isJump := io.input.isJump
  branch.io.input.isJumpRegister := io.input.isJumpRegister
  branch.io.input.pc := io.input.pc
  branch.io.input.registerTarget := io.input.source(0)
  branch.io.input.instruction := io.input.instruction
  branch.io.input.condition := comparator.io.result

  val csrWriteData = Mux(
    io.input.useRj,
    (io.input.source(0) & io.input.source(1)) |
      (~io.input.source(0) & io.input.csrReadData),
    io.input.source(1)
  )
  val invalidateTlbData = Cat(
    0.U(3.W),
    io.input.source(1)(31, 13),
    io.input.source(0)(9, 0)
  )

  val result = WireDefault(alu.io.result)
  when(io.input.isJump || io.input.isJumpRegister) {
    result := io.input.pc + 4.U
  }
  when(io.input.readCsr) {
    result := io.input.csrReadData
  }
  when(io.input.readTimerId) {
    result := io.input.timerId
  }
  when(io.input.readTimerLow) {
    result := io.input.timerValue(31, 0)
  }
  when(io.input.readTimerHigh) {
    result := io.input.timerValue(63, 32)
  }

  io.output.result := result
  io.output.csrWriteData := csrWriteData
  io.output.actualTarget := Mux(
    io.input.writeCsr,
    csrWriteData,
    Mux(io.input.tlbOperation =/= TlbOperation.None, invalidateTlbData, branch.io.actualTarget)
  )
  io.output.actualTaken := comparator.io.result || io.input.isJump || io.input.isJumpRegister
  io.output.mispredict := branch.io.mispredict
  io.output.isCounter := io.input.readTimerId || io.input.readTimerLow || io.input.readTimerHigh
}
