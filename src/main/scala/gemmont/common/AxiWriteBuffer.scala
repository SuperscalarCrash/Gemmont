package gemmont.common

import chisel3._
import chisel3.util._

class BufferedWriteEntry(addressWidth: Int, dataWidth: Int, idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val address = UInt(addressWidth.W)
  val data = UInt(dataWidth.W)
  val size = UInt(3.W)
  val strobe = UInt((dataWidth / 8).W)
}

class AxiWriteBuffer(
    addressWidth: Int = 32,
    dataWidth: Int = 32,
    idWidth: Int = 4,
    depth: Int = 1024
) extends Module {
  require(depth > 0 && isPow2(depth))

  private val pointerWidth = log2Ceil(depth)
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val input = Flipped(new Axi4Master(addressWidth, dataWidth, idWidth))
    val output = new Axi4Master(addressWidth, dataWidth, idWidth)
  })

  private def blockInput(bus: Axi4Master): Unit = {
    bus.aw.ready := false.B
    bus.w.ready := false.B
    bus.b.valid := false.B
    bus.b.bits := 0.U.asTypeOf(bus.b.bits)
    bus.ar.ready := false.B
    bus.r.valid := false.B
    bus.r.bits := 0.U.asTypeOf(bus.r.bits)
  }

  blockInput(io.input)
  Axi4.setIdle(io.output)
  io.output.b.ready := true.B
  io.input.b.bits.resp := Axi4.Okay

  val entries = SyncReadMem(depth, new BufferedWriteEntry(addressWidth, dataWidth, idWidth))
  val pushPointer = RegInit(0.U(pointerWidth.W))
  val popPointer = RegInit(0.U(pointerWidth.W))
  val count = RegInit(0.U(countWidth.W))
  val empty = count === 0.U
  val full = count === depth.U

  val writeBoot :: writeWaitRead :: writeSend :: writeWaitResponse :: Nil = Enum(4)
  val writeState = RegInit(writeBoot)
  val dequeue = writeState === writeBoot && !empty
  val writeRunning = writeState =/= writeBoot || dequeue
  val dequeueData = entries.read(popPointer, dequeue)
  val activeWrite = Reg(new BufferedWriteEntry(addressWidth, dataWidth, idWidth))
  val writeAddressSent = RegInit(false.B)
  val writeDataSent = RegInit(false.B)

  val enqueue = !full && io.input.aw.valid && !dequeue
  when(enqueue) {
    io.input.aw.ready := true.B
    io.input.w.ready := true.B
    io.input.b.valid := true.B
  }

  val enqueueEntry = Wire(new BufferedWriteEntry(addressWidth, dataWidth, idWidth))
  enqueueEntry.id := io.input.aw.bits.id
  enqueueEntry.address := io.input.aw.bits.addr
  enqueueEntry.data := io.input.w.bits.data
  enqueueEntry.size := io.input.aw.bits.size
  enqueueEntry.strobe := io.input.w.bits.strb
  when(enqueue) {
    entries.write(pushPointer, enqueueEntry)
    pushPointer := pushPointer + 1.U
  }
  when(dequeue) {
    popPointer := popPointer + 1.U
  }
  when(enqueue =/= dequeue) {
    count := Mux(enqueue, count + 1.U, count - 1.U)
  }

  switch(writeState) {
    is(writeBoot) {
      when(dequeue) {
        writeState := writeWaitRead
      }
    }
    is(writeWaitRead) {
      activeWrite := dequeueData
      writeAddressSent := false.B
      writeDataSent := false.B
      writeState := writeSend
    }
    is(writeSend) {
      io.output.aw.valid := !writeAddressSent
      io.output.aw.bits.id := activeWrite.id
      io.output.aw.bits.addr := activeWrite.address
      io.output.aw.bits.len := 0.U
      io.output.aw.bits.size := activeWrite.size
      io.output.aw.bits.burst := Axi4.IncrementingBurst
      io.output.w.valid := !writeDataSent
      io.output.w.bits.data := activeWrite.data
      io.output.w.bits.strb := activeWrite.strobe
      io.output.w.bits.last := true.B

      when(io.output.aw.fire) { writeAddressSent := true.B }
      when(io.output.w.fire) { writeDataSent := true.B }
      val addressDone = writeAddressSent || io.output.aw.fire
      val dataDone = writeDataSent || io.output.w.fire
      when(addressDone && dataDone) {
        writeState := Mux(io.output.b.valid, writeBoot, writeWaitResponse)
      }
    }
    is(writeWaitResponse) {
      when(io.output.b.valid) {
        writeState := writeBoot
      }
    }
  }

  val readBoot :: readWaitResponse :: Nil = Enum(2)
  val readState = RegInit(readBoot)
  val routeRead = readState === readWaitResponse ||
    (readState === readBoot && empty && io.input.ar.valid && !writeRunning)
  when(routeRead) {
    io.output.ar.valid := io.input.ar.valid
    io.output.ar.bits := io.input.ar.bits
    io.input.ar.ready := io.output.ar.ready
    io.input.r.valid := io.output.r.valid
    io.input.r.bits := io.output.r.bits
    io.output.r.ready := io.input.r.ready
  }
  when(readState === readBoot && empty && io.input.ar.valid && !writeRunning) {
    readState := readWaitResponse
  }
  when(readState === readWaitResponse && io.input.r.fire && io.input.r.bits.last) {
    readState := readBoot
  }
}
