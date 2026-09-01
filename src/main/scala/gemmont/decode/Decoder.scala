package gemmont.decode

import chisel3._
import chisel3.util._
import gemmont.isa._

class ExceptionPayload extends Bundle {
  val code = UInt(6.W)
  val subcode = UInt(9.W)
  val isTlbRefill = Bool()
}

class DecodeInput extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val predictedBranch = Bool()
  val privilegeLevel = UInt(2.W)
  val incomingException = Valid(new ExceptionPayload)
}

class DecodedMicroOp extends Bundle {
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val functionalUnit = FunctionalUnit()
  val useRj = Bool()
  val useRk = Bool()
  val useRd = Bool()
  val writebackAddress = UInt(5.W)
  val writeRegister = Bool()
  val immediateExtend = ExtendOp()
  val aluOperation = AluOp()
  val loadStoreOperation = LoadStoreOp()
  val isLoad = Bool()
  val isStore = Bool()
  val compareOperation = CompareOp()
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val branchLike = Bool()
  val writeCsr = Bool()
  val readCsr = Bool()
  val readTimerLow = Bool()
  val readTimerHigh = Bool()
  val readTimerId = Bool()
  val tlbOperation = TlbOperation()
  val operateCache = Bool()
  val isWait = Bool()
  val isBarrier = Bool()
  val isLoadLinked = Bool()
  val isStoreConditional = Bool()
  val uniqueRetire = Bool()
  val signed = Bool()
  val exception = Valid(new ExceptionPayload)
  val isErtn = Bool()
  val flushState = Bool()
}

