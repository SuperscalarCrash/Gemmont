package gemmont.frontend

import chisel3._
import chisel3.util.{Cat, Mux1H, log2Ceil}

object BranchPredictorParameters {
  val historyWidth = 8
  val phtEntries = 8192
  val phtIndexWidth = log2Ceil(phtEntries)
  val foldedHistoryWidth = historyWidth
  private val packetBranchCounts = 0 to 4

  def foldHistory(history: UInt): UInt = history

  private def advancedHistories(history: UInt, branchTaken: Bool): Seq[UInt] =
    packetBranchCounts.map { branchCount =>
      ((history << branchCount) | branchTaken.asUInt)(historyWidth - 1, 0)
    }

  private def selectBranchCount(branchCount: UInt, candidates: Seq[UInt]): UInt =
    Mux1H(
      packetBranchCounts.zip(candidates).map { case (count, candidate) =>
        (branchCount === count.U) -> candidate
      }
    )

  def advanceHistory(history: UInt, branchCount: UInt, branchTaken: Bool): UInt =
    selectBranchCount(branchCount, advancedHistories(history, branchTaken))

  def advanceFoldedHistory(
      history: UInt,
      branchCount: UInt,
      branchTaken: Bool
  ): UInt = {
    advanceHistory(history, branchCount, branchTaken)
  }

  def advanceFoldedHistoryByPhysicalLaneWalk(
      history: UInt,
      predictionHits: Seq[Bool],
      takenCandidates: Seq[Bool]
  ): UInt = {
    require(predictionHits.length == 4)
    require(takenCandidates.length == predictionHits.length)

    var active = true.B
    var advanced = history
    for (bank <- predictionHits.indices) {
      val append = active && predictionHits(bank)
      advanced = Mux(
        append,
        Cat(advanced(historyWidth - 2, 0), takenCandidates(bank)),
        advanced
      )
      active = active && !takenCandidates(bank)
    }
    advanced
  }

  def phtAddressFromPcWord(pcWord: UInt, foldedHistory: UInt): UInt =
    Cat(foldedHistory ^ pcWord(12, 5), pcWord(4, 0))

  def phtAddressFromFolded(pc: UInt, foldedHistory: UInt): UInt =
    phtAddressFromPcWord(pc(14, 2), foldedHistory)

  def phtAddress(pc: UInt, history: UInt): UInt =
    phtAddressFromFolded(pc, foldHistory(history))
}
