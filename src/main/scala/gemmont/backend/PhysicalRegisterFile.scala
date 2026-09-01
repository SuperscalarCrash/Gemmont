package gemmont.backend

import chisel3._
import chisel3.util._
import gemmont.DesignParams

class PhysicalWrite extends Bundle {
  val address = UInt(DesignParams.physicalRegisterAddressWidth.W)
  val data = UInt(32.W)
  val bypass = Bool()
}

class PhysicalRegisterFile(
    readPorts: Int,
    busyReadPorts: Int,
    writePorts: Int,
    clearBusyPorts: Int,
    allocationPorts: Int = 3
) extends Module {
  val io = IO(new Bundle {
    val readAddress = Input(Vec(readPorts, UInt(DesignParams.physicalRegisterAddressWidth.W)))
    val readData = Output(Vec(readPorts, UInt(32.W)))
    val lsuBypassResultBit2ForMdu = Input(Bool())
    val lsuBypassResultBit30ForMdu = Input(Bool())
    val busyAddress = Input(
      Vec(busyReadPorts, UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val busy = Output(Vec(busyReadPorts, Bool()))
    val write = Input(Vec(writePorts, Valid(new PhysicalWrite)))
    val clearBusy = Input(
      Vec(clearBusyPorts, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val allocate = Input(
      Vec(allocationPorts, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val debugRegisters = Output(Vec(DesignParams.physicalRegisterCount, UInt(32.W)))
  })

  val registers = RegInit(
    VecInit(Seq.fill(DesignParams.physicalRegisterCount)(0.U(32.W)))
  )
  val busy = RegInit(
    VecInit(Seq.fill(DesignParams.physicalRegisterCount)(false.B))
  )
  io.debugRegisters := registers

  for (port <- 0 until readPorts) {
    val address = io.readAddress(port)
    val storedData = Mux(address === 0.U, 0.U, registers(address - 1.U))
    val matches = VecInit(io.write.map { write =>
      write.valid && write.bits.bypass && write.bits.address === address
    })
    val bypassData = io.write.zipWithIndex.map { case (write, producer) =>
      if ((port == 6 || port == 7) && producer == 4)
        Cat(
          write.bits.data(31),
          io.lsuBypassResultBit30ForMdu,
          write.bits.data(29, 3),
          io.lsuBypassResultBit2ForMdu,
          write.bits.data(1, 0)
        )
      else write.bits.data
    }
    io.readData(port) := Mux1H(
      Seq(!matches.asUInt.orR -> storedData) ++
        bypassData.zip(matches).map { case (data, hit) => hit -> data }
    )
  }

  for (port <- 0 until busyReadPorts) {
    val address = io.busyAddress(port)
    io.busy(port) := Mux(address === 0.U, false.B, busy(address - 1.U))
    for (clear <- 0 until clearBusyPorts) {
      when(io.clearBusy(clear).valid && io.clearBusy(clear).bits === address) {
        io.busy(port) := false.B
      }
    }
  }

  for (port <- 0 until allocationPorts) {
    when(io.allocate(port).valid) {
      assert(io.allocate(port).bits =/= 0.U)
      busy(io.allocate(port).bits - 1.U) := true.B
    }
  }
  for (port <- 0 until clearBusyPorts) {
    when(io.clearBusy(port).valid) {
      assert(io.clearBusy(port).bits =/= 0.U)
      busy(io.clearBusy(port).bits - 1.U) := false.B
    }
  }
  for (port <- 0 until writePorts) {
    when(io.write(port).valid) {
      assert(io.write(port).bits.address =/= 0.U)
      registers(io.write(port).bits.address - 1.U) := io.write(port).bits.data
    }
  }

  for (left <- 0 until writePorts; right <- left + 1 until writePorts) {
    assert(
      !(io.write(left).valid && io.write(right).valid &&
        io.write(left).bits.address === io.write(right).bits.address),
      "physical register write conflict"
    )
  }
}
