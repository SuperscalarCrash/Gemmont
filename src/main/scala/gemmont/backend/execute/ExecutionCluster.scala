package gemmont.backend.execute

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.DesignParams
import gemmont.backend.PhysicalWrite
import gemmont.backend.issue.{IntegerIssueMicroOp, MulDivIssueMicroOp, TypedIssueSlot}
import gemmont.backend.rob.{RobCompletion, RobState}
import gemmont.debug.ExecutionProfileObservation
import gemmont.isa.{FunctionalUnit, LoongArch, LoadStoreOp}

class IntegerWritebackRecord extends Bundle {
  val slot = new TypedIssueSlot(new IntegerIssueMicroOp)
  val output = new IntegerExecuteOutput
  val timerValue = UInt(64.W)
  val csrReadData = UInt(32.W)
}

class ExecutionCluster extends Module {
  val io = IO(new Bundle {
    val integerIssue = Input(Vec(3, Valid(new TypedIssueSlot(new IntegerIssueMicroOp))))
    val mulDivIssue = Input(Valid(new TypedIssueSlot(new MulDivIssueMicroOp)))
    val integerIssueFire = Output(Bool())
    val mulDivAllowIssue = Output(Bool())
    val mulDivIssueFire = Output(Bool())

    val readAddress = Output(
      Vec(8, UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val readData = Input(Vec(8, UInt(32.W)))
    val bypassWrite = Input(Vec(5, Valid(new PhysicalWrite)))
    val lsuBypassResultBit0ForLane0 = Input(Bool())
    val lsuBypassResultBit1ForLane0 = Input(Bool())
    val lsuBypassResultBit2ForLane1 = Input(Bool())
    val lsuBypassResultBit3ForLane1 = Input(Bool())
    val lsuBypassResultBit7ForLane0 = Input(Bool())
    val csrReadAddress = Output(UInt(14.W))
    val csrReadData = Input(UInt(32.W))
    val timerValue = Input(UInt(64.W))
    val timerId = Input(UInt(32.W))
    val stallRead = Input(Bool())
    val flush = Input(Bool())

    val clearBusy = Output(
      Vec(4, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val write = Output(Vec(4, Valid(new PhysicalWrite)))
    val completion = Output(Vec(4, Valid(new RobCompletion)))
    val profileObservation = Output(new ExecutionProfileObservation)
  })

  val integerRrdValid = RegInit(VecInit(Seq.fill(3)(false.B)))
  val integerRrdSlot = Reg(Vec(3, new TypedIssueSlot(new IntegerIssueMicroOp)))
  val integerExeValid = RegInit(VecInit(Seq.fill(3)(false.B)))
  val integerExeSlot = Reg(Vec(3, new TypedIssueSlot(new IntegerIssueMicroOp)))
  val integerExeSource = Reg(Vec(3, Vec(2, UInt(32.W))))
  val integerWbValid = RegInit(VecInit(Seq.fill(3)(false.B)))
  val integerWb = Reg(Vec(3, new IntegerWritebackRecord))
  val integerExecute = Seq.fill(3)(Module(new IntegerExecute))

  val wb0WriteValidForLane0 = RegInit(false.B)
  val wb1AddressBit4ForLane2Operand1 = Reg(Bool())
  val wb2AddressBit4ForLane2Operand0 = Reg(Bool())
  Seq(
    wb0WriteValidForLane0,
    wb1AddressBit4ForLane2Operand1,
    wb2AddressBit4ForLane2Operand0
  ).foreach { copy =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(copy.toTarget, "dont_touch = \"yes\"")
    })
  }

  io.integerIssueFire := !io.flush && !io.stallRead
  for (lane <- 0 until 3) {
    io.readAddress(2 * lane) := integerRrdSlot(lane).operands(0).physical
    io.readAddress(2 * lane + 1) := integerRrdSlot(lane).operands(1).physical

    io.clearBusy(lane).valid :=
      integerRrdValid(lane) && integerRrdSlot(lane).writeRegister && !io.stallRead
    io.clearBusy(lane).bits := integerRrdSlot(lane).writePhysical

    val bypassedSource = Wire(Vec(2, UInt(32.W)))
    for (operand <- 0 until 2) {
      val matches = VecInit(io.bypassWrite.zipWithIndex.map { case (write, producer) =>
        val writeValid =
          if (lane == 0 && producer == 0)
            wb0WriteValidForLane0
          else write.valid
        val writeAddress =
          if (lane == 2 && operand == 1 && producer == 1)
            Cat(
              integerWb(1).slot.writePhysical(5),
              wb1AddressBit4ForLane2Operand1,
              integerWb(1).slot.writePhysical(3, 0)
            )
          else if (lane == 2 && operand == 0 && producer == 2)
            Cat(
              integerWb(2).slot.writePhysical(5),
              wb2AddressBit4ForLane2Operand0,
              integerWb(2).slot.writePhysical(3, 0)
            )
          else write.bits.address
        writeValid && write.bits.bypass &&
        writeAddress === integerExeSlot(lane).operands(operand).physical
      })
      val bypassData = io.bypassWrite.zipWithIndex.map { case (write, producer) =>
        if (lane == 0 && producer == 4)
          Cat(
            write.bits.data(31, 8),
            io.lsuBypassResultBit7ForLane0,
            write.bits.data(6, 2),
            io.lsuBypassResultBit1ForLane0,
            io.lsuBypassResultBit0ForLane0
          )
        else if (lane == 1 && producer == 4)
          Cat(
            write.bits.data(31, 4),
            io.lsuBypassResultBit3ForLane1,
            io.lsuBypassResultBit2ForLane1,
            write.bits.data(1, 0)
          )
        else write.bits.data
      }
      bypassedSource(operand) := Mux1H(
        Seq(!matches.asUInt.orR -> integerExeSource(lane)(operand)) ++
          bypassData.zip(matches).map { case (data, hit) => hit -> data }
      )
    }

    val payload = integerExeSlot(lane).payload
    val execute = integerExecute(lane)
    execute.io.input.pc := payload.pc
    execute.io.input.instruction := payload.instruction
    execute.io.input.source := bypassedSource
    execute.io.input.useRj := payload.useRj
    execute.io.input.useRk := payload.useRk
    execute.io.input.useRd := payload.useRd
    execute.io.input.immediateExtend := payload.immediateExtend
    execute.io.input.aluOperation := payload.aluOperation
    execute.io.input.compareOperation := payload.compareOperation
    execute.io.input.isBranch := payload.isBranch
    execute.io.input.isJump := payload.isJump
    execute.io.input.isJumpRegister := payload.isJumpRegister
    execute.io.input.predictedTaken := payload.prediction.taken
    execute.io.input.predictedTarget := payload.prediction.target
    execute.io.input.readCsr := payload.readCsr
    execute.io.input.writeCsr := payload.writeCsr
    execute.io.input.csrReadData := io.csrReadData
    execute.io.input.readTimerLow := payload.readTimerLow
    execute.io.input.readTimerHigh := payload.readTimerHigh
    execute.io.input.readTimerId := payload.readTimerId
    execute.io.input.timerValue := io.timerValue
    execute.io.input.timerId := io.timerId
    execute.io.input.tlbOperation := payload.tlbOperation

    io.write(lane).valid := integerWbValid(lane) && integerWb(lane).slot.writeRegister
    io.write(lane).bits.address := integerWb(lane).slot.writePhysical
    io.write(lane).bits.data := integerWb(lane).output.result
    io.write(lane).bits.bypass := true.B

    val completionState = WireDefault(0.U.asTypeOf(new RobState))
    completionState.complete := true.B
    completionState.loadStoreOperation := LoadStoreOp.Byte
    completionState.integerResult := Mux(
      lane.U === 2.U,
      integerWb(lane).output.actualTarget,
      integerWb(lane).output.result
    )
    completionState.mispredict := integerWb(lane).output.mispredict
    completionState.actualTaken := integerWb(lane).output.actualTaken
    completionState.isCounter := integerWb(lane).output.isCounter
    completionState.counterValue := integerWb(lane).timerValue
    completionState.csrStatusRead := integerWb(lane).slot.payload.readCsr &&
      integerWb(lane).slot.payload.instruction(23, 10) === LoongArch.CsrAddress.Estat.U
    completionState.csrReadData := integerWb(lane).csrReadData
    io.completion(lane).valid := integerWbValid(lane)
    io.completion(lane).bits.index := integerWb(lane).slot.robIndex
    io.completion(lane).bits.state := completionState
  }
  io.csrReadAddress := integerExeSlot(2).payload.instruction(23, 10)

  when(integerExeValid(1)) {
    wb1AddressBit4ForLane2Operand1 := integerExeSlot(1).writePhysical(4)
  }
  when(integerExeValid(2)) {
    wb2AddressBit4ForLane2Operand0 := integerExeSlot(2).writePhysical(4)
  }
  when(io.flush) {
    wb0WriteValidForLane0 := false.B
  }.otherwise {
    wb0WriteValidForLane0 := integerExeValid(0) && integerExeSlot(0).writeRegister
  }
  assert(wb0WriteValidForLane0 === (integerWbValid(0) && integerWb(0).slot.writeRegister))

  for (lane <- 0 until 3) {
    when(integerExeValid(lane)) {
      integerWb(lane).slot := integerExeSlot(lane)
      integerWb(lane).output := integerExecute(lane).io.output
      integerWb(lane).timerValue := io.timerValue
      integerWb(lane).csrReadData := io.csrReadData
    }
    when(io.integerIssue(lane).valid && io.integerIssueFire) {
      integerRrdSlot(lane) := io.integerIssue(lane).bits
    }
    when(integerRrdValid(lane) && !io.stallRead) {
      integerExeSlot(lane) := integerRrdSlot(lane)
      integerExeSource(lane)(0) := io.readData(2 * lane)
      integerExeSource(lane)(1) := io.readData(2 * lane + 1)
    }

    when(io.flush) {
      integerRrdValid(lane) := false.B
      integerExeValid(lane) := false.B
      integerWbValid(lane) := false.B
    }.otherwise {
      integerWbValid(lane) := integerExeValid(lane)
      when(io.stallRead) {
        integerExeValid(lane) := false.B
      }.otherwise {
        integerRrdValid(lane) := io.integerIssue(lane).valid && io.integerIssueFire
        integerExeValid(lane) := integerRrdValid(lane)
      }
    }
  }

  val mulDivRrdValid = RegInit(false.B)
  val mulDivRrdSlot = Reg(new TypedIssueSlot(new MulDivIssueMicroOp))
  val mulDiv = Module(new MulDivUnit)
  val dp4 = Module(new Dp4Unit)
  io.readAddress(6) := mulDivRrdSlot.operands(0).physical
  io.readAddress(7) := mulDivRrdSlot.operands(1).physical

  val rrdIsDp4 = mulDivRrdSlot.payload.functionalUnit === FunctionalUnit.Dp4
  val dp4InFlight = RegInit(false.B)

  val commonMduEnable = !io.flush && !io.stallRead && !dp4InFlight
  val mulDivRrdWillDrain = mulDivRrdValid && !rrdIsDp4 &&
    mulDiv.io.request.ready && commonMduEnable
  io.mulDivAllowIssue := commonMduEnable &&
    (!mulDivRrdValid || mulDivRrdWillDrain)
  io.mulDivIssueFire := io.mulDivIssue.valid && io.mulDivAllowIssue

  val mulDivRequestValid = mulDivRrdValid && commonMduEnable && !rrdIsDp4
  val dp4RequestValid = mulDivRrdValid && commonMduEnable && rrdIsDp4 &&
    mulDiv.io.empty

  val mduResponseBits = Mux(dp4InFlight, dp4.io.response.bits, mulDiv.io.response.bits)
  val mduResponseFire = Mux(dp4InFlight, dp4.io.response.fire, mulDiv.io.response.fire)

  when(io.flush) {
    dp4InFlight := false.B
  }.otherwise {
    when(mduResponseFire) {
      dp4InFlight := false.B
    }
    when(dp4.io.request.fire) {
      dp4InFlight := true.B
    }
  }

  mulDiv.io.request.valid := mulDivRequestValid
  mulDiv.io.request.bits.operation := mulDivRrdSlot.payload.functionalUnit
  mulDiv.io.request.bits.signed := mulDivRrdSlot.payload.signed
  mulDiv.io.request.bits.source1 := io.readData(6)
  mulDiv.io.request.bits.source2 := io.readData(7)
  mulDiv.io.request.bits.robIndex := mulDivRrdSlot.robIndex
  mulDiv.io.request.bits.writeRegister := mulDivRrdSlot.writeRegister
  mulDiv.io.request.bits.writePhysical := mulDivRrdSlot.writePhysical
  mulDiv.io.response.ready := true.B
  mulDiv.io.flush := io.flush

  dp4.io.request.valid := dp4RequestValid
  dp4.io.request.bits.operation := mulDivRrdSlot.payload.functionalUnit
  dp4.io.request.bits.signed := mulDivRrdSlot.payload.signed
  dp4.io.request.bits.source1 := io.readData(6)
  dp4.io.request.bits.source2 := io.readData(7)
  dp4.io.request.bits.robIndex := mulDivRrdSlot.robIndex
  dp4.io.request.bits.writeRegister := mulDivRrdSlot.writeRegister
  dp4.io.request.bits.writePhysical := mulDivRrdSlot.writePhysical
  dp4.io.response.ready := true.B
  dp4.io.flush := io.flush

  val mduWakeup = Mux(dp4InFlight, dp4.io.wakeup, mulDiv.io.wakeup)
  io.clearBusy(3).valid := mduWakeup.valid
  io.clearBusy(3).bits := mduWakeup.bits

  io.write(3).valid := mduResponseFire && mduResponseBits.writeRegister
  io.write(3).bits.address := mduResponseBits.writePhysical
  io.write(3).bits.data := mduResponseBits.result
  io.write(3).bits.bypass := false.B
  val mulDivCompletionState = WireDefault(0.U.asTypeOf(new RobState))
  mulDivCompletionState.complete := true.B
  mulDivCompletionState.loadStoreOperation := LoadStoreOp.Byte
  io.completion(3).valid := mduResponseFire && !io.flush
  io.completion(3).bits.index := mduResponseBits.robIndex
  io.completion(3).bits.state := mulDivCompletionState

  when(!io.flush) {
    when(mulDiv.io.request.fire || dp4.io.request.fire) {
      mulDivRrdValid := false.B
    }
    when(io.mulDivIssueFire) {
      mulDivRrdValid := true.B
      mulDivRrdSlot := io.mulDivIssue.bits
    }
  }.otherwise {
    mulDivRrdValid := false.B
  }

  io.profileObservation.integerIssue :=
    VecInit(io.integerIssue.map(_.valid && io.integerIssueFire)).asUInt
  io.profileObservation.integerExeRob := VecInit(integerExeSlot.map(_.robIndex)).asUInt
  io.profileObservation.integerMispredict := VecInit(
    integerExecute.zip(integerExeValid).map { case (execute, valid) =>
      valid && execute.io.output.mispredict
    }
  ).asUInt
  io.profileObservation.mduIssue := io.mulDivIssueFire
}
