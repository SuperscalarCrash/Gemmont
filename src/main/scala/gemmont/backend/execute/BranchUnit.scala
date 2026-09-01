package gemmont.backend.execute

import chisel3._
import chisel3.util._

class BranchUnitInput extends Bundle {
  val predictedTaken = Bool()
  val predictedTarget = UInt(32.W)
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val pc = UInt(32.W)
  val registerTarget = UInt(32.W)
  val instruction = UInt(32.W)
  val condition = Bool()
}

class BranchUnit extends Module {
  val io = IO(new Bundle {
    val input = Input(new BranchUnitInput)
    val actualTarget = Output(UInt(32.W))
    val mispredict = Output(Bool())
  })

  private def signExtend(value: UInt, width: Int): UInt =
    Cat(Fill(32 - width, value(width - 1)), value)

  val branchOffset = signExtend(Cat(io.input.instruction(25, 10), 0.U(2.W)), 18)
  val jumpOffset = signExtend(
    Cat(io.input.instruction(9, 0), io.input.instruction(25, 10), 0.U(2.W)),
    28
  )
  val registerOffset = signExtend(Cat(io.input.instruction(25, 10), 0.U(2.W)), 18)

  io.actualTarget := Mux(
    io.input.isBranch,
    io.input.pc + branchOffset,
    Mux(io.input.isJump, io.input.pc + jumpOffset, io.input.registerTarget + registerOffset)
  )

  val controlTransfer = io.input.isBranch || io.input.isJump || io.input.isJumpRegister
  val actualTaken = io.input.condition || io.input.isJump || io.input.isJumpRegister

  io.mispredict := Mux(
    controlTransfer,
    (io.input.predictedTaken ^ actualTaken) ||
      (actualTaken && io.actualTarget =/= io.input.predictedTarget),
    io.input.predictedTaken
  )
}
