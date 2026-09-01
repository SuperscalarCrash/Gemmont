package gemmont.common

import chisel3._
import chisel3.util._

class Axi4Address(addressWidth: Int, idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val addr = UInt(addressWidth.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = Bool()
  val cache = UInt(4.W)
  val prot = UInt(3.W)
}

class Axi4WriteData(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val strb = UInt((dataWidth / 8).W)
  val last = Bool()
}

class Axi4WriteResponse(idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val resp = UInt(2.W)
}

class Axi4ReadData(dataWidth: Int, idWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val data = UInt(dataWidth.W)
  val resp = UInt(2.W)
  val last = Bool()
}

class Axi4Master(addressWidth: Int = 32, dataWidth: Int = 32, idWidth: Int = 4) extends Bundle {
  val aw = Decoupled(new Axi4Address(addressWidth, idWidth))
  val w = Decoupled(new Axi4WriteData(dataWidth))
  val b = Flipped(Decoupled(new Axi4WriteResponse(idWidth)))
  val ar = Decoupled(new Axi4Address(addressWidth, idWidth))
  val r = Flipped(Decoupled(new Axi4ReadData(dataWidth, idWidth)))
}

object Axi4 {
  val Okay: UInt = 0.U(2.W)
  val IncrementingBurst: UInt = 1.U(2.W)

  def setIdle(bus: Axi4Master): Unit = {
    bus.aw.valid := false.B
    bus.aw.bits := 0.U.asTypeOf(bus.aw.bits)
    bus.w.valid := false.B
    bus.w.bits := 0.U.asTypeOf(bus.w.bits)
    bus.b.ready := false.B
    bus.ar.valid := false.B
    bus.ar.bits := 0.U.asTypeOf(bus.ar.bits)
    bus.r.ready := false.B
  }
}
