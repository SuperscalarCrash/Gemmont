package gemmont.backend.issue

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.DesignParams
import gemmont.isa.FunctionalUnit

private class IntegerIssueCompactionControl(
    depth: Int,
    issueWidth: Int,
    destination: Int
) extends Module {
  require(destination >= 0 && destination < depth)

  override def desiredName = s"IntegerIssueCompactionControlD$destination"

  val io = IO(new Bundle {
    val firedIssueMask = Input(UInt(depth.W))
    val moveBy = Output(Vec(issueWidth, Bool()))
  })

  for (index <- 0 until issueWidth) {
    val distance = index + 1
    val source = destination + distance
    val prefixEnd = math.min(source - 1, depth - 1)
    val prefixCount = Wire(UInt(log2Ceil(issueWidth + 1).W))
    prefixCount := PopCount(io.firedIssueMask(prefixEnd, 0))
    io.moveBy(index) := prefixCount === distance.U
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(io.moveBy(index).toTarget, "max_fanout = 16")
    })
  }
}

class TypedIntegerIssueQueue[T <: Data](gen: T) extends Module {
  private val depth = 7
  private val issueWidth = DesignParams.issueWidth
  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(3, Decoupled(new TypedIssueSlot(gen))))
    val globalWakeup = Input(
      Vec(5, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val sameCycleWakeup = Input(
      Vec(
        issueWidth,
        Vec(depth, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
      )
    )
    val issueFire = Input(Bool())
    val flush = Input(Bool())
    val issued = Output(Vec(3, Valid(new TypedIssueSlot(gen))))
    val occupancy = Output(UInt(3.W))
  })

  val slots = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(Valid(new TypedIssueSlot(gen))))))
  io.occupancy := PopCount(slots.map(_.valid))

  val grants = Wire(Vec(issueWidth, UInt(depth.W)))
  def operandsReady(slot: Int, lane: Int): Bool =
    slots(slot).bits.operands
      .map { operand =>
        operand.ready ||
        (io.sameCycleWakeup(lane)(slot).valid &&
          io.sameCycleWakeup(lane)(slot).bits === operand.physical)
      }
      .reduce(_ && _)

  val aluRequests = Seq.tabulate(issueWidth) { lane =>
    VecInit((0 until depth).map { slot =>
      slots(slot).valid && operandsReady(slot, lane) &&
      slots(slot).bits.functionalUnit === FunctionalUnit.Alu
    })
  }
  def rankRequests(requests: UInt): Seq[UInt] = {

    val rows = (0 until (1 << depth)).map { mask =>
      val selectedSlots = (0 until depth).filter(slot => (mask & (1 << slot)) != 0)
      val rankWords = (0 until issueWidth).map { rank =>
        selectedSlots
          .lift(rank)
          .map(slot => BigInt(1) << slot)
          .getOrElse(BigInt(0))
          .U(depth.W)
      }
      Cat(rankWords.reverse)
    }
    val packedGrants = VecInit(rows)(requests)
    (0 until issueWidth).map { rank =>
      packedGrants((rank + 1) * depth - 1, rank * depth)
    }
  }
  val aluGrantsByRank = Seq.tabulate(issueWidth) { lane =>
    rankRequests(aluRequests(lane).asUInt)
  }
  val timerRequests = VecInit((0 until depth).map { slot =>
    slots(slot).valid && operandsReady(slot, 1) &&
    slots(slot).bits.functionalUnit === FunctionalUnit.Timer
  })
  val specialRequests = VecInit((0 until depth).map { slot =>
    val unit = slots(slot).bits.functionalUnit
    slots(slot).valid && operandsReady(slot, 2) &&
    (unit === FunctionalUnit.Compare || unit === FunctionalUnit.Csr ||
      unit === FunctionalUnit.InvTlb)
  })
  val timerGrant = PriorityEncoderOH(timerRequests.asUInt)
  val specialGrant = PriorityEncoderOH(specialRequests.asUInt)

  grants(0) := aluGrantsByRank(0)(0)
  grants(1) := Mux(timerGrant.orR, timerGrant, aluGrantsByRank(1)(1))
  grants(2) := Mux(
    specialGrant.orR,
    specialGrant,
    Mux(timerGrant.orR, aluGrantsByRank(2)(1), aluGrantsByRank(2)(2))
  )
  for (lane <- 0 until issueWidth) {
    io.issued(lane).valid := grants(lane).orR
    io.issued(lane).bits := 0.U.asTypeOf(io.issued(lane).bits)
    for (slot <- 0 until depth) {
      when(grants(lane)(slot)) { io.issued(lane).bits := slots(slot).bits }
    }
  }
  val issueMask = grants.reduce(_ | _)

  val afterWakeup = WireInit(slots)
  for (slot <- 0 until depth; operand <- 0 until 2; wakeup <- io.globalWakeup) {
    when(wakeup.valid && wakeup.bits === slots(slot).bits.operands(operand).physical) {
      afterWakeup(slot).bits.operands(operand).ready := true.B
    }
  }
  val afterLocalWakeup = WireInit(afterWakeup)
  for (slot <- 0 until depth; operand <- 0 until 2) {
    val localWakeupHit = (0 until depth)
      .map { producer =>
        io.issueFire && issueMask(producer) && slots(producer).bits.writeRegister &&
        slots(producer).bits.writePhysical === slots(slot).bits.operands(operand).physical
      }
      .reduce(_ || _)
    when(localWakeupHit) {
      afterLocalWakeup(slot).bits.operands(operand).ready := true.B
    }
  }

  val afterEnqueue = WireInit(afterLocalWakeup)
  val validFall = Wire(Vec(depth, Bool()))
  validFall(0) := !slots(0).valid
  for (slot <- 1 until depth) validFall(slot) := slots(slot - 1).valid && !slots(slot).valid
  for (port <- 0 until 3) {
    io.enqueue(port).ready := !slots(depth - port - 1).valid
    for (slot <- port until depth) {
      when(validFall(slot - port)) {

        afterEnqueue(slot).bits := io.enqueue(port).bits
      }
      when(io.enqueue(port).valid && validFall(slot - port)) {
        afterEnqueue(slot).valid := true.B
      }
    }
  }
  val firedIssueMask = Mux(io.issueFire, issueMask, 0.U)
  private val compactionControl = Seq.tabulate(depth) { destination =>
    val control = Module(
      new IntegerIssueCompactionControl(depth, issueWidth, destination)
    )
    control.io.firedIssueMask := firedIssueMask
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(control.toTarget, "keep_hierarchy = \"yes\"")
    })
    control
  }
  val afterCompress = WireInit(afterEnqueue)
  for (destination <- 0 until depth; distance <- 1 to issueWidth) {
    if (destination + distance < depth) {
      when(compactionControl(destination).io.moveBy(distance - 1)) {
        afterCompress(destination) := afterEnqueue(destination + distance)
      }
    } else {
      when(compactionControl(destination).io.moveBy(distance - 1)) {
        afterCompress(destination).valid := false.B
      }
    }
  }
  when(io.flush) {
    for (slot <- 0 until depth) slots(slot).valid := false.B
  }.otherwise {
    for (slot <- 0 until depth) slots(slot).valid := afterCompress(slot).valid
  }

  for (slot <- 0 until depth) {
    slots(slot).bits := afterCompress(slot).bits
  }
}

