package gemmont.isa

import chisel3._

object AluOp extends ChiselEnum {
  val Add, CpuConfig, Sub = Value
  val And, Or, Xor, Nor = Value
  val Slt, Sltu = Value
  val Sll, Srl, Sra = Value
  val Lu12i, PcAddi, PcAddu12i = Value
}

object CompareOp extends ChiselEnum {
  val Eq, Ne = Value
  val Eqz, Nez = Value
  val Ge, Lt, Le, Gt = Value
  val Geu, Ltu, Leu, Gtu = Value
  val Gez, Ltz, Lez, Gtz = Value
}

object FunctionalUnit extends ChiselEnum {
  val None = Value
  val Alu, Compare, Csr, Timer, InvTlb = Value
  val Mul, MulHigh, Div, Mod = Value
  val Dp4 = Value
  val LoadStore = Value
}

object ExtendOp extends ChiselEnum {
  val Sign, Zero = Value
}

object LoadStoreOp extends ChiselEnum {
  val Byte, Half, Word, ByteUnsigned, HalfUnsigned = Value
  val CacheOperation, Preload = Value
}

object CacheSelect extends ChiselEnum {
  val None, Instruction, Data = Value
}

object CacheOperation extends ChiselEnum {
  val None, StoreTag, IndexInvalidate, HitInvalidate = Value
}

object MemoryAccess extends ChiselEnum {
  val Fetch, Load, Store = Value
}

object TlbOperation extends ChiselEnum {
  val None, Search, Read, Write, Fill = Value
  val Invalidate1, Invalidate2, Invalidate3, Invalidate4, Invalidate5, Invalidate6 = Value
}

object LoadStoreOpEncoding {
  def axiSize(operation: LoadStoreOp.Type): UInt = {
    Mux(
      operation === LoadStoreOp.Byte || operation === LoadStoreOp.ByteUnsigned,
      0.U,
      Mux(
        operation === LoadStoreOp.Half || operation === LoadStoreOp.HalfUnsigned,
        1.U,
        2.U
      )
    )
  }
}
