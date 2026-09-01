package gemmont.backend.execute

import chisel3._
import chisel3.util._
import gemmont.DesignParams
import gemmont.isa.FunctionalUnit

class MulDivRequest extends Bundle {
  val operation = FunctionalUnit()
  val signed = Bool()
  val source1 = UInt(32.W)
  val source2 = UInt(32.W)
  val robIndex = UInt(5.W)
  val writeRegister = Bool()
  val writePhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class MulDivResponse extends Bundle {
  val result = UInt(32.W)
  val robIndex = UInt(5.W)
  val writeRegister = Bool()
  val writePhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class MultiplyPipelineRecord extends Bundle {
  val request = new MulDivRequest
  val negative = Bool()
}

class MultiplyProductRecord extends Bundle {
  val magnitude = UInt(64.W)
  val operation = FunctionalUnit()
  val negative = Bool()
  val robIndex = UInt(5.W)
  val writeRegister = Bool()
  val writePhysical = UInt(DesignParams.physicalRegisterAddressWidth.W)
}

class MulDivUnit extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new MulDivRequest))
    val response = Decoupled(new MulDivResponse)
    val flush = Input(Bool())
    val empty = Output(Bool())
    val wakeup = Output(
      Valid(UInt(DesignParams.physicalRegisterAddressWidth.W))
    )
  })

  val idle :: divide :: response :: Nil = Enum(3)
  val state = RegInit(idle)
  val divisionRequest = Reg(new MulDivRequest)

  val responsePayload = Reg(new MulDivResponse)
  val source1Negative = Reg(Bool())
  val source2Negative = Reg(Bool())
  val smallDivision = Reg(Bool())

  val source1Signed = io.request.bits.source1.asSInt
  val source2Signed = io.request.bits.source2.asSInt
  val absolute1 = Mux(
    io.request.bits.signed && source1Signed < 0.S,
    (-source1Signed).asUInt,
    io.request.bits.source1
  )
  val absolute2 = Mux(
    io.request.bits.signed && source2Signed < 0.S,
    (-source2Signed).asUInt,
    io.request.bits.source2
  )
  private def magnitudeFits16(source: UInt): Bool = {
    val upper = source(31, 16)
    val unsignedOrPositiveFits = upper === 0.U

    val negativeFits = upper.andR && source(15, 0).orR
    unsignedOrPositiveFits || (io.request.bits.signed && negativeFits)
  }
  val useSmallDivider =
    magnitudeFits16(io.request.bits.source1) && magnitudeFits16(io.request.bits.source2)
  val requestIsMultiply =
    io.request.bits.operation === FunctionalUnit.Mul ||
      io.request.bits.operation === FunctionalUnit.MulHigh

  val multiplyInputValid = RegInit(false.B)
  val multiplyInput = Reg(new MultiplyPipelineRecord)
  val multiplyProductValid = RegInit(false.B)
  val multiplyProduct = Reg(new MultiplyProductRecord)
  val multiplyOutputValid = RegInit(false.B)
  val multiplyOutputReady = !multiplyOutputValid || io.response.ready
  val multiplyProductReady = !multiplyProductValid || multiplyOutputReady
  val multiplyInputReady = !multiplyInputValid || multiplyProductReady

  io.empty := state === idle && !multiplyInputValid &&
    !multiplyProductValid && !multiplyOutputValid

  io.request.ready := Mux(
    requestIsMultiply,
    state === idle && multiplyInputReady,
    io.empty
  )
  io.response.valid := multiplyOutputValid || state === response
  io.response.bits := responsePayload
  io.wakeup.bits := Mux(
    multiplyProductValid,
    multiplyProduct.writePhysical,
    divisionRequest.writePhysical
  )

  val divider32 = Module(new UnsignedDivider(32))
  val divider16 = Module(new UnsignedDivider(16))
  divider32.io.flush := io.flush
  divider16.io.flush := io.flush
  val divisionRequestFire = io.request.fire && !requestIsMultiply
  divider32.io.command.valid := divisionRequestFire && !useSmallDivider
  divider16.io.command.valid := divisionRequestFire && useSmallDivider
  divider32.io.command.bits.numerator :=
    Mux(state === idle, absolute1, divisionRequest.source1)
  divider32.io.command.bits.denominator :=
    Mux(state === idle, absolute2, divisionRequest.source2)
  divider16.io.command.bits.numerator :=
    Mux(state === idle, absolute1(15, 0), divisionRequest.source1(15, 0))
  divider16.io.command.bits.denominator :=
    Mux(state === idle, absolute2(15, 0), divisionRequest.source2(15, 0))
  divider32.io.response.ready := state === divide && !smallDivision
  divider16.io.response.ready := state === divide && smallDivision

  when(multiplyOutputReady) {
    multiplyOutputValid := multiplyProductValid
    when(multiplyProductValid) {
      val product = Mux(
        multiplyProduct.negative,
        -multiplyProduct.magnitude,
        multiplyProduct.magnitude
      )
      responsePayload.result := Mux(
        multiplyProduct.operation === FunctionalUnit.MulHigh,
        product(63, 32),
        product(31, 0)
      )
      responsePayload.robIndex := multiplyProduct.robIndex
      responsePayload.writeRegister := multiplyProduct.writeRegister
      responsePayload.writePhysical := multiplyProduct.writePhysical
    }
  }

  when(multiplyProductReady) {
    multiplyProductValid := multiplyInputValid
    when(multiplyInputValid) {
      multiplyProduct.magnitude :=
        multiplyInput.request.source1 * multiplyInput.request.source2
      multiplyProduct.operation := multiplyInput.request.operation
      multiplyProduct.negative := multiplyInput.negative
      multiplyProduct.robIndex := multiplyInput.request.robIndex
      multiplyProduct.writeRegister := multiplyInput.request.writeRegister
      multiplyProduct.writePhysical := multiplyInput.request.writePhysical
    }
    multiplyInputValid := false.B
  }

  when(io.request.fire && requestIsMultiply) {
    multiplyInputValid := true.B
    multiplyInput.request := io.request.bits
    multiplyInput.request.source1 := absolute1
    multiplyInput.request.source2 := absolute2
    multiplyInput.negative := io.request.bits.signed &&
      (io.request.bits.source1(31) ^ io.request.bits.source2(31))
  }

  when(divisionRequestFire) {
    divisionRequest := io.request.bits
    divisionRequest.source1 := absolute1
    divisionRequest.source2 := absolute2
    source1Negative := io.request.bits.source1(31)
    source2Negative := io.request.bits.source2(31)
    smallDivision := useSmallDivider
    state := divide
  }

  val selectedValid = Mux(smallDivision, divider16.io.response.valid, divider32.io.response.valid)

  io.wakeup.valid :=
    (multiplyProductValid && multiplyOutputReady &&
      multiplyProduct.writeRegister) ||
      (state === divide && selectedValid && divisionRequest.writeRegister)
  val quotientMagnitude = Mux(
    smallDivision,
    Cat(0.U(16.W), divider16.io.response.bits.quotient),
    divider32.io.response.bits.quotient
  )
  val remainderMagnitude = Mux(
    smallDivision,
    Cat(0.U(16.W), divider16.io.response.bits.remainder),
    divider32.io.response.bits.remainder
  )
  when(state === divide && selectedValid) {
    val quotientNegative = divisionRequest.signed &&
      (source1Negative ^ source2Negative)
    val remainderNegative = divisionRequest.signed && source1Negative
    val quotient = Mux(quotientNegative, -quotientMagnitude, quotientMagnitude)
    val remainder = Mux(remainderNegative, -remainderMagnitude, remainderMagnitude)
    responsePayload.result := Mux(
      divisionRequest.operation === FunctionalUnit.Div,
      quotient,
      remainder
    )
    responsePayload.robIndex := divisionRequest.robIndex
    responsePayload.writeRegister := divisionRequest.writeRegister
    responsePayload.writePhysical := divisionRequest.writePhysical
    state := response
  }

  when(state === response && io.response.fire) {
    state := idle
  }
  when(io.flush) {
    state := idle
    multiplyInputValid := false.B
    multiplyProductValid := false.B
    multiplyOutputValid := false.B
  }
}
