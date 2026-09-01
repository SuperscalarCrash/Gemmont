package gemmont.backend

import chisel3._
import chisel3.util._
import gemmont.DesignParams

class RenameRequest extends Bundle {
  val valid = Bool()
  val source1Valid = Bool()
  val source1 = UInt(DesignParams.architecturalRegisterAddressWidth.W)
  val source2Valid = Bool()
  val source2 = UInt(DesignParams.architecturalRegisterAddressWidth.W)
  val writeValid = Bool()
  val destination = UInt(DesignParams.architecturalRegisterAddressWidth.W)
}

class RenameRecord extends Bundle {
  val source = Vec(2, UInt(DesignParams.physicalRegisterAddressWidth.W))
  val write = UInt(DesignParams.physicalRegisterAddressWidth.W)
  val previousWrite = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class ArchitecturalCommit extends Bundle {
  val architectural = UInt(DesignParams.architecturalRegisterAddressWidth.W)
  val previousPhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
  val physical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class RenameTable extends Module {
  private val width = DesignParams.issueWidth
  private val pointerWidth = DesignParams.robAddressWidth

  val io = IO(new Bundle {
    val requests = Input(Vec(width, new RenameRequest))
    val advance = Input(Bool())
    val commits = Input(Vec(width, Valid(new ArchitecturalCommit)))
    val recover = Input(Bool())
    val records = Output(Vec(width, new RenameRecord))
    val canAdvance = Output(Bool())
    val allocated = Output(
      Vec(width, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val architecturalRat = Output(
      Vec(
        DesignParams.architecturalRegisterCount - 1,
        UInt(DesignParams.physicalRegisterAddressWidth.W)
      )
    )
  })

  val speculativeRat = RegInit(
    VecInit(
      (1 until DesignParams.architecturalRegisterCount)
        .map(_.U(DesignParams.physicalRegisterAddressWidth.W))
    )
  )
  val architecturalRat = RegInit(
    VecInit(
      (1 until DesignParams.architecturalRegisterCount)
        .map(_.U(DesignParams.physicalRegisterAddressWidth.W))
    )
  )
  val freeStorage = RegInit(
    VecInit(
      (DesignParams.architecturalRegisterCount to DesignParams.physicalRegisterCount)
        .map(_.U(DesignParams.physicalRegisterAddressWidth.W))
    )
  )
  val freeReadPointer = RegInit(0.U(pointerWidth.W))
  val freeWritePointer = RegInit(0.U(pointerWidth.W))
  val risingOccupancy = RegInit(true.B)
  val prefetchedFree = RegInit(
    VecInit(
      (DesignParams.architecturalRegisterCount until
        DesignParams.architecturalRegisterCount + width)
        .map(_.U(DesignParams.physicalRegisterAddressWidth.W))
    )
  )

  io.architecturalRat := architecturalRat

  val freeEmpty = freeReadPointer === freeWritePointer && !risingOccupancy
  val freeFull = freeReadPointer === freeWritePointer && risingOccupancy
  val available = freeWritePointer - freeReadPointer

  val priorWrites = Wire(Vec(width, UInt(2.W)))
  for (lane <- 0 until width) {
    priorWrites(lane) := PopCount(
      io.requests.take(lane).map(request => request.valid && request.writeValid)
    )
  }
  val packetWriteCount = PopCount(io.requests.map(request => request.valid && request.writeValid))

  val allocationValid = Wire(Vec(width, Bool()))
  for (lane <- 0 until width) {
    allocationValid(lane) := freeFull || (!freeEmpty && priorWrites(lane) < available)
  }
  io.canAdvance := !(0 until width)
    .map { lane =>
      io.requests(lane).valid && io.requests(lane).writeValid && !allocationValid(lane)
    }
    .reduce(_ || _)

  def ratRead(address: UInt): UInt = Mux(address === 0.U, 0.U, speculativeRat(address - 1.U))

  for (lane <- 0 until width) {
    val request = io.requests(lane)
    val sourceAddresses = Seq(request.source1, request.source2, request.destination)
    val sourceValid = Seq(request.source1Valid, request.source2Valid, request.writeValid)
    val mappings = Wire(Vec(3, UInt(DesignParams.physicalRegisterAddressWidth.W)))
    for (port <- 0 until 3) {
      mappings(port) := ratRead(sourceAddresses(port))
      for (earlier <- 0 until lane) {
        when(
          sourceValid(port) && io.requests(earlier).valid && io.requests(earlier).writeValid &&
            io.requests(earlier).destination === sourceAddresses(port)
        ) {
          mappings(port) := prefetchedFree(priorWrites(earlier))
        }
      }
    }
    io.records(lane).source(0) := mappings(0)
    io.records(lane).source(1) := mappings(1)
    io.records(lane).previousWrite := mappings(2)

    io.records(lane).write :=
      Mux(request.valid && request.writeValid, prefetchedFree(priorWrites(lane)), 0.U)

    io.allocated(lane).valid := io.advance && request.valid && request.writeValid
    io.allocated(lane).bits := io.records(lane).write
  }

  when(io.advance) {
    assert(io.canAdvance, "rename advanced without enough free physical registers")
    for (lane <- 0 until width) {
      when(io.requests(lane).valid && io.requests(lane).writeValid) {
        speculativeRat(io.requests(lane).destination - 1.U) := io.records(lane).write
      }
    }
  }

  val commitCount = PopCount(io.commits.map(_.valid))
  val priorCommits = Wire(Vec(width, UInt(2.W)))
  for (lane <- 0 until width) {
    priorCommits(lane) := PopCount(io.commits.take(lane).map(_.valid))
    when(io.commits(lane).valid) {
      assert(io.commits(lane).bits.architectural =/= 0.U)
      architecturalRat(io.commits(lane).bits.architectural - 1.U) := io.commits(lane).bits.physical
      freeStorage((freeWritePointer + priorCommits(lane))(pointerWidth - 1, 0)) :=
        io.commits(lane).bits.previousPhysical
    }
  }

  val allocationCount = Mux(io.advance, packetWriteCount, 0.U)
  val nextReadPointer = freeReadPointer + allocationCount

  val advancedReadPointer = freeReadPointer + packetWriteCount
  for (port <- 0 until width) {
    val advancedAddress = (advancedReadPointer + port.U)(pointerWidth - 1, 0)
    val advancedData = WireDefault(freeStorage(advancedAddress))
    for (commit <- 0 until width) {
      when(
        io.commits(commit).valid &&
          (freeWritePointer + priorCommits(commit))(pointerWidth - 1, 0) === advancedAddress
      ) {
        advancedData := io.commits(commit).bits.previousPhysical
      }
    }
    when(io.advance) {
      prefetchedFree(port) := advancedData
    }.otherwise {
      val currentAddress = (freeReadPointer + port.U)(pointerWidth - 1, 0)
      for (commit <- 0 until width) {
        when(
          io.commits(commit).valid &&
            (freeWritePointer + priorCommits(commit))(pointerWidth - 1, 0) === currentAddress
        ) {
          prefetchedFree(port) := io.commits(commit).bits.previousPhysical
        }
      }
    }
  }

  when(io.advance) {
    freeReadPointer := nextReadPointer
  }
  when(commitCount =/= 0.U) {
    freeWritePointer := freeWritePointer + commitCount
  }
  when(commitCount =/= allocationCount) {
    risingOccupancy := commitCount > allocationCount
  }

  when(io.recover) {
    speculativeRat := architecturalRat
    freeWritePointer := freeReadPointer
    risingOccupancy := true.B
  }

  when(commitCount =/= 0.U) {
    assert(!freeFull || allocationCount =/= 0.U, "commit attempted to overfill physical free list")
  }
}
