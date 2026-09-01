package gemmont.isa

import chisel3._
import chisel3.util._

object InstructionFields {
  def rd(instruction: UInt): UInt = instruction(4, 0)
  def rj(instruction: UInt): UInt = instruction(9, 5)
  def rk(instruction: UInt): UInt = instruction(14, 10)
  def shiftAmount(instruction: UInt): UInt = instruction(14, 10)
  def signExtendedImmediate12(instruction: UInt): UInt =
    Cat(Fill(20, instruction(21)), instruction(21, 10))
  def zeroExtendedImmediate12(instruction: UInt): UInt = Cat(0.U(20.W), instruction(21, 10))
  def signExtendedImmediate20Shift2(instruction: UInt): UInt =
    Cat(Fill(10, instruction(24)), instruction(24, 5), 0.U(2.W))
  def immediate20Shift12(instruction: UInt): UInt = Cat(instruction(24, 5), 0.U(12.W))
}
