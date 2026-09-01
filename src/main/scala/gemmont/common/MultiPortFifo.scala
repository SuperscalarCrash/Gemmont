package gemmont.common

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._

class MultiPortFifo[T <: Data](
    gen: T,
    depth: Int,
    pushPorts: Int,
    popPorts: Int,
    initializeFull: Boolean = false,
    localizePushControl: Boolean = false
) extends Module {
  require(depth > 0 && isPow2(depth))
  require(pushPorts > 0 && pushPorts <= depth)
  require(popPorts > 0 && popPorts <= depth)

  private val addressWidth = log2Ceil(depth)
  private val pushCountWidth = log2Ceil(pushPorts + 1)
  private val popCountWidth = log2Ceil(popPorts + 1)

  val io = IO(new Bundle {
    val push = Flipped(Vec(pushPorts, Decoupled(gen.cloneType)))
    val pop = Vec(popPorts, Decoupled(gen.cloneType))
    val flush = Input(Bool())
    val empty = Output(Bool())
    val full = Output(Bool())
  })

  val storage = Reg(Vec(depth, gen.cloneType))
  val pushPointer = RegInit(0.U(addressWidth.W))
  val popPointer = RegInit(0.U(addressWidth.W))
  val risingOccupancy = RegInit(initializeFull.B)

  val empty = pushPointer === popPointer && !risingOccupancy
  val full = pushPointer === popPointer && risingOccupancy
  io.empty := empty
  io.full := full

  val maximumPush = popPointer - pushPointer
  val maximumPop = pushPointer - popPointer

  for (index <- 0 until pushPorts) {
    io.push(index).ready := !full && (empty || index.U < maximumPush)
  }

  val pushFires = VecInit(io.push.map(_.fire))
  val effectivePushCount = PriorityMux(
    (0 until pushPorts).map(index => !pushFires(index) -> index.U(pushCountWidth.W)) :+
      (true.B -> pushPorts.U(pushCountWidth.W))
  )

  val localizedPushPointers = if (localizePushControl) {
    Some(Seq.fill(depth)(RegInit(0.U(addressWidth.W))))
  } else {
    None
  }
  localizedPushPointers.foreach(_.foreach { pointer =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(pointer.toTarget, "dont_touch = \"yes\"")
    })
  })

  localizedPushPointers match {
    case Some(pointers) =>
      for (slot <- 0 until depth) {
        val selects = (0 until pushPorts).map { index =>
          val prefixValid = io.push.take(index + 1).map(_.valid).reduce(_ && _)
          val address = (pointers(slot) + index.U)(addressWidth - 1, 0)
          io.push(index).ready && prefixValid && address === slot.U
        }
        when(selects.reduce(_ || _)) {
          storage(slot) := Mux1H(selects, io.push.map(_.bits))
        }
      }
    case None =>
      for (index <- 0 until pushPorts) {
        val prefixValid = io.push.take(index + 1).map(_.valid).reduce(_ && _)
        when(io.push(index).ready && prefixValid) {
          storage((pushPointer + index.U)(addressWidth - 1, 0)) := io.push(index).bits
        }
      }
  }

  localizedPushPointers.foreach(_.foreach { pointer =>
    pointer := pointer + effectivePushCount
    when(io.flush) {
      pointer := 0.U
    }
  })

  for (index <- 0 until popPorts) {
    io.pop(index).valid := !empty && (full || index.U < maximumPop)
    io.pop(index).bits := storage((popPointer + index.U)(addressWidth - 1, 0))
  }

  val popFires = VecInit(io.pop.map(_.fire))
  val effectivePopCount = PriorityMux(
    (0 until popPorts).map(index => !popFires(index) -> index.U(popCountWidth.W)) :+
      (true.B -> popPorts.U(popCountWidth.W))
  )

  pushPointer := pushPointer + effectivePushCount
  popPointer := popPointer + effectivePopCount
  when(effectivePushCount =/= effectivePopCount) {
    risingOccupancy := effectivePushCount > effectivePopCount
  }
  when(io.flush) {
    pushPointer := 0.U
    popPointer := 0.U
    risingOccupancy := false.B
  }

  for (index <- 1 until pushPorts) {
    when(io.push(index).valid) {
      assert(io.push(index - 1).valid, "push valid ports must form a prefix")
    }
  }
  for (index <- 1 until popPorts) {
    when(io.pop(index).ready) {
      assert(io.pop(index - 1).ready, "pop ready ports must form a prefix")
    }
  }
}