class TypedInOrderIssueQueue[T <: Data](depth: Int, gen: T) extends Module {
  private val pointerWidth = log2Ceil(depth)
  private val occupancyWidth = log2Ceil(depth + 1)
  val io = IO(new Bundle {
    val enqueue = Flipped(Vec(3, Decoupled(new TypedIssueSlot(gen))))
    val wakeup = Input(
      Vec(5, Valid(UInt(DesignParams.physicalRegisterAddressWidth.W)))
    )
    val sameCycleWakeup = Input(
      Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
    val issueFire = Input(Bool())
    val flush = Input(Bool())
    val issued = Output(Valid(new TypedIssueSlot(gen)))
    val occupancy = Output(UInt(occupancyWidth.W))
    val headOperandReady = Output(Vec(2, Bool()))
  })

  val slots = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new TypedIssueSlot(gen)))))
  val head = RegInit(0.U(pointerWidth.W))
  val occupancy = RegInit(0.U(occupancyWidth.W))
  io.occupancy := occupancy
  assert(occupancy <= depth.U)

  def wrapAdd(base: UInt, offset: UInt): UInt = {
    val sum = base +& offset
    Mux(sum >= depth.U, sum - depth.U, sum)(pointerWidth - 1, 0)
  }

  val headSlot = slots(head)
  val headOperandReady = VecInit(
    headSlot.operands
      .map { operand =>
        operand.ready || (io.sameCycleWakeup.valid && io.sameCycleWakeup.bits === operand.physical)
      }
  )
  io.headOperandReady := headOperandReady
  val headOperandsReady = headOperandReady.reduce(_ && _)

  io.issued.valid := occupancy =/= 0.U && headOperandsReady
  io.issued.bits := headSlot

  val afterWakeup = WireInit(slots)
  for (slot <- 0 until depth; operand <- 0 until 2; wakeup <- io.wakeup) {
    when(wakeup.valid && wakeup.bits === slots(slot).operands(operand).physical) {
      afterWakeup(slot).operands(operand).ready := true.B
    }
  }

  for (port <- 0 until 3) {
    io.enqueue(port).ready := occupancy <= (depth - port - 1).U
  }
  val enqueueFire = VecInit(io.enqueue.map(_.fire))
  val enqueueCount = PopCount(enqueueFire)
  val enqueueIndex = Wire(Vec(3, UInt(pointerWidth.W)))
  for (port <- 0 until 3) {
    val priorEnqueues = if (port == 0) 0.U else PopCount(enqueueFire.take(port))
    enqueueIndex(port) := wrapAdd(head, occupancy + priorEnqueues)
  }
  for (slot <- 0 until depth) {
    slots(slot) := afterWakeup(slot)
    for (port <- 0 until 3) {
      when(enqueueFire(port) && enqueueIndex(port) === slot.U) {
        slots(slot) := io.enqueue(port).bits
      }
    }
  }

  val dequeue = io.issueFire
  when(io.flush) {
    head := 0.U
    occupancy := 0.U
  }.otherwise {
    when(dequeue) {
      head := wrapAdd(head, 1.U)
    }
    occupancy := occupancy + enqueueCount - dequeue.asUInt
  }

}
