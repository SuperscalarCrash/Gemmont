package gemmont.top

import chisel3._
import chisel3.util.{Cat, MuxCase}
import gemmont.core.GemmontCoreDebug
import gemmont.isa.{LoadStoreOp, TlbOperation}
import gemmont.privilege.{DirectMapWindow, TlbPage}

private[top] final class DifftestCommitObservation extends Bundle {
  val index = UInt(8.W)
  val valid = Bool()
  val pc = UInt(64.W)
  val instr = UInt(32.W)
  val skip = Bool()
  val isTlbFill = Bool()
  val tlbFillIndex = UInt(5.W)
  val isCntInst = Bool()
  val timer64Value = UInt(64.W)
  val wen = Bool()
  val wdest = UInt(8.W)
  val wdata = UInt(64.W)
  val csrRstat = Bool()
  val csrData = UInt(32.W)
}

private[top] final class DifftestExceptionObservation extends Bundle {
  val valid = Bool()
  val eret = Bool()
  val intrNo = UInt(32.W)
  val cause = UInt(32.W)
  val pc = UInt(64.W)
  val instruction = UInt(32.W)
}

private[top] final class DifftestStoreObservation extends Bundle {
  val valid = UInt(8.W)
  val physicalAddress = UInt(64.W)
  val virtualAddress = UInt(64.W)
  val data = UInt(64.W)
}

private[top] final class DifftestLoadObservation extends Bundle {
  val valid = UInt(8.W)
  val physicalAddress = UInt(64.W)
  val virtualAddress = UInt(64.W)
}

private[top] final class DifftestRetirementObservation extends Bundle {
  val commits = Vec(3, new DifftestCommitObservation)
  val exception = new DifftestExceptionObservation
  val store = new DifftestStoreObservation
  val load = new DifftestLoadObservation
}

private[top] final class DifftestCsrObservation extends Bundle {
  val crmd = UInt(64.W)
  val prmd = UInt(64.W)
  val euen = UInt(64.W)
  val ecfg = UInt(64.W)
  val estat = UInt(64.W)
  val era = UInt(64.W)
  val badv = UInt(64.W)
  val eentry = UInt(64.W)
  val tlbidx = UInt(64.W)
  val tlbehi = UInt(64.W)
  val tlbelo0 = UInt(64.W)
  val tlbelo1 = UInt(64.W)
  val asid = UInt(64.W)
  val pgdl = UInt(64.W)
  val pgdh = UInt(64.W)
  val save0 = UInt(64.W)
  val save1 = UInt(64.W)
  val save2 = UInt(64.W)
  val save3 = UInt(64.W)
  val tid = UInt(64.W)
  val tcfg = UInt(64.W)
  val tval = UInt(64.W)
  val ticlr = UInt(64.W)
  val llbctl = UInt(64.W)
  val tlbrentry = UInt(64.W)
  val dmw0 = UInt(64.W)
  val dmw1 = UInt(64.W)
}

private[top] final class DifftestObservation extends Bundle {
  val retirement = new DifftestRetirementObservation
  val trapValid = Bool()
  val csr = new DifftestCsrObservation
  val gpr = Vec(32, UInt(32.W))
}

