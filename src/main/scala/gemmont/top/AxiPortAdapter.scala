package gemmont.top

import gemmont.common.Axi4Master

private[top] object AxiPortAdapter {
  def connect(memory: Axi4Master, ports: ChiplabCoreTop): Unit = {
    ports.awvalid := memory.aw.valid
    memory.aw.ready := ports.awready
    ports.awaddr := memory.aw.bits.addr
    ports.awid := memory.aw.bits.id
    ports.awlen := memory.aw.bits.len
    ports.awsize := memory.aw.bits.size
    ports.awburst := memory.aw.bits.burst
    ports.awlock := memory.aw.bits.lock
    ports.awcache := memory.aw.bits.cache
    ports.awprot := memory.aw.bits.prot
    ports.wvalid := memory.w.valid
    memory.w.ready := ports.wready
    ports.wdata := memory.w.bits.data
    ports.wstrb := memory.w.bits.strb
    ports.wlast := memory.w.bits.last
    memory.b.valid := ports.bvalid
    ports.bready := memory.b.ready
    memory.b.bits.id := ports.bid
    memory.b.bits.resp := ports.bresp
    ports.arvalid := memory.ar.valid
    memory.ar.ready := ports.arready
    ports.araddr := memory.ar.bits.addr
    ports.arid := memory.ar.bits.id
    ports.arlen := memory.ar.bits.len
    ports.arsize := memory.ar.bits.size
    ports.arburst := memory.ar.bits.burst
    ports.arlock := memory.ar.bits.lock
    ports.arcache := memory.ar.bits.cache
    ports.arprot := memory.ar.bits.prot
    memory.r.valid := ports.rvalid
    ports.rready := memory.r.ready
    memory.r.bits.data := ports.rdata
    memory.r.bits.id := ports.rid
    memory.r.bits.resp := ports.rresp
    memory.r.bits.last := ports.rlast
  }
}
