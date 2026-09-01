package gemmont.isa

import chisel3._

final case class InstructionPattern private (value: BigInt, mask: BigInt) {
  def matches(instruction: UInt): Bool =
    (instruction & mask.U(32.W)) === value.U(32.W)
}

object InstructionPattern {
  def apply(pattern: String): InstructionPattern = {
    val compact = pattern.filterNot(_.isWhitespace).map {
      case '-' | '?'         => '?'
      case bit @ ('0' | '1') => bit
      case other =>
        throw new IllegalArgumentException(s"invalid instruction pattern character: $other")
    }
    require(compact.length == 32, s"instruction pattern has ${compact.length} bits, expected 32")
    val value = BigInt(compact.map { case '?' => '0'; case bit => bit }, 2)
    val mask = BigInt(compact.map { case '?' => '0'; case _ => '1' }, 2)
    new InstructionPattern(value, mask)
  }
}

object LoongArch {
  private val R = "-----"
  private def any(width: Int): String = "-" * width
  private def p(bits: String): InstructionPattern = InstructionPattern(bits)

  val Add = p("00000000000100000" + R + R + R)
  val Sub = p("00000000000100010" + R + R + R)
  val Slt = p("00000000000100100" + R + R + R)
  val Sltu = p("00000000000100101" + R + R + R)
  val Nor = p("00000000000101000" + R + R + R)
  val And = p("00000000000101001" + R + R + R)
  val Or = p("00000000000101010" + R + R + R)
  val Xor = p("00000000000101011" + R + R + R)
  val Sll = p("00000000000101110" + R + R + R)
  val Srl = p("00000000000101111" + R + R + R)
  val Sra = p("00000000000110000" + R + R + R)

  val Slli = p("00000000010000001" + any(5) + R + R)
  val Srli = p("00000000010001001" + any(5) + R + R)
  val Srai = p("00000000010010001" + any(5) + R + R)
  val Slti = p("0000001000" + any(12) + R + R)
  val Sltui = p("0000001001" + any(12) + R + R)
  val Addi = p("0000001010" + any(12) + R + R)
  val Andi = p("0000001101" + any(12) + R + R)
  val Ori = p("0000001110" + any(12) + R + R)
  val Xori = p("0000001111" + any(12) + R + R)

  val Lu12i = p("0001010" + any(20) + R)
  val PcAddi = p("0001100" + any(20) + R)
  val PcAddu12i = p("0001110" + any(20) + R)

  val Jirl = p("010011" + any(16) + R + R)
  val B = p("010100" + any(26))
  val Bl = p("010101" + any(26))
  val Beq = p("010110" + any(16) + R + R)
  val Bne = p("010111" + any(16) + R + R)
  val Blt = p("011000" + any(16) + R + R)
  val Bge = p("011001" + any(16) + R + R)
  val Bltu = p("011010" + any(16) + R + R)
  val Bgeu = p("011011" + any(16) + R + R)

  val Mul = p("00000000000111000" + R + R + R)
  val Mulh = p("00000000000111001" + R + R + R)
  val Mulhu = p("00000000000111010" + R + R + R)
  val Dp4 = p("00000000000111100" + R + R + R)
  val Div = p("00000000001000000" + R + R + R)
  val Mod = p("00000000001000001" + R + R + R)
  val Divu = p("00000000001000010" + R + R + R)
  val Modu = p("00000000001000011" + R + R + R)

  val LoadByte = p("0010100000" + any(12) + R + R)
  val LoadByteUnsigned = p("0010101000" + any(12) + R + R)
  val LoadHalf = p("0010100001" + any(12) + R + R)
  val LoadHalfUnsigned = p("0010101001" + any(12) + R + R)
  val LoadWord = p("0010100010" + any(12) + R + R)
  val StoreByte = p("0010100100" + any(12) + R + R)
  val StoreHalf = p("0010100101" + any(12) + R + R)
  val StoreWord = p("0010100110" + any(12) + R + R)

  val Ertn = p("00000110010010000011100000000000")
  val Break = p("00000000001010100" + any(15))
  val Syscall = p("00000000001010110" + any(15))
  val Csr = p("00000100" + any(14) + R + R)
  val Idle = p("00000110010010001" + any(15))

  val ReadCounterLow = p("0000000000000000011000" + R + R)
  val ReadCounterHigh = p("0000000000000000011001" + "00000" + R)
  val CpuConfig = p("0000000000000000011011" + R + R)

  val CacheOp = p("0000011000" + any(12) + R + any(5))
  val Preload = p("0010101011" + any(12) + R + any(5))
  val DataBarrier = p("00111000011100100" + any(15))
  val InstBarrier = p("00111000011100101" + any(15))

  val LoadLinked = p("00100000" + any(14) + R + R)
  val StoreConditional = p("00100001" + any(14) + R + R)

  val TlbSearch = p("00000110010010000010100000000000")
  val TlbRead = p("00000110010010000010110000000000")
  val TlbWrite = p("00000110010010000011000000000000")
  val TlbFill = p("00000110010010000011010000000000")
  val InvalidateTlb = p("00000110010010011" + R + R + any(5))

  object CsrAddress {
    val Crmd = 0x0
    val Prmd = 0x1
    val Euen = 0x2
    val Ecfg = 0x4
    val Estat = 0x5
    val Era = 0x6
    val Badv = 0x7
    val Eentry = 0xc
    val Tlbidx = 0x10
    val Tlbehi = 0x11
    val Tlbelo0 = 0x12
    val Tlbelo1 = 0x13
    val Asid = 0x18
    val Pgdl = 0x19
    val Pgdh = 0x1a
    val Pgd = 0x1b
    val Cpuid = 0x20
    val Save0 = 0x30
    val Save1 = 0x31
    val Save2 = 0x32
    val Save3 = 0x33
    val Tid = 0x40
    val Tcfg = 0x41
    val Tval = 0x42
    val Ticlr = 0x44
    val Llbctl = 0x60
    val TlbRentry = 0x88
    val Dmw0 = 0x180
    val Dmw1 = 0x181
  }

  final case class Exception(code: Int, subcode: Int = 0)
  object ExceptionCode {
    val Interrupt = Exception(0x0)
    val PageInvalidLoad = Exception(0x1)
    val PageInvalidStore = Exception(0x2)
    val PageInvalidFetch = Exception(0x3)
    val PageModified = Exception(0x4)
    val PagePrivilege = Exception(0x7)
    val AddressErrorFetch = Exception(0x8)
    val AddressErrorMemory = Exception(0x8, 1)
    val AddressAlignment = Exception(0x9)
    val SystemCall = Exception(0xb)
    val Breakpoint = Exception(0xc)
    val IllegalInstruction = Exception(0xd)
    val PrivilegeInstruction = Exception(0xe)
    val TlbRefill = Exception(0x3f)
  }
}
