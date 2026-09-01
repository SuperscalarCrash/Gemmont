package gemmont.frontend

import chisel3._
import chisel3.util._

private class H64WeightRom extends BlackBox with HasBlackBoxResource {
  import H64CorrectorParameters._

  override def desiredName: String = "H64WeightRom"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val enable = Input(Bool())
    val address = Input(UInt(rowWidth.W))
    val data = Output(UInt(physicalRowWidth.W))
  })

  addResource("/H64WeightRom.sv")
}

class H64BranchCorrector(
    initializationFile: String = "src/main/resources/h64-residual-weights-int4.hex",
    marginThreshold: Int = 1
) extends Module {
  import H64CorrectorParameters._

  require(marginThreshold >= 0 && marginThreshold < (1 << (scoreWidth - 1)))

  val io = IO(new Bundle {
    val query = Flipped(Valid(new H64CorrectorQuery))
    val fast = Flipped(Valid(new H64FastMetadata))
    val indexStateConditionalMask = Input(UInt(fetchWidth.W))
    val indexStateTakenMask = Input(UInt(fetchWidth.W))
    val flush = Input(Bool())
    val fastPostHistory = Output(UInt(historyWidth.W))
    val fastPostPath = Output(UInt(pathWidth.W))
    val fastPostIndexState = Output(UInt(indexWidth.W))
    val result = Output(Valid(new H64CorrectorResult))
  })

  private def balancedSum(values: Seq[SInt]): SInt = {
    require(values.nonEmpty)
    if (values.length == 1) values.head
    else {
      val next = values
        .grouped(2)
        .map {
          case Seq(left, right) => left +& right
          case Seq(left)        => left
        }
        .toSeq
      balancedSum(next)
    }
  }

  private def weight(row: UInt, index: Int): SInt =
    row((index + 1) * weightBits - 1, index * weightBits).asSInt

  private def signedFeatureTerm(value: SInt, feature: Bool): SInt = {
    val extended = value.pad(scoreWidth)
    Mux(feature, extended, -extended)
  }

  require(
    initializationFile == "src/main/resources/h64-residual-weights-int4.hex",
    "H64WeightRom currently has one frozen synthesis image"
  )
  private val weights = Module(new H64WeightRom)

  val groupPc = Cat(io.query.bits.pc(31, 4), 0.U(4.W))
  val wordPc = groupPc >> 2
  val pcHash = wordPc ^ (wordPc >> indexWidth) ^ (wordPc >> (2 * indexWidth))
  val tableIndex = pcHash(indexWidth - 1, 0) ^ io.query.bits.indexState

  val q0Valid = RegNext(io.query.valid, false.B)
  val q0Pc = RegNext(io.query.bits.pc)
  val q0History = RegNext(io.query.bits.history)
  val q0Path = RegNext(io.query.bits.path)
  val q0IndexState = RegNext(io.query.bits.indexState)
  val q0Token = RegNext(io.query.bits.token)
  val q0Epoch = RegNext(io.query.bits.epoch)
  val q0BankPermutation = RegNext(tableIndex(1, 0))
  val q0RomAddress = RegNext(tableIndex(indexWidth - 1, 2))

  weights.io.clock := clock

  weights.io.enable := true.B
  weights.io.address := q0RomAddress
  val romResponse = weights.io.data

  val q1Valid = RegNext(q0Valid && !io.flush, false.B)
  val q1Pc = RegNext(q0Pc)
  val q1History = RegNext(q0History)
  val q1Path = RegNext(q0Path)
  val q1Token = RegNext(q0Token)
  val q1Epoch = RegNext(q0Epoch)
  val q1BankPermutation = RegNext(q0BankPermutation)
  val q1FastValid = RegNext(io.fast.valid && !io.flush, false.B)
  val q1Fast = RegNext(io.fast.bits)

  val postHistoryByWay = Wire(Vec(fetchWidth + 1, UInt(historyWidth.W)))
  val postPathByWay = Wire(Vec(fetchWidth + 1, UInt(pathWidth.W)))
  postHistoryByWay(0) := q0History
  postPathByWay(0) := q0Path
  val feedbackPhysicalBanks = VecInit((0 until fetchWidth).map { way =>
    (q0Pc(3, 2) + way.U)(1, 0)
  })
  val stateConditionalMask = io.fast.bits.stateConditionalMask
  val branchWordLows = VecInit((0 until fetchWidth).map { way =>
    Cat(q0Pc(11, 4), feedbackPhysicalBanks(way))
  })
  for (way <- 0 until fetchWidth) {
    val conditional = stateConditionalMask(way)
    val taken = io.fast.bits.takenMask(way)
    val branchPc = q0Pc + (way * 4).U
    postHistoryByWay(way + 1) := Mux(
      conditional,
      Cat(postHistoryByWay(way)(historyWidth - 2, 0), taken),
      postHistoryByWay(way)
    )
    postPathByWay(way + 1) := Mux(
      conditional,
      (postPathByWay(way) << 1) ^ (branchPc >> 2),
      postPathByWay(way)
    )
  }
  io.fastPostHistory := postHistoryByWay(fetchWidth)
  io.fastPostPath := postPathByWay(fetchWidth)

  val conditionalCount = PopCount(io.indexStateConditionalMask)
  val shiftedIndexState =
    (q0IndexState << conditionalCount)(indexWidth - 1, 0)
  val indexContributions = (0 until fetchWidth).map { way =>
    val laterCount =
      if (way == fetchWidth - 1) 0.U
      else PopCount(io.indexStateConditionalMask(fetchWidth - 1, way + 1))
    val shiftedPc = (branchWordLows(way) << laterCount)(indexWidth - 1, 0)
    val takenWide =
      Cat(0.U((indexWidth - 1).W), io.indexStateTakenMask(way))
    val shiftedTaken =
      (takenWide << laterCount)(indexWidth - 1, 0)
    Mux(
      io.indexStateConditionalMask(way),
      shiftedPc ^ shiftedTaken,
      0.U(indexWidth.W)
    )
  }
  val combinedIndexContribution =
    (indexContributions(0) ^ indexContributions(1)) ^
      (indexContributions(2) ^ indexContributions(3))
  io.fastPostIndexState := shiftedIndexState ^ combinedIndexContribution

  when(io.fast.valid) {
    assert(
      io.indexStateConditionalMask === stateConditionalMask,
      "H64 index-state conditional mask must match the fast state mask"
    )
    assert(
      io.indexStateTakenMask === io.fast.bits.takenMask,
      "H64 index-state taken mask must match the fast taken mask"
    )
  }

  when(q0Valid) {
    assert(
      q0IndexState ===
        (q0History(indexWidth - 1, 0) ^ q0Path(indexWidth - 1, 0)),
      "H64 combined index state must match the full history/path snapshots"
    )
  }

  val modelRows = VecInit((0 until bankCount).map { bank =>
    romResponse((bank + 1) * entryWidth - 1, bank * entryWidth)
  })
  private val historyTermsPerPartial = 4

  private val firstHalfHistoryTerms = 33 - 2
  private val historyGroups =
    (0 until firstHalfHistoryTerms).grouped(historyTermsPerPartial).toSeq ++
      (firstHalfHistoryTerms until historyWidth).grouped(historyTermsPerPartial).toSeq
  private val historyPartialCount = historyGroups.length
  private val partialCount = historyPartialCount + 1
  val q1ConditionalMask = q1Fast.conditionalMask & q1Fast.instructionMask

  private def calculatePartials(row: UInt, fastTaken: Bool): Vec[SInt] = {
    val calculated = Wire(Vec(partialCount, SInt(scoreWidth.W)))
    for ((historyIndices, group) <- historyGroups.zipWithIndex) {
      val terms = historyIndices.map { historyIndex =>
        val relativeHistory = q1History(historyIndex) ^ fastTaken
        signedFeatureTerm(weight(row, historyIndex + 2), relativeHistory)
      }
      calculated(group) := balancedSum(terms)
    }
    val relativeDirection = !fastTaken
    val tail = Seq(
      weight(row, 0).pad(scoreWidth),
      signedFeatureTerm(weight(row, 1), relativeDirection)
    )
    calculated(historyPartialCount) := balancedSum(tail)
    calculated
  }

  private def captureMetadata(
      metadata: H64CorrectorResult,
      conditionalMask: UInt
  ): Unit = {
    metadata.pc := q1Pc
    metadata.token := q1Token
    metadata.epoch := q1Epoch
    metadata.history := q1History
    metadata.path := q1Path
    metadata.conditionalMask := conditionalMask
    metadata.fastTakenMask := q1Fast.takenMask
    metadata.neuralTakenMask := 0.U
    metadata.reliableMask := 0.U
    metadata.scores.foreach(_ := 0.S)
    metadata.phtCounters := q1Fast.phtCounters
    metadata.directionBias := q1Fast.directionBias
  }

  val calculatedPartials = Wire(
    Vec(bankCount, Vec(partialCount, SInt(scoreWidth.W)))
  )
  for (modelBank <- 0 until bankCount) {
    val physicalBank = modelBank.U ^ q1BankPermutation
    val wayForModelBank = (physicalBank - q1Pc(3, 2))(1, 0)
    calculatedPartials(modelBank) := calculatePartials(
      modelRows(modelBank),
      q1Fast.takenMask(wayForModelBank)
    )
  }

  val partialValid = RegInit(false.B)
  val partials = Reg(Vec(bankCount, Vec(partialCount, SInt(scoreWidth.W))))
  val partialBankPermutation = Reg(UInt(log2Ceil(bankCount).W))
  val partialMetadata = Reg(new H64CorrectorResult)
  partialValid := q1Valid && q1FastValid
  when(q1Valid && q1FastValid) {
    assert(q1Fast.token === q1Token, "H64 fast metadata token mismatch")
    assert(q1Fast.epoch === q1Epoch, "H64 fast metadata epoch mismatch")
    partials := calculatedPartials
    partialBankPermutation := q1BankPermutation
    captureMetadata(partialMetadata, q1ConditionalMask)
  }

  val resultValid = RegInit(false.B)
  val resultBits = Reg(new H64CorrectorResult)
  resultValid := partialValid
  when(partialValid) {
    resultBits := partialMetadata
    val modelScores = VecInit((0 until bankCount).map { modelBank =>
      balancedSum(partials(modelBank))
    })
    for (way <- 0 until fetchWidth) {
      val physicalBank = (partialMetadata.pc(3, 2) + way.U)(1, 0)
      val modelBank = physicalBank ^ partialBankPermutation
      resultBits.scores(way) := modelScores(modelBank)
    }
  }
  when(io.flush) {
    partialValid := false.B
    resultValid := false.B
  }

  val outputBits = WireDefault(resultBits)
  val neuralTaken = Wire(Vec(fetchWidth, Bool()))
  val reliable = Wire(Vec(fetchWidth, Bool()))
  io.result.valid := resultValid
  for (way <- 0 until fetchWidth) {
    val score = resultBits.scores(way)
    val overrideFast = score >= marginThreshold.S
    neuralTaken(way) := resultBits.fastTakenMask(way) ^ overrideFast
    reliable(way) := overrideFast
  }
  outputBits.neuralTakenMask := neuralTaken.asUInt
  outputBits.reliableMask := reliable.asUInt
  io.result.bits := outputBits
}