class DecodeControl extends Bundle {
  val functionalUnit = FunctionalUnit()
  val useRj = Bool()
  val useRk = Bool()
  val useRd = Bool()
  val registerWrite = Bool()
  val overrideRdToReturnAddress = Bool()
  val immediateExtend = ExtendOp()
  val aluOperation = AluOp()
  val readCsr = Bool()
  val readTimerHigh = Bool()
  val loadStoreOperation = LoadStoreOp()
  val isLoad = Bool()
  val isStore = Bool()
  val compareOperation = CompareOp()
  val isBranch = Bool()
  val isJump = Bool()
  val isJumpRegister = Bool()
  val signed = Bool()
  val isErtn = Bool()
  val isSyscall = Bool()
  val isBreak = Bool()
  val isBarrier = Bool()
  val tlbOperation = TlbOperation()
  val operateCache = Bool()
  val isWait = Bool()
  val isLoadLinked = Bool()
  val isStoreConditional = Bool()
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val input = Input(new DecodeInput)
    val output = Output(new DecodedMicroOp)
  })

  private def setTwoReadOneWrite(control: DecodeControl, operation: AluOp.Type): Unit = {
    control.useRj := true.B
    control.useRk := true.B
    control.registerWrite := true.B
    control.aluOperation := operation
  }

  private def setOneReadOneWrite(control: DecodeControl, operation: AluOp.Type): Unit = {
    control.useRj := true.B
    control.registerWrite := true.B
    control.aluOperation := operation
  }

  val instruction = io.input.instruction
  val control = Wire(new DecodeControl)
  control := 0.U.asTypeOf(control)
  control.functionalUnit := FunctionalUnit.Alu
  control.immediateExtend := ExtendOp.Sign
  control.aluOperation := AluOp.Add
  control.loadStoreOperation := LoadStoreOp.Byte
  control.compareOperation := CompareOp.Eq
  control.tlbOperation := TlbOperation.None

  val matched = WireDefault(false.B)
  def decode(pattern: InstructionPattern)(actions: => Unit): Unit = {
    when(pattern.matches(instruction)) {
      matched := true.B
      actions
    }
  }

  decode(LoongArch.Add)(setTwoReadOneWrite(control, AluOp.Add))
  decode(LoongArch.Sub)(setTwoReadOneWrite(control, AluOp.Sub))
  decode(LoongArch.Slt)(setTwoReadOneWrite(control, AluOp.Slt))
  decode(LoongArch.Sltu)(setTwoReadOneWrite(control, AluOp.Sltu))
  decode(LoongArch.Nor)(setTwoReadOneWrite(control, AluOp.Nor))
  decode(LoongArch.And)(setTwoReadOneWrite(control, AluOp.And))
  decode(LoongArch.Or)(setTwoReadOneWrite(control, AluOp.Or))
  decode(LoongArch.Xor)(setTwoReadOneWrite(control, AluOp.Xor))
  decode(LoongArch.Sll)(setTwoReadOneWrite(control, AluOp.Sll))
  decode(LoongArch.Srl)(setTwoReadOneWrite(control, AluOp.Srl))
  decode(LoongArch.Sra)(setTwoReadOneWrite(control, AluOp.Sra))

  decode(LoongArch.Slli)(setOneReadOneWrite(control, AluOp.Sll))
  decode(LoongArch.Srli)(setOneReadOneWrite(control, AluOp.Srl))
  decode(LoongArch.Srai)(setOneReadOneWrite(control, AluOp.Sra))
  decode(LoongArch.Slti)(setOneReadOneWrite(control, AluOp.Slt))
  decode(LoongArch.Sltui)(setOneReadOneWrite(control, AluOp.Sltu))
  decode(LoongArch.Addi)(setOneReadOneWrite(control, AluOp.Add))
  decode(LoongArch.Andi) {
    setOneReadOneWrite(control, AluOp.And); control.immediateExtend := ExtendOp.Zero
  }
  decode(LoongArch.Ori) {
    setOneReadOneWrite(control, AluOp.Or); control.immediateExtend := ExtendOp.Zero
  }
  decode(LoongArch.Xori) {
    setOneReadOneWrite(control, AluOp.Xor); control.immediateExtend := ExtendOp.Zero
  }

  decode(LoongArch.Lu12i) {
    control.registerWrite := true.B; control.aluOperation := AluOp.Lu12i
  }
  decode(LoongArch.PcAddi) {
    control.registerWrite := true.B; control.aluOperation := AluOp.PcAddi
  }
  decode(LoongArch.PcAddu12i) {
    control.registerWrite := true.B; control.aluOperation := AluOp.PcAddu12i
  }

  def branch(pattern: InstructionPattern, comparison: CompareOp.Type): Unit = decode(pattern) {
    control.functionalUnit := FunctionalUnit.Compare
    control.isBranch := true.B
    control.useRj := true.B
    control.useRd := true.B
    control.compareOperation := comparison
  }
  branch(LoongArch.Beq, CompareOp.Eq)
  branch(LoongArch.Bne, CompareOp.Ne)
  branch(LoongArch.Blt, CompareOp.Lt)
  branch(LoongArch.Bge, CompareOp.Ge)
  branch(LoongArch.Bltu, CompareOp.Ltu)
  branch(LoongArch.Bgeu, CompareOp.Geu)

  decode(LoongArch.B) {
    control.functionalUnit := FunctionalUnit.Compare; control.isJump := true.B
  }
  decode(LoongArch.Bl) {
    control.functionalUnit := FunctionalUnit.Compare
    control.isJump := true.B
    control.registerWrite := true.B
    control.overrideRdToReturnAddress := true.B
  }
  decode(LoongArch.Jirl) {
    control.functionalUnit := FunctionalUnit.Compare
    control.isJumpRegister := true.B
    control.useRj := true.B
    control.registerWrite := true.B
  }

  def mulDiv(pattern: InstructionPattern, unit: FunctionalUnit.Type, signed: Boolean): Unit =
    decode(pattern) {
      control.useRj := true.B
      control.useRk := true.B
      control.registerWrite := true.B
      control.functionalUnit := unit
      control.signed := signed.B
    }
  mulDiv(LoongArch.Mul, FunctionalUnit.Mul, signed = true)
  mulDiv(LoongArch.Mulh, FunctionalUnit.MulHigh, signed = true)
  mulDiv(LoongArch.Mulhu, FunctionalUnit.MulHigh, signed = false)
  decode(LoongArch.Dp4) {
    control.useRj := true.B
    control.useRk := true.B
    control.registerWrite := true.B
    control.functionalUnit := FunctionalUnit.Dp4
    control.signed := true.B
  }
  mulDiv(LoongArch.Div, FunctionalUnit.Div, signed = true)
  mulDiv(LoongArch.Divu, FunctionalUnit.Div, signed = false)
  mulDiv(LoongArch.Mod, FunctionalUnit.Mod, signed = true)
  mulDiv(LoongArch.Modu, FunctionalUnit.Mod, signed = false)

  def load(pattern: InstructionPattern, operation: LoadStoreOp.Type): Unit = decode(pattern) {
    control.functionalUnit := FunctionalUnit.LoadStore
    control.useRj := true.B
    control.registerWrite := true.B
    control.isLoad := true.B
    control.loadStoreOperation := operation
  }
  load(LoongArch.LoadWord, LoadStoreOp.Word)
  load(LoongArch.LoadHalf, LoadStoreOp.Half)
  load(LoongArch.LoadHalfUnsigned, LoadStoreOp.HalfUnsigned)
  load(LoongArch.LoadByte, LoadStoreOp.Byte)
  load(LoongArch.LoadByteUnsigned, LoadStoreOp.ByteUnsigned)

  def store(pattern: InstructionPattern, operation: LoadStoreOp.Type): Unit = decode(pattern) {
    control.functionalUnit := FunctionalUnit.LoadStore
    control.useRj := true.B
    control.useRd := true.B
    control.isStore := true.B
    control.loadStoreOperation := operation
  }
  store(LoongArch.StoreWord, LoadStoreOp.Word)
  store(LoongArch.StoreHalf, LoadStoreOp.Half)
  store(LoongArch.StoreByte, LoadStoreOp.Byte)

  decode(LoongArch.LoadLinked) {
    control.functionalUnit := FunctionalUnit.LoadStore
    control.useRj := true.B
    control.registerWrite := true.B
    control.isLoad := true.B
    control.loadStoreOperation := LoadStoreOp.Word
    control.isLoadLinked := true.B
  }
  decode(LoongArch.StoreConditional) {
    control.functionalUnit := FunctionalUnit.LoadStore
    control.useRj := true.B
    control.useRd := true.B
    control.registerWrite := true.B
    control.isStore := true.B
    control.loadStoreOperation := LoadStoreOp.Word
    control.isStoreConditional := true.B
  }

  decode(LoongArch.Csr) {
    control.functionalUnit := FunctionalUnit.Csr
    control.registerWrite := true.B
    control.readCsr := true.B
  }
  decode(LoongArch.ReadCounterLow) {
    control.functionalUnit := FunctionalUnit.Timer; control.registerWrite := true.B
  }
  decode(LoongArch.ReadCounterHigh) {
    control.functionalUnit := FunctionalUnit.Timer
    control.registerWrite := true.B
    control.readTimerHigh := true.B
  }
  decode(LoongArch.CpuConfig)(setOneReadOneWrite(control, AluOp.CpuConfig))

  decode(LoongArch.Syscall) {
    control.functionalUnit := FunctionalUnit.None; control.isSyscall := true.B
  }
  decode(LoongArch.Break) {
    control.functionalUnit := FunctionalUnit.None; control.isBreak := true.B
  }
  decode(LoongArch.Ertn) {
    control.functionalUnit := FunctionalUnit.None; control.isErtn := true.B
  }
  decode(LoongArch.Idle) {
    control.functionalUnit := FunctionalUnit.None; control.isWait := true.B
  }

  decode(LoongArch.TlbSearch) {
    control.functionalUnit := FunctionalUnit.None; control.tlbOperation := TlbOperation.Search
  }
  decode(LoongArch.TlbRead) {
    control.functionalUnit := FunctionalUnit.None; control.tlbOperation := TlbOperation.Read
  }
  decode(LoongArch.TlbWrite) {
    control.functionalUnit := FunctionalUnit.None; control.tlbOperation := TlbOperation.Write
  }
  decode(LoongArch.TlbFill) {
    control.functionalUnit := FunctionalUnit.None; control.tlbOperation := TlbOperation.Fill
  }
  decode(LoongArch.InvalidateTlb) {
    control.functionalUnit := FunctionalUnit.InvTlb
    control.useRj := true.B
    control.useRk := true.B
    control.tlbOperation := TlbOperation.Invalidate1
  }

  decode(LoongArch.InstBarrier) {
    control.functionalUnit := FunctionalUnit.LoadStore; control.isBarrier := true.B
  }
  decode(LoongArch.DataBarrier) {
    control.functionalUnit := FunctionalUnit.LoadStore; control.isBarrier := true.B
  }
  decode(LoongArch.CacheOp) {
    control.functionalUnit := FunctionalUnit.LoadStore
    control.operateCache := true.B
    control.useRj := true.B
    control.loadStoreOperation := LoadStoreOp.CacheOperation
  }
  decode(LoongArch.Preload) {
    control.useRj := true.B
  }

  io.output := 0.U.asTypeOf(io.output)
  io.output.pc := io.input.pc
  io.output.instruction := instruction
  io.output.functionalUnit := control.functionalUnit
  io.output.useRj := control.useRj
  io.output.useRk := control.useRk
  io.output.useRd := control.useRd
  io.output.immediateExtend := control.immediateExtend
  io.output.aluOperation := control.aluOperation
  io.output.loadStoreOperation := control.loadStoreOperation
  io.output.isLoad := control.isLoad
  io.output.isStore := control.isStore
  io.output.compareOperation := control.compareOperation
  io.output.isBranch := control.isBranch
  io.output.isJump := control.isJump
  io.output.isJumpRegister := control.isJumpRegister
  io.output.signed := control.signed
  io.output.readCsr := control.readCsr
  io.output.readTimerHigh := control.readTimerHigh
  io.output.tlbOperation := control.tlbOperation
  io.output.operateCache := control.operateCache
  io.output.isWait := control.isWait
  io.output.isBarrier := control.isBarrier
  io.output.isLoadLinked := control.isLoadLinked
  io.output.isStoreConditional := control.isStoreConditional
  io.output.isErtn := control.isErtn

  val rd = InstructionFields.rd(instruction)
  val rj = InstructionFields.rj(instruction)
  val writeCsr = control.readCsr && rj =/= 0.U
  io.output.writeCsr := writeCsr
  when(writeCsr) {
    io.output.useRd := true.B
  }
  when(writeCsr && rj =/= 1.U) {
    io.output.useRj := true.B
  }

  val readTimerLow = control.functionalUnit === FunctionalUnit.Timer && !control.readTimerHigh &&
    rd =/= 0.U
  val readTimerId = control.functionalUnit === FunctionalUnit.Timer && !control.readTimerHigh &&
    rd === 0.U
  io.output.readTimerLow := readTimerLow
  io.output.readTimerId := readTimerId

  io.output.writebackAddress := Mux(
    control.overrideRdToReturnAddress,
    1.U,
    Mux(readTimerId, rj, rd)
  )
  io.output.writeRegister := control.registerWrite && io.output.writebackAddress =/= 0.U

  io.output.exception := io.input.incomingException
  when(!io.input.incomingException.valid) {
    io.output.exception.valid := false.B
    io.output.exception.bits := 0.U.asTypeOf(io.output.exception.bits)
    when(control.isSyscall) {
      io.output.exception.valid := true.B
      io.output.exception.bits.code := LoongArch.ExceptionCode.SystemCall.code.U
      io.output.exception.bits.subcode := LoongArch.ExceptionCode.SystemCall.subcode.U
    }
    when(control.isBreak) {
      io.output.exception.valid := true.B
      io.output.exception.bits.code := LoongArch.ExceptionCode.Breakpoint.code.U
      io.output.exception.bits.subcode := LoongArch.ExceptionCode.Breakpoint.subcode.U
    }
    when(!matched) {
      io.output.exception.valid := true.B
      io.output.exception.bits.code := LoongArch.ExceptionCode.IllegalInstruction.code.U
      io.output.exception.bits.subcode := LoongArch.ExceptionCode.IllegalInstruction.subcode.U
    }

    when(LoongArch.InvalidateTlb.matches(instruction) && control.useRj && control.useRk) {
      switch(instruction(4, 0)) {
        is(0.U, 1.U) { io.output.tlbOperation := TlbOperation.Invalidate1 }
        is(2.U) { io.output.tlbOperation := TlbOperation.Invalidate2 }
        is(3.U) { io.output.tlbOperation := TlbOperation.Invalidate3 }
        is(4.U) { io.output.tlbOperation := TlbOperation.Invalidate4 }
        is(5.U) { io.output.tlbOperation := TlbOperation.Invalidate5 }
        is(6.U) { io.output.tlbOperation := TlbOperation.Invalidate6 }
      }
      when(instruction(4, 0) > 6.U) {
        io.output.exception.valid := true.B
        io.output.exception.bits.code := LoongArch.ExceptionCode.IllegalInstruction.code.U
        io.output.exception.bits.subcode := LoongArch.ExceptionCode.IllegalInstruction.subcode.U
      }
    }

    val privilegedCacheOperation = LoongArch.CacheOp.matches(instruction) &&
      instruction(4, 3) =/= "b10".U
    val privilegedInstruction = LoongArch.Csr.matches(instruction) ||
      privilegedCacheOperation || LoongArch.Ertn.matches(instruction) ||
      LoongArch.Idle.matches(instruction) || LoongArch.TlbRead.matches(instruction) ||
      LoongArch.TlbWrite.matches(instruction) || LoongArch.TlbSearch.matches(instruction) ||
      LoongArch.TlbFill.matches(instruction) || LoongArch.InvalidateTlb.matches(instruction)
    when(io.input.privilegeLevel =/= 0.U && privilegedInstruction) {
      io.output.exception.valid := true.B
      io.output.exception.bits.code := LoongArch.ExceptionCode.PrivilegeInstruction.code.U
      io.output.exception.bits.subcode := LoongArch.ExceptionCode.PrivilegeInstruction.subcode.U
    }
  }

  io.output.branchLike := control.isBranch || control.isJump || control.isJumpRegister
  val operatesTlb = io.output.tlbOperation =/= TlbOperation.None
  io.output.flushState := control.isErtn || writeCsr || control.isWait || operatesTlb ||
    control.operateCache || control.isBarrier || control.isLoadLinked || control.isStoreConditional
  io.output.uniqueRetire := io.output.flushState || io.output.branchLike || control.isLoad ||
    control.isStore || io.input.predictedBranch
}
