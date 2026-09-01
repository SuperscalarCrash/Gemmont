package gemmont.top

import chisel3._

class DifftestInstrCommitIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val index = Input(UInt(8.W))
  val valid = Input(Bool())
  val pc = Input(UInt(64.W))
  val instr = Input(UInt(32.W))
  val skip = Input(Bool())
  val isTLBFill = Input(Bool())
  val tlbFillIndex = Input(UInt(5.W))
  val isCntInst = Input(Bool())
  val timer64Value = Input(UInt(64.W))
  val wen = Input(Bool())
  val wdest = Input(UInt(8.W))
  val wdata = Input(UInt(64.W))
  val csrRstat = Input(Bool())
  val csrData = Input(UInt(32.W))
}

class DifftestExcpEventIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val excpValid = Input(Bool())
  val eret = Input(Bool())
  val intrNo = Input(UInt(32.W))
  val cause = Input(UInt(32.W))
  val exceptionPc = Input(UInt(64.W))
  val exceptionInst = Input(UInt(32.W))
}

class DifftestTrapEventIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val valid = Input(Bool())
  val code = Input(UInt(3.W))
  val pc = Input(UInt(64.W))
  val cycleCnt = Input(UInt(64.W))
  val instrCnt = Input(UInt(64.W))
}

class DifftestStoreEventIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val index = Input(UInt(8.W))
  val valid = Input(UInt(8.W))
  val storePAddr = Input(UInt(64.W))
  val storeVAddr = Input(UInt(64.W))
  val storeData = Input(UInt(64.W))
}

class DifftestLoadEventIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val index = Input(UInt(8.W))
  val valid = Input(UInt(8.W))
  val paddr = Input(UInt(64.W))
  val vaddr = Input(UInt(64.W))
}

class DifftestCsrStateIo extends Bundle {
  val clock = Input(Clock())
  val coreid = Input(UInt(8.W))
  val crmd = Input(UInt(64.W))
  val prmd = Input(UInt(64.W))
  val euen = Input(UInt(64.W))
  val ecfg = Input(UInt(64.W))
  val estat = Input(UInt(64.W))
  val era = Input(UInt(64.W))
  val badv = Input(UInt(64.W))
  val eentry = Input(UInt(64.W))
  val tlbidx = Input(UInt(64.W))
  val tlbehi = Input(UInt(64.W))
  val tlbelo0 = Input(UInt(64.W))
  val tlbelo1 = Input(UInt(64.W))
  val asid = Input(UInt(64.W))
  val pgdl = Input(UInt(64.W))
  val pgdh = Input(UInt(64.W))
  val save0 = Input(UInt(64.W))
  val save1 = Input(UInt(64.W))
  val save2 = Input(UInt(64.W))
  val save3 = Input(UInt(64.W))
  val tid = Input(UInt(64.W))
  val tcfg = Input(UInt(64.W))
  val tval = Input(UInt(64.W))
  val ticlr = Input(UInt(64.W))
  val llbctl = Input(UInt(64.W))
  val tlbrentry = Input(UInt(64.W))
  val dmw0 = Input(UInt(64.W))
  val dmw1 = Input(UInt(64.W))
}

class MycpuDifftestInstrCommit extends BlackBox {
  override def desiredName: String = "MycpuDifftestInstrCommit"
  val io = IO(new DifftestInstrCommitIo)
}

class MycpuDifftestExcpEvent extends BlackBox {
  override def desiredName: String = "MycpuDifftestExcpEvent"
  val io = IO(new DifftestExcpEventIo)
}

class MycpuDifftestTrapEvent extends BlackBox {
  override def desiredName: String = "MycpuDifftestTrapEvent"
  val io = IO(new DifftestTrapEventIo)
}

class MycpuDifftestStoreEvent extends BlackBox {
  override def desiredName: String = "MycpuDifftestStoreEvent"
  val io = IO(new DifftestStoreEventIo)
}

class MycpuDifftestLoadEvent extends BlackBox {
  override def desiredName: String = "MycpuDifftestLoadEvent"
  val io = IO(new DifftestLoadEventIo)
}

class MycpuDifftestCSRRegState extends BlackBox {
  override def desiredName: String = "MycpuDifftestCSRRegState"
  val io = IO(new DifftestCsrStateIo)
}

class MycpuDifftestGRegState extends BlackBox {
  override def desiredName: String = "MycpuDifftestGRegState"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val coreid = Input(UInt(8.W))
    val gpr = Input(Vec(32, UInt(64.W)))
  })
}
