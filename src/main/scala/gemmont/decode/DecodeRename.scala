package gemmont.decode

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.backend.{RenameRecord, RenameTable}
import gemmont.backend.rob.{RobAllocate, RobMicroOp}
import gemmont.debug.DecodeProfileObservation
import gemmont.frontend.{FetchBufferEntry, PredictionInfo, PredictionRecovery}
import gemmont.isa.{InstructionFields, LoadStoreOp}

class DecodedEntry extends Bundle {
  val microOp = new DecodedMicroOp
  val prediction = new PredictionInfo
  val recovery = new PredictionRecovery
}

class RenamedEntry extends Bundle {
  val decoded = new DecodedEntry
  val rename = new RenameRecord
  val robIndex = UInt(5.W)
}

class DecodePacket extends Bundle {
  val entries = Vec(3, Valid(new DecodedEntry))
}

class RenamePacket extends Bundle {
  val entries = Vec(3, Valid(new RenamedEntry))
}

class DecodeRename extends Module {
  val io = IO(new Bundle {
    val fetch = Flipped(Vec(3, Decoupled(new FetchBufferEntry)))
    val dispatch = Decoupled(new RenamePacket)
    val robAllocate = Vec(3, Decoupled(new RobAllocate))
    val robAllocatedIndex = Input(Vec(3, UInt(5.W)))
    val commits = Input(Vec(3, Valid(new gemmont.backend.ArchitecturalCommit)))
    val recoverRename = Input(Bool())
    val flush = Input(Bool())
    val privilegeLevel = Input(UInt(2.W))
    val physicalAllocations = Output(
      Vec(3, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val architecturalRat = Output(
      Vec(
        DesignParams.architecturalRegisterCount - 1,
        UInt(DesignParams.physicalRegisterAddressWidth.W)
      )
    )
    val profileObservation = Output(new DecodeProfileObservation)
  })

  val decoders = Seq.fill(3)(Module(new Decoder))
  val renameTable = Module(new RenameTable)
  renameTable.io.commits := io.commits
  renameTable.io.recover := io.recoverRename
  io.physicalAllocations := renameTable.io.allocated
  io.architecturalRat := renameTable.io.architecturalRat

  val renameValid = RegInit(false.B)
  val renamePacket = Reg(new DecodePacket)
  val dispatchValid = RegInit(false.B)
  val dispatchPacket = Reg(new RenamePacket)

  for (lane <- 0 until 3) {
    decoders(lane).io.input.pc := io.fetch(lane).bits.pc
    decoders(lane).io.input.instruction := io.fetch(lane).bits.instruction
    decoders(lane).io.input.predictedBranch := io.fetch(lane).bits.prediction.predictsBranch
    decoders(lane).io.input.privilegeLevel := io.privilegeLevel
    decoders(lane).io.input.incomingException := io.fetch(lane).bits.exception
  }

  val decodedCombinational = Wire(new DecodePacket)
  for (lane <- 0 until 3) {
    decodedCombinational.entries(lane).valid := io.fetch(lane).valid
    decodedCombinational.entries(lane).bits.microOp := decoders(lane).io.output
    decodedCombinational.entries(lane).bits.prediction := io.fetch(lane).bits.prediction
    decodedCombinational.entries(lane).bits.recovery := io.fetch(lane).bits.recovery
  }

  for (lane <- 0 until 3) {
    val entry = renamePacket.entries(lane)
    val microOp = entry.bits.microOp
    val instruction = microOp.instruction
    renameTable.io.requests(lane).valid := renameValid && entry.valid
    renameTable.io.requests(lane).source1Valid := microOp.useRj
    renameTable.io.requests(lane).source1 := InstructionFields.rj(instruction)
    renameTable.io.requests(lane).source2Valid := microOp.useRk || microOp.useRd
    renameTable.io.requests(lane).source2 := Mux(
      microOp.useRk,
      InstructionFields.rk(instruction),
      InstructionFields.rd(instruction)
    )
    renameTable.io.requests(lane).writeValid := microOp.writeRegister
    renameTable.io.requests(lane).destination := microOp.writebackAddress
  }

  val recoveryBlocked = io.flush || io.recoverRename
  io.dispatch.valid := dispatchValid && !recoveryBlocked
  io.dispatch.bits := dispatchPacket
  val dispatchFire = io.dispatch.fire
  val dispatchCanTake = !dispatchValid || dispatchFire
  val robReady = (0 until 3)
    .map { lane =>
      !renamePacket.entries(lane).valid || io.robAllocate(lane).ready
    }
    .reduce(_ && _)
  val dispatchStorageCanTake = !dispatchValid || io.dispatch.ready
  val renameTableAdvance = renameValid && renameTable.io.canAdvance &&
    dispatchStorageCanTake && robReady && !io.recoverRename
  val renameFire = renameTableAdvance && !io.flush
  renameTable.io.advance := renameTableAdvance

  def assignRobMicroOp(target: RobMicroOp, source: DecodedEntry): Unit = {
    val microOp = source.microOp
    target.pc := microOp.pc
    target.instruction := microOp.instruction
    target.prediction := source.prediction
    target.predictionRecovery := source.recovery
    target.writebackAddress := microOp.writebackAddress
    target.writeRegister := microOp.writeRegister
    target.isLoad := microOp.isLoad
    target.isStore := microOp.isStore
    target.loadStoreOperation := microOp.loadStoreOperation
    target.readTimerLow := microOp.readTimerLow
    target.readTimerHigh := microOp.readTimerHigh
    target.readTimerId := microOp.readTimerId
    target.isBranch := microOp.isBranch
    target.isJump := microOp.isJump
    target.isJumpRegister := microOp.isJumpRegister
    target.branchLike := microOp.branchLike
    target.tlbOperation := microOp.tlbOperation
    target.operateCache := microOp.operateCache
    target.writeCsr := microOp.writeCsr
    target.readCsr := microOp.readCsr
    target.isWait := microOp.isWait
    target.isLoadLinked := microOp.isLoadLinked
    target.isStoreConditional := microOp.isStoreConditional
    target.uniqueRetire := microOp.uniqueRetire
    target.isErtn := microOp.isErtn
    target.flushState := microOp.flushState
  }

  val robPayload = Wire(Vec(3, new RobAllocate))
  for (lane <- 0 until 3) {
    val decoded = renamePacket.entries(lane).bits
    robPayload(lane) := 0.U.asTypeOf(robPayload(lane))
    robPayload(lane).initialState.loadStoreOperation := LoadStoreOp.Byte
    assignRobMicroOp(robPayload(lane).info.microOp, decoded)
    robPayload(lane).info.rename.previousPhysical := renameTable.io.records(lane).previousWrite
    robPayload(lane).info.rename.physical := renameTable.io.records(lane).write
    robPayload(lane).info.frontendException := decoded.microOp.exception.valid
    robPayload(lane).initialState.complete :=
      decoded.microOp.exception.valid || decoded.microOp.functionalUnit.asUInt === 0.U
    robPayload(lane).initialState.exception.valid := decoded.microOp.exception.valid
    robPayload(lane).initialState.exception.bits.code := decoded.microOp.exception.bits.code
    robPayload(lane).initialState.exception.bits.subcode := decoded.microOp.exception.bits.subcode
    robPayload(lane).initialState.exception.bits.isTlbRefill :=
      decoded.microOp.exception.bits.isTlbRefill
    robPayload(lane).initialState.exception.bits.badAddress := 0.U
    robPayload(lane).initialState.mispredict :=
      !decoded.microOp.branchLike && decoded.prediction.taken

    io.robAllocate(lane).valid := renameFire && renamePacket.entries(lane).valid
    io.robAllocate(lane).bits := robPayload(lane)
  }

  val renamedCombinational = Wire(new RenamePacket)
  for (lane <- 0 until 3) {
    renamedCombinational.entries(lane).valid := renamePacket.entries(lane).valid
    renamedCombinational.entries(lane).bits.decoded := renamePacket.entries(lane).bits
    renamedCombinational.entries(lane).bits.rename := renameTable.io.records(lane)
    renamedCombinational.entries(lane).bits.robIndex := io.robAllocatedIndex(lane)
  }

  val renameCanTake = !renameValid || renameFire
  for (lane <- 0 until 3) io.fetch(lane).ready := renameCanTake && !recoveryBlocked

  when(dispatchCanTake) {
    dispatchValid := renameFire
    when(renameFire) { dispatchPacket := renamedCombinational }
  }
  when(renameCanTake) {
    renameValid := io.fetch(0).valid
    when(io.fetch(0).valid) { renamePacket := decodedCombinational }
  }

  when(io.flush) {
    renameValid := false.B
    dispatchValid := false.B
  }

  io.profileObservation.idFire := VecInit(io.fetch.map(_.fire)).asUInt
  io.profileObservation.renameFire := renameFire
  io.profileObservation.renameRobIndex :=
    VecInit(renamedCombinational.entries.map(_.bits.robIndex)).asUInt
}
