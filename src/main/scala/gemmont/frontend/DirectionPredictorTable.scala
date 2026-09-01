package gemmont.frontend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

class DirectionPredictorUpdate extends Bundle {
  val address = UInt(BranchPredictorParameters.phtIndexWidth.W)
  val taken = Bool()
  val set = Bool()
  val setValue = UInt(2.W)
}

class DirectionPredictorTable(
    initializationFile: String = "src/main/resources/pht-init.hex"
) extends Module {
  private val bankCount = 4
  private val rowCount = BranchPredictorParameters.phtEntries / bankCount
  private val indexWidth = BranchPredictorParameters.phtIndexWidth
  private val rowWidth = log2Ceil(rowCount)

  val io = IO(new Bundle {
    val read = Flipped(Valid(UInt(indexWidth.W)))
    val response = Output(Vec(bankCount, UInt(2.W)))
    val physicalResponse = Output(Vec(bankCount, UInt(2.W)))
    val responseValid = Output(Bool())
    val update = Flipped(Valid(new DirectionPredictorUpdate))
  })

  val banks = Seq.fill(bankCount)(
    SyncReadMem(rowCount, UInt(2.W), SyncReadMem.ReadFirst)
  )
  banks.foreach(memory => loadMemoryFromFileInline(memory, initializationFile))

  val readOffset = io.read.bits(1, 0)
  val readRow = io.read.bits(indexWidth - 1, 2)
  when(io.read.valid) {
    assert(readOffset === 0.U, "PHT requests must be aligned to a physical bank group")
  }
  val bankResponses = Wire(Vec(bankCount, UInt(2.W)))
  for (bank <- 0 until bankCount) {

    bankResponses(bank) := banks(bank).read(readRow, true.B)
  }
  io.physicalResponse := bankResponses

  for (output <- 0 until bankCount) {
    io.response(output) := bankResponses(output)
  }
  io.responseValid := RegNext(io.read.valid, false.B)

  val updateQueues =
    Seq.fill(bankCount)(Module(new Queue(new DirectionPredictorUpdate, 256)))
  val writePending = RegInit(VecInit(Seq.fill(bankCount)(false.B)))
  val writeRow = Reg(Vec(bankCount, UInt(rowWidth.W)))
  val writeTaken = Reg(Vec(bankCount, Bool()))
  val updateBank = io.update.bits.address(1, 0)

  private def saturatingIncrement(value: UInt): UInt =
    Mux(value === 3.U, value, value + 1.U)

  private def saturatingDecrement(value: UInt): UInt =
    Mux(value === 0.U, value, value - 1.U)

  for (bank <- 0 until bankCount) {
    val queue = updateQueues(bank)
    queue.io.enq.valid := io.update.valid && updateBank === bank.U
    queue.io.enq.bits := io.update.bits
    when(queue.io.enq.valid) {
      assert(queue.io.enq.ready, "direction predictor update queue overflow")
    }

    val startRead = !writePending(bank) && queue.io.deq.valid && !queue.io.deq.bits.set
    val directWrite = !writePending(bank) && queue.io.deq.valid && queue.io.deq.bits.set
    queue.io.deq.ready := startRead || directWrite

    val queuedRow = queue.io.deq.bits.address(indexWidth - 1, 2)
    val portAddress = Mux(writePending(bank), writeRow(bank), queuedRow)
    val portReadData = Wire(UInt(2.W))
    val trainedCounter = Mux(
      writeTaken(bank),
      saturatingIncrement(portReadData),
      saturatingDecrement(portReadData)
    )
    val portWriteData = Mux(directWrite, queue.io.deq.bits.setValue, trainedCounter)
    portReadData := banks(bank).readWrite(
      portAddress,
      portWriteData,
      writePending(bank) || startRead || directWrite,
      writePending(bank) || directWrite
    )

    when(writePending(bank)) {
      writePending(bank) := false.B
    }.elsewhen(startRead) {
      writePending(bank) := true.B
      writeRow(bank) := queuedRow
      writeTaken(bank) := queue.io.deq.bits.taken
    }
  }
}
