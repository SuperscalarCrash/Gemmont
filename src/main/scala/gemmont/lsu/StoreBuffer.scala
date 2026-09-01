package gemmont.lsu

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.isa.LoadStoreOp

class StoreBufferSlot extends Bundle {
  val retired = Bool()
  val address = UInt(32.W)
  val byteEnable = UInt(4.W)
  val data = UInt(32.W)
  val isStore = Bool()
  val cached = Bool()
  val writeRegister = Valid(UInt(6.W))
  val operation = LoadStoreOp()
  val robIndex = UInt(5.W)
}

class StoreBuffer extends Module {
  private val depth = DesignParams.storeBufferDepth
  private val drainDepth = 2
  val io = IO(new Bundle {
    val push = Flipped(Decoupled(new StoreBufferSlot))
    val pop = Decoupled(new StoreBufferSlot)
    val commitStore = Input(Bool())
    val flush = Input(Bool())
    val queryAddress = Input(UInt(32.W))
    val queryData = Output(UInt(32.W))
    val queryByteEnable = Output(UInt(4.W))
    val occupancy = Output(UInt(4.W))
  })

  val queue = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(Valid(new StoreBufferSlot)))))

  val retiredDrain = Reg(Vec(drainDepth, new StoreBufferSlot))
  val drainHead = RegInit(false.B)
  val drainTail = RegInit(false.B)
  val drainOccupancy = RegInit(0.U(log2Ceil(drainDepth + 1).W))
  val queueOccupancy = PopCount(queue.map(_.valid))
  io.occupancy := queueOccupancy +& drainOccupancy
  io.push.ready := io.occupancy < depth.U
  assert(io.occupancy <= depth.U)
  assert(drainOccupancy <= drainDepth.U)
  io.pop.valid := drainOccupancy =/= 0.U
  io.pop.bits := retiredDrain(drainHead)

  val queryBytes = Wire(Vec(4, UInt(8.W)))
  val queryByteEnable = Wire(Vec(4, Bool()))

  val orderedDrain = Wire(Vec(drainDepth, Valid(new StoreBufferSlot)))
  orderedDrain(0).valid := drainOccupancy > 0.U
  orderedDrain(0).bits := retiredDrain(drainHead)
  orderedDrain(1).valid := drainOccupancy > 1.U
  orderedDrain(1).bits := retiredDrain(!drainHead)
  val forwardingSlots = orderedDrain.toSeq ++ queue.toSeq
  for (byte <- 0 until 4) {
    val matches = forwardingSlots.map { slot =>
      io.queryAddress(31, 2) === slot.bits.address(31, 2) && slot.valid &&
      slot.bits.isStore && slot.bits.cached && slot.bits.byteEnable(byte)
    }
    queryByteEnable(byte) := matches.reduce(_ || _)
    queryBytes(byte) := PriorityMux(
      forwardingSlots.indices.reverse.map { slot =>
        matches(slot) -> forwardingSlots(slot).bits.data(8 * byte + 7, 8 * byte)
      } :+
        (true.B -> 0.U(8.W))
    )
  }
  io.queryByteEnable := queryByteEnable.asUInt
  io.queryData := Cat(queryBytes.reverse)

  val afterRetire = WireInit(queue)
  val retireFall = Wire(Vec(depth, Bool()))
  retireFall(0) := !queue(0).bits.retired
  for (slot <- 1 until depth) {
    retireFall(slot) := queue(slot - 1).bits.retired && !queue(slot).bits.retired
  }
  for (slot <- 0 until depth) {
    when(io.commitStore && retireFall(slot)) {
      afterRetire(slot).bits.retired := true.B
    }
  }

  val afterEnqueue = WireInit(afterRetire)
  val validFall = Wire(Vec(depth, Bool()))
  validFall(0) := !queue(0).valid
  for (slot <- 1 until depth) validFall(slot) := queue(slot - 1).valid && !queue(slot).valid
  for (slot <- 0 until depth) {
    when(io.push.fire && validFall(slot)) {
      afterEnqueue(slot).valid := true.B
      afterEnqueue(slot).bits := io.push.bits
    }
  }

  val afterFlush = WireInit(afterEnqueue)
  when(io.flush) {
    for (slot <- 0 until depth) {
      when(!afterEnqueue(slot).bits.retired) {
        afterFlush(slot).valid := false.B
      }
    }
  }

  val drainCanAccept = drainOccupancy < drainDepth.U
  val moveToDrain =
    afterFlush.head.valid && afterFlush.head.bits.retired && drainCanAccept

  when(moveToDrain) {
    retiredDrain(drainTail) := afterFlush.head.bits
    drainTail := !drainTail
  }
  when(io.pop.fire) {
    drainHead := !drainHead
  }
  switch(Cat(moveToDrain, io.pop.fire)) {
    is("b10".U) { drainOccupancy := drainOccupancy + 1.U }
    is("b01".U) { drainOccupancy := drainOccupancy - 1.U }
  }

  when(moveToDrain) {
    for (slot <- 0 until depth) {
      if (slot + 1 < depth) queue(slot) := afterFlush(slot + 1)
      else queue(slot).valid := false.B
    }
  }.otherwise {
    queue := afterFlush
  }
}
