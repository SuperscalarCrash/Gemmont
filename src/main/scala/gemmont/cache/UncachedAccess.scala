package gemmont.cache

import chisel3._
import chisel3.util._
import gemmont.common.{Axi4, Axi4Master}
import gemmont.isa.LoadStoreOpEncoding

class UncachedAccess extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new UncachedRequest))
    val loadResponse = Decoupled(UInt(32.W))
    val dataCacheWritebackIdle = Input(Bool())
    val axi = new Axi4Master
    val busy = Output(Bool())
  })

  Axi4.setIdle(io.axi)
  io.axi.b.ready := true.B
  io.request.ready := false.B
  io.loadResponse.valid := false.B
  io.loadResponse.bits := 0.U

  val idle :: sendStore :: waitStoreResponse :: waitLoadResponse :: holdLoadResponse :: Nil = Enum(
    5
  )
  val state = RegInit(idle)
  val activeStore = Reg(new UncachedRequest)
  val addressSent = RegInit(false.B)
  val dataSent = RegInit(false.B)
  val writeResponseReceived = RegInit(true.B)
  val loadData = Reg(UInt(32.W))

  when(io.axi.b.fire) { writeResponseReceived := true.B }

  def driveLoadAddress(request: UncachedRequest): Unit = {
    io.axi.ar.valid := io.request.valid
    io.axi.ar.bits.id := 2.U
    io.axi.ar.bits.addr := request.address
    io.axi.ar.bits.len := 0.U
    io.axi.ar.bits.size := LoadStoreOpEncoding.axiSize(request.operation)
    io.axi.ar.bits.burst := Axi4.IncrementingBurst
  }

  def acceptIdleRequest(): Unit = {
    when(io.request.bits.write) {
      io.request.ready := true.B
      when(io.request.fire) {
        activeStore := io.request.bits
        addressSent := false.B
        dataSent := false.B
        writeResponseReceived := false.B
        state := sendStore
      }
    }.otherwise {
      when(io.dataCacheWritebackIdle) {
        driveLoadAddress(io.request.bits)
        io.request.ready := io.axi.ar.ready
        when(io.request.fire) { state := waitLoadResponse }
      }
    }
  }

  switch(state) {
    is(idle) { acceptIdleRequest() }
    is(sendStore) {
      io.axi.aw.valid := !addressSent
      io.axi.aw.bits.id := 2.U
      io.axi.aw.bits.addr := activeStore.address
      io.axi.aw.bits.len := 0.U
      io.axi.aw.bits.size := LoadStoreOpEncoding.axiSize(activeStore.operation)
      io.axi.aw.bits.burst := Axi4.IncrementingBurst
      io.axi.w.valid := !dataSent
      io.axi.w.bits.data := activeStore.data
      io.axi.w.bits.strb := activeStore.byteEnable
      io.axi.w.bits.last := true.B

      when(io.axi.aw.fire) { addressSent := true.B }
      when(io.axi.w.fire) { dataSent := true.B }
      val allSent = (addressSent || io.axi.aw.fire) && (dataSent || io.axi.w.fire)
      when(allSent) {
        state := Mux(writeResponseReceived || io.axi.b.fire, idle, waitStoreResponse)
      }
    }
    is(waitStoreResponse) {

      when(io.axi.b.fire) {
        acceptIdleRequest()
        when(!io.request.fire) { state := idle }
      }
    }
    is(waitLoadResponse) {
      io.axi.r.ready := true.B
      when(io.axi.r.fire && io.axi.r.bits.last) {
        loadData := io.axi.r.bits.data
        state := holdLoadResponse
      }
    }
    is(holdLoadResponse) {
      io.loadResponse.valid := true.B
      io.loadResponse.bits := loadData
      when(io.loadResponse.fire) { state := idle }
    }
  }

  io.busy := state =/= idle || !writeResponseReceived
}
