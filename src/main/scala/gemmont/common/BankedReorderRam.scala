package gemmont.common

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

class BankedRamWrite(wordWidth: Int, addressWidth: Int) extends Bundle {
  val address = UInt(addressWidth.W)
  val data = UInt(wordWidth.W)
}

class BankedReorderRam(
    wordWidth: Int,
    wordCount: Int,
    portCount: Int,
    readLatency: Int = 1,
    writeFirst: Boolean = false,
    initializationFile: Option[String] = None,
    readWrapBoundaryWords: Option[Int] = None
) extends Module {
  require(wordWidth > 0)
  require(wordCount > 0 && isPow2(wordCount))
  require(portCount > 0 && isPow2(portCount))
  require(wordCount % portCount == 0)
  require(readLatency == 1 || readLatency == 2)
  readWrapBoundaryWords.foreach { boundaryWords =>
    require(boundaryWords > portCount && isPow2(boundaryWords))
    require(wordCount % boundaryWords == 0)
  }

  private val addressWidth = log2Ceil(wordCount)
  private val offsetWidth = log2Ceil(portCount)
  private val rows = wordCount / portCount
  private val rowWidth = math.max(1, log2Ceil(rows))

  val io = IO(new Bundle {
    val read = Flipped(Valid(UInt(addressWidth.W)))
    val response = Output(Vec(portCount, UInt(wordWidth.W)))
    val physicalResponse = Output(Vec(portCount, UInt(wordWidth.W)))
    val physicalResponseForwarded = Output(Vec(portCount, Bool()))
    val responseValid = Output(Bool())
    val write = Flipped(Valid(new BankedRamWrite(wordWidth, addressWidth)))
  })

  val banks = Seq.fill(portCount)(
    SyncReadMem(rows, UInt(wordWidth.W), SyncReadMem.ReadFirst)
  )
  initializationFile.foreach(file =>
    banks.foreach(memory => loadMemoryFromFileInline(memory, file))
  )
  val readOffset = if (portCount == 1) 0.U else io.read.bits(offsetWidth - 1, 0)
  val readRow = io.read.bits(addressWidth - 1, offsetWidth)

  val readRows = (0 until portCount).map { bank =>
    val crossesBankBoundary = bank.U < readOffset
    readWrapBoundaryWords match {
      case Some(boundaryWords) =>
        val boundaryRowWidth = log2Ceil(boundaryWords / portCount)
        val lowRow =
          (readRow(boundaryRowWidth - 1, 0) + crossesBankBoundary)(
            boundaryRowWidth - 1,
            0
          )
        if (boundaryRowWidth == rowWidth) lowRow
        else Cat(readRow(rowWidth - 1, boundaryRowWidth), lowRow)
      case None =>
        (readRow + crossesBankBoundary)(rowWidth - 1, 0)
    }
  }

  val bankResponses = Wire(Vec(portCount, UInt(wordWidth.W)))
  for (bank <- 0 until portCount) {

    bankResponses(bank) := banks(bank).read(readRows(bank), true.B)
  }

  val writeOffset = if (portCount == 1) 0.U else io.write.bits.address(offsetWidth - 1, 0)
  val writeRow = io.write.bits.address(addressWidth - 1, offsetWidth)
  for (bank <- 0 until portCount) {
    when(io.write.valid && writeOffset === bank.U) {
      banks(bank).write(writeRow, io.write.bits.data)
    }
  }

  val requestOffset = RegEnable(readOffset, io.read.valid)

  val physicalForwarded = Wire(Vec(portCount, Bool()))
  for (bank <- 0 until portCount) {
    physicalForwarded(bank) :=
      (if (writeFirst)
         io.read.valid && io.write.valid && writeOffset === bank.U &&
         readRows(bank) === writeRow
       else false.B)
  }
  val physicalForwardedDelay1 = RegNext(
    physicalForwarded,
    VecInit(Seq.fill(portCount)(false.B))
  )

  val physicalForwardedDataDelay1 = RegNext(io.write.bits.data)
  val physicalResponse = Wire(Vec(portCount, UInt(wordWidth.W)))
  for (bank <- 0 until portCount) {
    physicalResponse(bank) := Mux(
      physicalForwardedDelay1(bank),
      physicalForwardedDataDelay1,
      bankResponses(bank)
    )
  }
  io.physicalResponse := physicalResponse

  io.physicalResponseForwarded := physicalForwardedDelay1
  val aligned = Wire(Vec(portCount, UInt(wordWidth.W)))
  for (output <- 0 until portCount) {
    aligned(output) := physicalResponse((requestOffset + output.U)(offsetWidth - 1, 0))
  }

  val response1 = aligned

  if (readLatency == 1) {
    io.response := response1
    io.responseValid := RegNext(io.read.valid, false.B)
  } else {
    io.response := RegEnable(response1, RegNext(io.read.valid, false.B))
    io.responseValid := RegNext(RegNext(io.read.valid, false.B), false.B)
  }
}
