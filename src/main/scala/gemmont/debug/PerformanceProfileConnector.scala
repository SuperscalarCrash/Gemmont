package gemmont.debug

import chisel3._

object PerformanceProfileConnector {
  def attach(
      clock: Clock,
      reset: Bool,
      core: CoreProfileObservation,
      l2: L2ProfileObservation,
      interconnect: CacheInterconnectProfileObservation
  ): Unit = {
    val sink = Module(new PerformanceCounterSink)
    sink.io.clock := clock
    sink.io.reset := reset

    sink.io.cycle := core.cycle
    sink.io.fetchPc := core.fetchPc
    sink.io.retirePc := core.retirePc
    sink.io.retireCounterValue := core.retireCounterValue
    sink.io.retireValid := core.retireValid
    sink.io.retireCounter := core.retireCounter
    sink.io.branchRetired := core.branchRetired
    sink.io.mispredictRetired := core.mispredictRetired
    sink.io.branchMispredictRetired := core.branchMispredictRetired
    sink.io.otherRecovery := core.otherRecovery
    sink.io.h64LateCorrection := core.h64LateCorrection
    sink.io.h64RenameFire := core.h64RenameFire
    sink.io.h64RenameEvaluated := core.h64RenameEvaluated
    sink.io.h64RenameRobIndex := core.h64RenameRobIndex
    sink.io.h64RenameFastTaken := core.h64RenameFastTaken
    sink.io.h64RenameNeuralTaken := core.h64RenameNeuralTaken
    sink.io.h64RenameReliable := core.h64RenameReliable
    sink.io.h64RenameOverride := core.h64RenameOverride
    sink.io.h64DecodeFire := core.h64DecodeFire
    sink.io.h64DecodeRobIndex := core.h64DecodeRobIndex
    sink.io.h64DecodePc := core.h64DecodePc
    sink.io.h64DecodeToken := core.h64DecodeToken
    sink.io.h64DecodeEpoch := core.h64DecodeEpoch
    sink.io.h64DecodeWay := core.h64DecodeWay
    sink.io.h64DecodeHistory := core.h64DecodeHistory
    sink.io.h64DecodePath := core.h64DecodePath
    sink.io.h64DecodeScore := core.h64DecodeScore
    sink.io.h64DecodePhtCounter := core.h64DecodePhtCounter
    sink.io.h64DecodeFastTaken := core.h64DecodeFastTaken
    sink.io.h64DecodeNeuralTaken := core.h64DecodeNeuralTaken
    sink.io.h64DecodeReliable := core.h64DecodeReliable
    sink.io.h64DecodeOverride := core.h64DecodeOverride
    sink.io.h64DecodeDirectionBias := core.h64DecodeDirectionBias
    sink.io.h64RetireActualTaken := core.h64RetireActualTaken
    sink.io.retireRobIndex := core.retireRobIndex
    sink.io.mispredictResolved := core.mispredictResolved
    sink.io.mispredictResolvedRob := core.mispredictResolvedRob

    sink.io.frontendValid := core.frontendValid
    sink.io.dispatchBlocked := core.dispatchBlocked
    sink.io.robOccupancy := core.robOccupancy
    sink.io.integerIssueOccupancy := core.integerIssueOccupancy
    sink.io.mulDivIssueOccupancy := core.mulDivIssueOccupancy
    sink.io.memoryIssueOccupancy := core.memoryIssueOccupancy
    sink.io.storeBufferOccupancy := core.storeBufferOccupancy
    sink.io.integerIssue := core.integerIssue
    sink.io.mulDivIssue := core.mulDivIssue
    sink.io.memoryIssue := core.memoryIssue
    sink.io.memoryIssueOperandsReady := core.memoryIssueOperandsReady
    sink.io.memoryIssueAddressReady := core.memoryIssueAddressReady
    sink.io.memoryIssueDataReady := core.memoryIssueDataReady
    sink.io.memoryIssueLsuReady := core.memoryIssueLsuReady
    sink.io.memoryIssueHeadPc := core.memoryIssueHeadPc

    sink.io.speculativeWakeupFailed := core.speculativeWakeupFailed
    sink.io.mem2Valid := core.mem2Valid
    sink.io.mem2Pc := core.mem2Pc
    sink.io.mem2CacheWait := core.mem2CacheWait
    sink.io.mem2StoreBufferWait := core.mem2StoreBufferWait
    sink.io.mem2AuxWait := core.mem2AuxWait

    sink.io.instructionCacheRequest := core.instructionCache.request
    sink.io.instructionCacheHit := core.instructionCache.hit
    sink.io.instructionCacheMiss := core.instructionCache.miss
    sink.io.instructionCacheMissBusy := core.instructionCache.missBusy
    sink.io.dataCacheRequest := core.dataCache.request
    sink.io.dataCacheHit := core.dataCache.hit
    sink.io.dataCacheMiss := core.dataCache.miss
    sink.io.dataCacheMissBusy := core.dataCache.missBusy
    sink.io.dataCacheRefillBusy := core.dataCache.refillBusy
    sink.io.dataCachePostResponseRefillBusy := core.dataCache.postResponseRefillBusy
    sink.io.dataCacheDirectRefill := core.dataCache.directRefill
    sink.io.dataCacheEarlyResponse := core.dataCache.earlyResponse
    sink.io.dataCacheDirtyWriteback := core.dataCache.dirtyWriteback
    sink.io.dataCacheDirtyVictim := core.dataCache.dirtyVictim
    sink.io.dataCacheTailBlockedWouldHit := core.dataCache.tailBlockedWouldHit
    sink.io.dataCacheTailBlockedSameFillLine := core.dataCache.tailBlockedSameFillLine
    sink.io.dataCacheTailBlockedNewMiss := core.dataCache.tailBlockedNewMiss
    sink.io.dataCacheTailBlockedStore := core.dataCache.tailBlockedStore
    sink.io.dataCacheDirtyVictimCaptureBusy := core.dataCache.dirtyVictimCaptureBusy
    sink.io.dataCacheDirtyVictimReadAddressWait := core.dataCache.dirtyVictimReadAddressWait
    sink.io.dataCacheDirtyVictimResponseWait := core.dataCache.dirtyVictimResponseWait
    sink.io.dataCacheLoadMiss := core.dataCache.loadMiss
    sink.io.dataCacheStoreMiss := core.dataCache.storeMiss
    sink.io.dataCacheLoadMissPlusOne := core.dataCache.loadMissPlusOne
    sink.io.dataCacheLoadMissMinusOne := core.dataCache.loadMissMinusOne
    sink.io.dataCacheLoadMissRepeat := core.dataCache.loadMissRepeat
    sink.io.dataCachePrefetchCandidate := core.dataCache.prefetchCandidate
    sink.io.dataCachePrefetchRequest := core.dataCache.prefetchRequest
    sink.io.dataCachePrefetchL2Hit := core.dataCache.prefetchL2Hit
    sink.io.dataCachePrefetchL2Miss := core.dataCache.prefetchL2Miss
    sink.io.dataCachePrefetchBufferHit := core.dataCache.prefetchBufferHit
    sink.io.dataCachePrefetchLate := core.dataCache.prefetchLate
    sink.io.dataCachePrefetchDropped := core.dataCache.prefetchDropped
    sink.io.dataCachePrefetchDuplicate := core.dataCache.prefetchDuplicate
    sink.io.dataCachePrefetchPageSuppressed := core.dataCache.prefetchPageSuppressed
    sink.io.dataCachePrefetchCancelled := core.dataCache.prefetchCancelled
    sink.io.dataCachePrefetchUseless := core.dataCache.prefetchUseless

    sink.io.l2InstructionRead := l2.instructionRead
    sink.io.l2InstructionHit := l2.instructionHit
    sink.io.l2InstructionMiss := l2.instructionMiss
    sink.io.l2InstructionMissBusy := l2.instructionMissBusy
    sink.io.l2DataRead := l2.dataRead
    sink.io.l2DataHit := l2.dataHit
    sink.io.l2DataDirectHit := l2.dataDirectHit
    sink.io.l2DataMiss := l2.dataMiss
    sink.io.l2DataMissBusy := l2.dataMissBusy
    sink.io.l2DataWrite := l2.dataWrite
    sink.io.l2DirtyWriteback := l2.dirtyWriteback
    sink.io.l2ReadAddress := l2.readAddress
    sink.io.l2DataWriteAddress := l2.dataWriteAddress
    sink.io.l2ReadMissBusy := l2.readMissBusy
    sink.io.l2WriteBusy := l2.writeBusy
    sink.io.l2DataPrefetchRead := l2.dataPrefetchRead
    sink.io.l2DataPrefetchHit := l2.dataPrefetchHit
    sink.io.l2DataPrefetchMiss := l2.dataPrefetchMiss
    sink.io.l2DataPrefetchWait := l2.dataPrefetchWait
    sink.io.l2InstructionReadWait := interconnect.instructionReadWait
    sink.io.l2InstructionReadWaitAddress := interconnect.instructionReadWaitAddress
    sink.io.l2DataReadWait := interconnect.dataReadWait
    sink.io.l2DataReadWaitAddress := interconnect.dataReadWaitAddress
  }
}
