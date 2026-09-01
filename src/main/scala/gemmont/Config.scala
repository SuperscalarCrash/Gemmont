package gemmont

import chisel3.util.log2Ceil

sealed trait CacheConfig {
  def sets: Int
  def lineBytes: Int
  def ways: Int

  final val offsetWidth: Int = log2Ceil(lineBytes)
  final val indexWidth: Int = log2Ceil(sets)
  final val tagOffset: Int = offsetWidth + indexWidth
  final val lineWords: Int = lineBytes / 4

  require(sets > 0 && (sets & (sets - 1)) == 0, "cache sets must be a power of two")
  require(
    lineBytes >= 4 && (lineBytes & (lineBytes - 1)) == 0,
    "cache line size must be a power of two"
  )
  require(ways > 0, "cache must have at least one way")
}

final case class ICacheConfig(
    sets: Int = DesignParams.instructionCacheSets,
    lineBytes: Int = DesignParams.cacheLineBytes,
    ways: Int = DesignParams.cacheWays
) extends CacheConfig {
  require(sets * lineBytes <= 4 * 1024, "4 KiB is the VIPT index+offset limit")
}

final case class DCacheConfig(
    sets: Int = DesignParams.dataCacheSets,
    lineBytes: Int = DesignParams.cacheLineBytes,
    ways: Int = DesignParams.cacheWays,
    useStreamPrefetch: Boolean = true
) extends CacheConfig {
  require(sets * lineBytes <= 4 * 1024, "4 KiB is the VIPT index+offset limit")
}

final case class L2DCacheConfig(
    sets: Int = DesignParams.l2DataCacheSets,
    lineBytes: Int = DesignParams.cacheLineBytes,
    ways: Int = DesignParams.cacheWays
) extends CacheConfig

final case class H64CorrectorConfig(
    enabled: Boolean = true,
    marginThreshold: Int = 1,
    detailedTrace: Boolean = false
) {
  require(marginThreshold >= 0 && marginThreshold < 512)
}

final case class FrontendConfig(
    resetPc: BigInt = DesignParams.resetPc,
    icache: ICacheConfig = ICacheConfig(),
    h64: H64CorrectorConfig = H64CorrectorConfig(),
    fetchWidth: Int = DesignParams.fetchWidth
) {
  require(fetchWidth == 4)
}

final case class TlbConfig(entries: Int = DesignParams.tlbEntries) {
  val indexWidth: Int = log2Ceil(entries)

  require(entries == 32)
}

final case class GemmontConfig(
    addressWidth: Int = DesignParams.addressWidth,
    dataWidth: Int = DesignParams.dataWidth,
    axiIdWidth: Int = DesignParams.axiIdWidth,
    frontend: FrontendConfig = FrontendConfig(),
    dcache: DCacheConfig = DCacheConfig(),
    l2Dcache: L2DCacheConfig = L2DCacheConfig(),
    tlb: TlbConfig = TlbConfig()
) {
  require(addressWidth == 32 && dataWidth == 32 && axiIdWidth == 4)
}
