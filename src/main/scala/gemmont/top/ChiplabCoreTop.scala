package gemmont.top

import chisel3._
import chisel3.util.RegEnable
import gemmont.GemmontConfig
import gemmont.cache.L2DataCache
import gemmont.common.AxiWriteBuffer
import gemmont.core.GemmontCore
import gemmont.debug.PerformanceProfileConnector

class ChiplabCoreTop(
    config: GemmontConfig = GemmontConfig(),
    phtInitializationFile: String = "src/main/resources/pht-init.hex"
) extends RawModule {
  override def desiredName: String = "core_top"

  val aclk = IO(Input(Clock()))
  val aresetn = IO(Input(Bool()))
  val intrpt = IO(Input(UInt(8.W)))

  val arid = IO(Output(UInt(4.W)))
  val araddr = IO(Output(UInt(32.W)))
  val arlen = IO(Output(UInt(8.W)))
  val arsize = IO(Output(UInt(3.W)))
  val arburst = IO(Output(UInt(2.W)))
  val arlock = IO(Output(UInt(2.W)))
  val arcache = IO(Output(UInt(4.W)))
  val arprot = IO(Output(UInt(3.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))
  val rid = IO(Input(UInt(4.W)))
  val rdata = IO(Input(UInt(32.W)))
  val rresp = IO(Input(UInt(2.W)))
  val rlast = IO(Input(Bool()))
  val rvalid = IO(Input(Bool()))
  val rready = IO(Output(Bool()))

  val awid = IO(Output(UInt(4.W)))
  val awaddr = IO(Output(UInt(32.W)))
  val awlen = IO(Output(UInt(8.W)))
  val awsize = IO(Output(UInt(3.W)))
  val awburst = IO(Output(UInt(2.W)))
  val awlock = IO(Output(UInt(2.W)))
  val awcache = IO(Output(UInt(4.W)))
  val awprot = IO(Output(UInt(3.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))
  val wid = IO(Output(UInt(4.W)))
  val wdata = IO(Output(UInt(32.W)))
  val wstrb = IO(Output(UInt(4.W)))
  val wlast = IO(Output(Bool()))
  val wvalid = IO(Output(Bool()))
  val wready = IO(Input(Bool()))
  val bid = IO(Input(UInt(4.W)))
  val bresp = IO(Input(UInt(2.W)))
  val bvalid = IO(Input(Bool()))
  val bready = IO(Output(Bool()))

  val break_point = IO(Input(Bool()))
  val infor_flag = IO(Input(Bool()))
  val reg_num = IO(Input(UInt(5.W)))
  val ws_valid = IO(Output(Bool()))
  val rf_rdata = IO(Output(UInt(32.W)))
  val debug0_wb_pc = IO(Output(UInt(32.W)))
  val debug0_wb_rf_wen = IO(Output(UInt(4.W)))
  val debug0_wb_rf_wnum = IO(Output(UInt(5.W)))
  val debug0_wb_rf_wdata = IO(Output(UInt(32.W)))
  val debug1_wb_pc = IO(Output(UInt(32.W)))
  val debug1_wb_rf_wen = IO(Output(UInt(4.W)))
  val debug1_wb_rf_wnum = IO(Output(UInt(5.W)))
  val debug1_wb_rf_wdata = IO(Output(UInt(32.W)))

  withClockAndReset(aclk, (!aresetn).asAsyncReset) {
    val core = Module(new GemmontCore(config, phtInitializationFile))
    val uncachedWriteBuffer = Module(
      new AxiWriteBuffer(config.addressWidth, config.dataWidth, config.axiIdWidth, depth = 1024)
    )
    val l2DataCache = Module(new L2DataCache(config.l2Dcache))

    core.io.externalInterrupt := intrpt
    l2DataCache.io.instructionReadReq <> core.io.instructionLineReadReq
    core.io.instructionLineReadResp <> l2DataCache.io.instructionReadResp
    l2DataCache.io.dataReadReq <> core.io.dataLineReadReq
    core.io.dataLineReadResp <> l2DataCache.io.dataReadResp
    l2DataCache.io.dataPrefetchReq <> core.io.dataLinePrefetchReq
    core.io.dataLinePrefetchResp <> l2DataCache.io.dataPrefetchResp
    l2DataCache.io.dataWriteReq <> core.io.dataLineWriteReq
    core.io.dataLineWriteAck <> l2DataCache.io.dataWriteAck
    uncachedWriteBuffer.io.input <> core.io.uncachedAxi
    l2DataCache.io.uncached <> uncachedWriteBuffer.io.output

    val memory = l2DataCache.io.downstream
    AxiPortAdapter.connect(memory, this)
    wid := RegEnable(memory.aw.bits.id, 0.U(4.W), memory.aw.valid)

    ws_valid := false.B
    rf_rdata := 0.U
    debug0_wb_pc := 0.U
    debug0_wb_rf_wen := 0.U
    debug0_wb_rf_wnum := 0.U
    debug0_wb_rf_wdata := 0.U
    debug1_wb_pc := 0.U
    debug1_wb_rf_wen := 0.U
    debug1_wb_rf_wnum := 0.U
    debug1_wb_rf_wdata := 0.U

    ChiplabDifftestAdapter.attach(aclk, core.io.debug)
    PerformanceProfileConnector.attach(
      aclk,
      !aresetn,
      core.io.profile,
      l2DataCache.io.profile,
      l2DataCache.io.interconnectProfile
    )
  }
}
