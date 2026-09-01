package gemmont.backend.rob

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.isa.{CacheOperation, CacheSelect, LoongArch, TlbOperation}

class CommitUnit extends Module {
  val io = IO(new Bundle {
    val entries = Input(Vec(3, Valid(new RobEntry)))
    val interruptPending = Input(Bool())
    val inhibitInterrupt = Input(Bool())
    val uncachedLoadCompleted = Input(Bool())
    val output = Output(new CommitOutputs)
  })

  val output = WireDefault(0.U.asTypeOf(new CommitOutputs))
  io.output := output
  output.tlbOperation := TlbOperation.None

  val entry0 = io.entries(0).bits
  val microOp0 = entry0.info.microOp
  val uncachedIdle :: uncachedExecute :: Nil = Enum(2)
  val uncachedState = RegInit(uncachedIdle)

  val interruptInhibited = uncachedState === uncachedExecute || io.inhibitInterrupt
  val hasException = entry0.state.exception.valid ||
    (io.interruptPending && !interruptInhibited)
  val linearRecovery = microOp0.flushState || (!microOp0.branchLike && entry0.state.mispredict)

  val completeMask = Wire(Vec(3, Bool()))
  for (lane <- 0 until 3) {
    completeMask(lane) := io.entries.take(lane + 1).map(_.bits.state.complete).reduce(_ && _)
  }
  val exceptionMask = WireInit(VecInit(Seq.fill(3)(true.B)))
  val uniqueMask = WireInit(VecInit(Seq.fill(3)(true.B)))
  val recoveryMask = WireInit(VecInit(Seq.fill(3)(true.B)))
  for (lane <- 1 until 3) {
    exceptionMask(lane) := !io.entries
      .take(lane + 1)
      .map(entry => entry.bits.state.exception.valid || linearRecovery)
      .reduce(_ || _)
    uniqueMask(lane) := !io.entries
      .slice(1, lane + 1)
      .map(_.bits.info.microOp.uniqueRetire)
      .reduce(_ || _)

    recoveryMask(lane) := !io.entries
      .take(lane + 1)
      .map(entry => entry.valid && entry.bits.state.mispredict)
      .reduce(_ || _)
  }

  val uncachedMask = WireDefault("b111".U(3.W))
  val isUncachedLoad = microOp0.isLoad && entry0.state.lsuUncached
  when(uncachedState === uncachedIdle) {
    when(io.entries(0).valid && isUncachedLoad && entry0.state.complete && !hasException) {
      uncachedMask := 0.U
      output.commitStore := true.B
      uncachedState := uncachedExecute
    }
  }.otherwise {
    uncachedMask := 0.U
    when(io.uncachedLoadCompleted) {
      uncachedMask := "b111".U
      uncachedState := uncachedIdle
    }
  }

  val readyMask = completeMask.asUInt & exceptionMask.asUInt & uniqueMask.asUInt &
    recoveryMask.asUInt & uncachedMask
  output.uncachedMask := uncachedMask
  val retireMask = Wire(Vec(3, Bool()))
  for (lane <- 0 until 3) retireMask(lane) := io.entries(lane).valid && readyMask(lane)
  output.retireMask := retireMask.asUInt

  val port0Fire = retireMask(0)
  val recoverState = port0Fire && (hasException || entry0.state.mispredict || linearRecovery)
  output.flush := recoverState
  val registerFlush = RegNext(recoverState, false.B)
  output.registerFlush := registerFlush
  output.recoverPrf := registerFlush
  output.hasException := hasException
  output.exception.valid := port0Fire && hasException
  output.exception.bits := entry0.state.exception.bits
  when(io.interruptPending && !interruptInhibited) {

    output.exception.bits.code := LoongArch.ExceptionCode.Interrupt.code.U
    output.exception.bits.subcode := LoongArch.ExceptionCode.Interrupt.subcode.U
    output.exception.bits.badAddress := 0.U
    output.exception.bits.isTlbRefill := false.B
  }
  when(entry0.info.frontendException) {
    output.exception.bits.badAddress := microOp0.pc
  }
  output.exceptionPc := microOp0.pc

  for (lane <- 0 until 3) {
    val microOp = io.entries(lane).bits.info.microOp
    val rename = io.entries(lane).bits.info.rename
    output.architecturalCommits(lane).valid := retireMask(lane) && !hasException &&
      microOp.writeRegister
    output.architecturalCommits(lane).bits.architectural := microOp.writebackAddress
    output.architecturalCommits(lane).bits.previousPhysical := rename.previousPhysical
    output.architecturalCommits(lane).bits.physical := rename.physical
  }

  when(port0Fire && !hasException) {
    output.enterWait := microOp0.isWait
    output.tlbOperation := microOp0.tlbOperation
    output.tlbInvalidateAsid := entry0.state.integerResult(DesignParams.asidWidth - 1, 0)
    output.tlbInvalidateVppn := entry0.state.integerResult(28, 10)
    output.csrWrite.valid := microOp0.writeCsr
    output.csrWrite.bits.address := microOp0.instruction(23, 10)
    output.csrWrite.bits.data := entry0.state.integerResult
    output.cacheMaintenance.valid := microOp0.operateCache
    output.cacheMaintenance.bits.address := entry0.state.exception.bits.badAddress
    output.cacheMaintenance.bits.operation := CacheOperation
      .safe(entry0.state.integerResult(1, 0))
      ._1
    output.cacheMaintenance.bits.select := CacheSelect.safe(entry0.state.integerResult(3, 2))._1
    output.ertn := microOp0.isErtn
    output.commitStore := microOp0.isStore
    output.setLoadLinked := microOp0.isLoadLinked
    val storeConditionalSuccess =
      microOp0.isStoreConditional && entry0.state.integerResult(0)
    output.clearLoadLinked := storeConditionalSuccess
    when(microOp0.isStoreConditional && !storeConditionalSuccess) {
      output.commitStore := false.B
    }

    output.predictorUpdate.valid := microOp0.branchLike || microOp0.prediction.predictsBranch
    output.predictorUpdate.bits.prediction := microOp0.prediction
    output.predictorUpdate.bits.recovery := microOp0.predictionRecovery
    output.predictorUpdate.bits.branchLike := microOp0.branchLike
    output.predictorUpdate.bits.conditionalBranch := microOp0.isBranch
    output.predictorUpdate.bits.taken :=
      (entry0.state.mispredict ^ microOp0.prediction.taken) || microOp0.isJump ||
        microOp0.isJumpRegister

    output.predictorUpdate.bits.staticTarget := microOp0.isJump
    output.predictorUpdate.bits.isReturn := microOp0.instruction === "h4c000020".U
    output.predictorUpdate.bits.isCall :=
      (microOp0.isJumpRegister && microOp0.instruction(4, 0) === 1.U) ||
        (microOp0.isJump && microOp0.instruction(26))
    output.predictorUpdate.bits.mispredict := entry0.state.mispredict
    output.predictorUpdate.bits.pc := microOp0.pc
    output.predictorUpdate.bits.target := entry0.state.integerResult

    val branchTarget = Mux(entry0.state.actualTaken, entry0.state.integerResult, microOp0.pc + 4.U)
    when(linearRecovery && !microOp0.isErtn) {
      output.backendRedirect.valid := true.B
      output.backendRedirect.bits := microOp0.pc + 4.U
    }
    when(entry0.state.mispredict && microOp0.branchLike) {
      output.backendRedirect.valid := true.B
      output.backendRedirect.bits := Mux(
        microOp0.isJump || microOp0.isJumpRegister,
        entry0.state.integerResult,
        branchTarget
      )
    }
  }
}
