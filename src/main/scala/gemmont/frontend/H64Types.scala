package gemmont.frontend

import chisel3._

object H64CorrectorParameters {
  val fetchWidth = 4
  val historyWidth = 64
  val pathWidth = 32
  val tableEntries = 1024
  val bankCount = 4
  val rowsPerBank = tableEntries / bankCount
  val indexWidth = chisel3.util.log2Ceil(tableEntries)
  val rowWidth = chisel3.util.log2Ceil(rowsPerBank)
  val weightBits = 4
  val weightsPerEntry = historyWidth + 2
  val entryWidth = weightsPerEntry * weightBits
  val physicalRowWidth = entryWidth * bankCount
  val scoreWidth = 11
  val tokenWidth = 4
  val epochWidth = 3
}

class H64CorrectorQuery extends Bundle {
  val pc = UInt(32.W)
  val history = UInt(H64CorrectorParameters.historyWidth.W)
  val path = UInt(H64CorrectorParameters.pathWidth.W)
  val indexState = UInt(H64CorrectorParameters.indexWidth.W)
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
}

class H64FastMetadata extends Bundle {
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
  val instructionMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val stateConditionalMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val conditionalMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val takenMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val directionBias = UInt(H64CorrectorParameters.fetchWidth.W)
  val phtCounters = Vec(H64CorrectorParameters.fetchWidth, UInt(2.W))
}

class H64CorrectorResult extends Bundle {
  val pc = UInt(32.W)
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val epoch = UInt(H64CorrectorParameters.epochWidth.W)
  val history = UInt(H64CorrectorParameters.historyWidth.W)
  val path = UInt(H64CorrectorParameters.pathWidth.W)
  val conditionalMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val fastTakenMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val neuralTakenMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val reliableMask = UInt(H64CorrectorParameters.fetchWidth.W)
  val scores = Vec(H64CorrectorParameters.fetchWidth, SInt(H64CorrectorParameters.scoreWidth.W))
  val phtCounters = Vec(H64CorrectorParameters.fetchWidth, UInt(2.W))
  val directionBias = UInt(H64CorrectorParameters.fetchWidth.W)
}

class H64CommittedBranch extends Bundle {
  val pc = UInt(32.W)
  val taken = Bool()
}

class LatePredictionCorrection extends Bundle {
  val token = UInt(H64CorrectorParameters.tokenWidth.W)
  val recovery = new PredictionRecovery
  val neuralHistoryBefore = UInt(H64CorrectorParameters.historyWidth.W)
  val neuralPathBefore = UInt(H64CorrectorParameters.pathWidth.W)
  val pc = UInt(32.W)
  val taken = Bool()
}