private[top] object ChiplabDifftestAdapter {
  def attach(aclk: Clock, debug: GemmontCoreDebug): Unit = {
    val current = Wire(new DifftestObservation)
    val retire = debug.retire

    for (lane <- 0 until 3) {
      val entry = retire.entries(lane).bits
      val microOp = entry.info.microOp
      val state = entry.state
      val commit = current.retirement.commits(lane)

      commit.index := lane.U

      commit.valid := retire.fire(lane) && !debug.exception.valid
      commit.pc := microOp.pc
      commit.instr := microOp.instruction
      commit.skip := false.B
      commit.isTlbFill := (lane == 0).B && microOp.tlbOperation === TlbOperation.Fill
      commit.tlbFillIndex := Mux((lane == 0).B, debug.tlbVictimIndex, 0.U)
      commit.isCntInst := microOp.readTimerLow || microOp.readTimerHigh || microOp.readTimerId
      commit.timer64Value := state.counterValue
      commit.wen := microOp.writeRegister
      commit.wdest := microOp.writebackAddress
      commit.wdata := retire.writeData(lane)
      commit.csrRstat := state.csrStatusRead
      commit.csrData := state.csrReadData
    }

    val entry0 = retire.entries(0).bits
    val state0 = entry0.state
    val microOp0 = entry0.info.microOp
    val exception = current.retirement.exception
    exception.valid := debug.exception.valid
    exception.eret := debug.ertn
    exception.intrNo := debug.privilege.estat(12, 2)
    exception.cause := debug.exception.bits.code
    exception.pc := microOp0.pc
    exception.instruction := microOp0.instruction

    val noException = !debug.exception.valid
    val storeRetired = retire.fire(0) && microOp0.isStore && noException
    val storeConditionalSuccess = microOp0.isStoreConditional && state0.integerResult(0)
    val storeMask = Cat(
      0.U(4.W),
      storeConditionalSuccess,
      !microOp0.isStoreConditional && state0.loadStoreOperation === LoadStoreOp.Word,
      state0.loadStoreOperation === LoadStoreOp.Half,
      state0.loadStoreOperation === LoadStoreOp.Byte
    )
    val store = current.retirement.store
    store.valid := Mux(storeRetired, storeMask, 0.U)
    store.physicalAddress := state0.physicalAddress
    store.virtualAddress := state0.virtualAddress
    store.data := state0.storeData

    val loadRetired = retire.fire(0) && microOp0.isLoad && noException
    val loadMask = Cat(
      0.U(2.W),
      microOp0.isLoadLinked,
      state0.loadStoreOperation === LoadStoreOp.Word,
      state0.loadStoreOperation === LoadStoreOp.HalfUnsigned,
      state0.loadStoreOperation === LoadStoreOp.Half,
      state0.loadStoreOperation === LoadStoreOp.ByteUnsigned,
      state0.loadStoreOperation === LoadStoreOp.Byte
    )
    val load = current.retirement.load
    load.valid := Mux(loadRetired, loadMask, 0.U)
    load.physicalAddress := state0.physicalAddress
    load.virtualAddress := state0.virtualAddress

    val privilege = debug.privilege
    val tlb = debug.tlb
    def packPage(page: TlbPage, global: Bool): UInt = Cat(
      0.U(4.W),
      page.physicalPageNumber,
      0.U(1.W),
      global,
      page.memoryAttribute,
      page.privilege,
      page.dirty,
      page.valid
    )
    def packWindow(window: DirectMapWindow): UInt = Cat(
      window.virtualSegment,
      0.U(1.W),
      window.physicalSegment,
      0.U(19.W),
      window.memoryAttribute,
      window.privilege3,
      0.U(2.W),
      window.privilege0
    )
    val csr = current.csr
    csr.crmd := privilege.crmd
    csr.prmd := privilege.prmd
    csr.euen := privilege.euen
    csr.ecfg := privilege.ecfg
    csr.estat := privilege.estat
    csr.era := privilege.era
    csr.badv := privilege.badv
    csr.eentry := privilege.eentry
    csr.tlbidx := Cat(tlb.notExist, 0.U(1.W), tlb.pageSize, 0.U(19.W), tlb.index)
    csr.tlbehi := Cat(tlb.virtualPageNumber, 0.U(13.W))
    csr.tlbelo0 := packPage(tlb.page0, tlb.global0)
    csr.tlbelo1 := packPage(tlb.page1, tlb.global1)
    csr.asid := Cat(0.U(8.W), 10.U(8.W), 0.U(6.W), tlb.asid)
    csr.pgdl := Cat(tlb.pageDirectoryLow, 0.U(12.W))
    csr.pgdh := Cat(tlb.pageDirectoryHigh, 0.U(12.W))
    csr.save0 := privilege.save(0)
    csr.save1 := privilege.save(1)
    csr.save2 := privilege.save(2)
    csr.save3 := privilege.save(3)
    csr.tid := privilege.tid
    csr.tcfg := privilege.tcfg
    csr.tval := privilege.tval
    csr.ticlr := privilege.ticlr
    csr.llbctl := privilege.llbctl
    csr.tlbrentry := Cat(tlb.tlbRefillEntry, 0.U(6.W))
    csr.dmw0 := packWindow(tlb.directMap0)
    csr.dmw1 := packWindow(tlb.directMap1)

    current.trapValid := false.B
    current.gpr(0) := 0.U
    for (index <- 1 until 32) {
      val physicalAddress = debug.architecturalRat(index - 1)
      current.gpr(index) := Mux(
        physicalAddress === 0.U,
        0.U,
        debug.physicalRegisters(physicalAddress - 1.U)
      )
    }

    val delayed = RegNext(
      current.retirement,
      0.U.asTypeOf(chiselTypeOf(current.retirement))
    )

    for (lane <- 0 until 3) {
      val observation = delayed.commits(lane)
      val commit = Module(new MycpuDifftestInstrCommit).io
      commit.clock := aclk
      commit.coreid := 0.U
      commit.index := observation.index
      commit.valid := observation.valid
      commit.pc := observation.pc
      commit.instr := observation.instr
      commit.skip := observation.skip
      commit.isTLBFill := observation.isTlbFill
      commit.tlbFillIndex := observation.tlbFillIndex
      commit.isCntInst := observation.isCntInst
      commit.timer64Value := observation.timer64Value
      commit.wen := observation.wen
      commit.wdest := observation.wdest
      commit.wdata := observation.wdata
      commit.csrRstat := observation.csrRstat
      commit.csrData := observation.csrData
    }

    val exceptionEvent = Module(new MycpuDifftestExcpEvent).io
    exceptionEvent.clock := aclk
    exceptionEvent.coreid := 0.U
    exceptionEvent.excpValid := delayed.exception.valid
    exceptionEvent.eret := delayed.exception.eret
    exceptionEvent.intrNo := delayed.exception.intrNo
    exceptionEvent.cause := delayed.exception.cause
    exceptionEvent.exceptionPc := delayed.exception.pc
    exceptionEvent.exceptionInst := delayed.exception.instruction

    val storeEvent = Module(new MycpuDifftestStoreEvent).io
    storeEvent.clock := aclk
    storeEvent.coreid := 0.U
    storeEvent.index := 0.U
    val storeValid = delayed.store.valid
    val storeData = delayed.store.data
    val storeAddress = delayed.store.physicalAddress
    val byteMask = ("hff".U(32.W) << (storeAddress(1, 0) << 3))(31, 0)
    val halfMask = ("hffff".U(32.W) << (storeAddress(1) << 4))(31, 0)
    storeEvent.valid := storeValid
    storeEvent.storePAddr := storeAddress
    storeEvent.storeVAddr := delayed.store.virtualAddress
    storeEvent.storeData := MuxCase(
      storeData,
      Seq(
        storeValid(0) -> Cat(0.U(32.W), storeData(31, 0) & byteMask),
        storeValid(1) -> Cat(0.U(32.W), storeData(31, 0) & halfMask),
        (storeValid(2) || storeValid(3)) -> Cat(0.U(32.W), storeData(31, 0))
      )
    )

    val loadEvent = Module(new MycpuDifftestLoadEvent).io
    loadEvent.clock := aclk
    loadEvent.coreid := 0.U
    loadEvent.index := 0.U
    loadEvent.valid := delayed.load.valid
    loadEvent.paddr := delayed.load.physicalAddress
    loadEvent.vaddr := delayed.load.virtualAddress

    val trap = Module(new MycpuDifftestTrapEvent).io
    trap.clock := aclk
    trap.coreid := 0.U
    trap.valid := current.trapValid
    trap.code := 0.U
    trap.pc := 0.U
    trap.cycleCnt := 0.U
    trap.instrCnt := 0.U

    val csrState = Module(new MycpuDifftestCSRRegState).io
    csrState.clock := aclk
    csrState.coreid := 0.U
    csrState.crmd := current.csr.crmd
    csrState.prmd := current.csr.prmd
    csrState.euen := current.csr.euen
    csrState.ecfg := current.csr.ecfg
    csrState.estat := current.csr.estat
    csrState.era := current.csr.era
    csrState.badv := current.csr.badv
    csrState.eentry := current.csr.eentry
    csrState.tlbidx := current.csr.tlbidx
    csrState.tlbehi := current.csr.tlbehi
    csrState.tlbelo0 := current.csr.tlbelo0
    csrState.tlbelo1 := current.csr.tlbelo1
    csrState.asid := current.csr.asid
    csrState.pgdl := current.csr.pgdl
    csrState.pgdh := current.csr.pgdh
    csrState.save0 := current.csr.save0
    csrState.save1 := current.csr.save1
    csrState.save2 := current.csr.save2
    csrState.save3 := current.csr.save3
    csrState.tid := current.csr.tid
    csrState.tcfg := current.csr.tcfg
    csrState.tval := current.csr.tval
    csrState.ticlr := current.csr.ticlr
    csrState.llbctl := current.csr.llbctl
    csrState.tlbrentry := current.csr.tlbrentry
    csrState.dmw0 := current.csr.dmw0
    csrState.dmw1 := current.csr.dmw1

    val gpr = Module(new MycpuDifftestGRegState).io
    gpr.clock := aclk
    gpr.coreid := 0.U
    for (index <- 0 until 32) {
      gpr.gpr(index) := current.gpr(index)
    }
  }
}
