package gemmont.privilege

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import gemmont.{DesignParams, TlbConfig}
import gemmont.isa.{LoongArch, TlbOperation}

class PrivilegeExceptionEvent extends Bundle {
  val code = UInt(6.W)
  val subcode = UInt(9.W)
  val badAddress = UInt(32.W)
  val pc = UInt(32.W)
  val isTlbRefill = Bool()
}

class PrivilegeDebugState extends Bundle {
  val crmd = UInt(32.W)
  val prmd = UInt(32.W)
  val euen = UInt(32.W)
  val ecfg = UInt(32.W)
  val estat = UInt(32.W)
  val era = UInt(32.W)
  val badv = UInt(32.W)
  val eentry = UInt(32.W)
  val save = Vec(4, UInt(32.W))
  val tid = UInt(32.W)
  val tcfg = UInt(32.W)
  val tval = UInt(32.W)
  val ticlr = UInt(32.W)
  val llbctl = UInt(32.W)
}

class PrivilegeController(
    tlbConfig: TlbConfig = TlbConfig()
) extends Module {
  val io = IO(new Bundle {
    val csrWrite = Input(Valid(new TlbCsrWrite))
    val csrReadAddress = Input(UInt(14.W))
    val csrReadData = Output(UInt(32.W))

    val exception = Input(Valid(new PrivilegeExceptionEvent))
    val ertn = Input(Bool())
    val setLoadLinked = Input(Bool())
    val clearLoadLinked = Input(Bool())
    val enterWait = Input(Bool())
    val externalInterrupt = Input(UInt(8.W))

    val tlbOperation = Input(TlbOperation())
    val tlbInvalidateAsid = Input(UInt(DesignParams.asidWidth.W))
    val tlbInvalidateVirtualPageNumber = Input(UInt(19.W))

    val redirect = Output(Valid(UInt(32.W)))
    val interruptPending = Output(Bool())
    val loadLinked = Output(Bool())
    val waiting = Output(Bool())
    val translationControl = Output(new TranslationControl)
    val frontendPrivilege = Output(UInt(2.W))
    val frontendDirectMap0 = Output(new DirectMapWindow)
    val frontendDirectMap1 = Output(new DirectMapWindow)
    val tlbEntries = Output(Vec(tlbConfig.entries, new TlbEntry))
    val tlbState = Output(new TlbCsrState)
    val tlbVictimIndex = Output(UInt(tlbConfig.indexWidth.W))
    val debug = Output(new PrivilegeDebugState)
  })

  val currentPrivilege = RegInit(0.U(2.W))

  val currentPrivilegeForFrontend = RegInit(0.U(2.W))
  annotate(new ChiselAnnotation {
    override def toFirrtl =
      firrtl.AttributeAnnotation(currentPrivilegeForFrontend.toTarget, "dont_touch = \"yes\"")
  })

  val directMap0ForFrontend = RegInit(0.U.asTypeOf(new DirectMapWindow))
  val directMap1ForFrontend = RegInit(0.U.asTypeOf(new DirectMapWindow))
  Seq(
    directMap0ForFrontend.privilege0,
    directMap0ForFrontend.privilege3,
    directMap0ForFrontend.memoryAttribute,
    directMap0ForFrontend.physicalSegment,
    directMap0ForFrontend.virtualSegment,
    directMap1ForFrontend.privilege0,
    directMap1ForFrontend.privilege3,
    directMap1ForFrontend.memoryAttribute,
    directMap1ForFrontend.physicalSegment,
    directMap1ForFrontend.virtualSegment
  ).foreach { field =>
    annotate(new ChiselAnnotation {
      override def toFirrtl =
        firrtl.AttributeAnnotation(field.toTarget, "dont_touch = \"yes\"")
    })
  }
  val interruptEnable = RegInit(false.B)
  val directAddress = RegInit(true.B)
  val paging = RegInit(false.B)
  val fetchMemoryAttribute = RegInit(0.U(2.W))
  val dataMemoryAttribute = RegInit(0.U(2.W))
  val previousPrivilege = RegInit(0.U(2.W))
  val previousInterruptEnable = RegInit(false.B)

  val interruptEnableMask = RegInit(0.U(13.W))
  val softwareInterrupt = RegInit(0.U(2.W))
  val externalInterrupt = RegInit(0.U(8.W))
  val timerInterrupt = RegInit(false.B)
  val exceptionCode = RegInit(0.U(6.W))
  val exceptionSubcode = RegInit(0.U(9.W))

  val exceptionReturnAddress = RegInit(0.U(32.W))
  val badAddress = RegInit(0.U(32.W))
  val exceptionEntry = RegInit(0.U(26.W))
  val save = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))

  val loadLinked = RegInit(false.B)
  val keepLoadLinked = RegInit(false.B)

  val threadId = RegInit(0.U(32.W))
  val timerCsrEnable = RegInit(false.B)
  val timerPeriodic = RegInit(false.B)
  val timerInitialValue = RegInit(0.U(30.W))
  val timerEnabled = RegInit(false.B)
  val timerValue = RegInit(0.U(32.W))

  val waiting = RegInit(false.B)

  val pendingStatus = Cat(0.U(1.W), timerInterrupt, externalInterrupt, softwareInterrupt)
  val effectiveMask = Cat(interruptEnableMask(12, 11), interruptEnableMask(9, 0))
  val interruptPending = (effectiveMask & pendingStatus).orR && interruptEnable
  io.interruptPending := interruptPending

  val crmd = Cat(
    0.U(23.W),
    dataMemoryAttribute,
    fetchMemoryAttribute,
    paging,
    directAddress,
    interruptEnable,
    currentPrivilege
  )
  val prmd = Cat(0.U(29.W), previousInterruptEnable, previousPrivilege)
  val ecfg = Cat(0.U(19.W), interruptEnableMask)
  val estat = Cat(
    0.U(1.W),
    exceptionSubcode,
    exceptionCode,
    0.U(3.W),
    0.U(1.W),
    timerInterrupt,
    0.U(1.W),
    externalInterrupt,
    softwareInterrupt
  )
  val eentry = Cat(exceptionEntry, 0.U(6.W))
  val tcfg = Cat(timerInitialValue, timerPeriodic, timerCsrEnable)
  val llbctl = Cat(0.U(29.W), keepLoadLinked, 0.U(1.W), loadLinked)

  val tlb = Module(new TlbFile(tlbConfig))
  tlb.io.csrWrite := io.csrWrite
  tlb.io.csrReadAddress := io.csrReadAddress
  tlb.io.badAddress := badAddress
  tlb.io.operation := io.tlbOperation
  tlb.io.invalidateAsid := io.tlbInvalidateAsid
  tlb.io.invalidateVirtualPageNumber := io.tlbInvalidateVirtualPageNumber
  tlb.io.exceptionCode := exceptionCode
  tlb.io.exceptionVirtualPageUpdate.valid := false.B
  tlb.io.exceptionVirtualPageUpdate.bits := io.exception.bits.badAddress(31, 13)

  io.tlbEntries := tlb.io.entries
  io.tlbState := tlb.io.state
  io.tlbVictimIndex := tlb.io.victimIndex

  io.translationControl.directAddress := directAddress
  io.translationControl.paging := paging
  io.translationControl.fetchMemoryAttribute := fetchMemoryAttribute
  io.translationControl.dataMemoryAttribute := dataMemoryAttribute
  io.translationControl.privilege := currentPrivilege
  io.frontendPrivilege := currentPrivilegeForFrontend
  io.frontendDirectMap0 := directMap0ForFrontend
  io.frontendDirectMap1 := directMap1ForFrontend
  io.translationControl.asid := tlb.io.state.asid
  io.translationControl.directMap0 := tlb.io.state.directMap0
  io.translationControl.directMap1 := tlb.io.state.directMap1

  val ownReadData = WireDefault(0.U(32.W))
  switch(io.csrReadAddress) {
    is(LoongArch.CsrAddress.Crmd.U) { ownReadData := crmd }
    is(LoongArch.CsrAddress.Prmd.U) { ownReadData := prmd }
    is(LoongArch.CsrAddress.Euen.U) { ownReadData := 0.U }
    is(LoongArch.CsrAddress.Ecfg.U) { ownReadData := ecfg }
    is(LoongArch.CsrAddress.Estat.U) { ownReadData := estat }
    is(LoongArch.CsrAddress.Era.U) { ownReadData := exceptionReturnAddress }
    is(LoongArch.CsrAddress.Badv.U) { ownReadData := badAddress }
    is(LoongArch.CsrAddress.Eentry.U) { ownReadData := eentry }
    is(LoongArch.CsrAddress.Cpuid.U) { ownReadData := 0.U }
    is(LoongArch.CsrAddress.Save0.U) { ownReadData := save(0) }
    is(LoongArch.CsrAddress.Save1.U) { ownReadData := save(1) }
    is(LoongArch.CsrAddress.Save2.U) { ownReadData := save(2) }
    is(LoongArch.CsrAddress.Save3.U) { ownReadData := save(3) }
    is(LoongArch.CsrAddress.Tid.U) { ownReadData := threadId }
    is(LoongArch.CsrAddress.Tcfg.U) { ownReadData := tcfg }
    is(LoongArch.CsrAddress.Tval.U) { ownReadData := timerValue }
    is(LoongArch.CsrAddress.Ticlr.U) { ownReadData := 0.U }
    is(LoongArch.CsrAddress.Llbctl.U) { ownReadData := llbctl }
  }
  io.csrReadData := Mux(tlb.io.csrReadMapped, tlb.io.csrReadData, ownReadData)

  io.redirect.valid := io.exception.valid || io.ertn
  io.redirect.bits := Mux(
    io.ertn,
    exceptionReturnAddress,
    Mux(
      io.exception.bits.isTlbRefill,
      Cat(tlb.io.state.tlbRefillEntry, 0.U(6.W)),
      eentry
    )
  )

  externalInterrupt := io.externalInterrupt

  when(timerEnabled) {
    when(timerValue <= 1.U) {
      timerInterrupt := true.B
      timerValue := Mux(timerPeriodic, Cat(timerInitialValue, 0.U(2.W)), 0.U)
      timerEnabled := timerPeriodic
    }.otherwise {
      timerValue := timerValue - 1.U
    }
  }

  when(io.setLoadLinked) { loadLinked := true.B }
  when(io.clearLoadLinked) { loadLinked := false.B }

  when(io.enterWait) { waiting := true.B }
  when(pendingStatus.orR) { waiting := false.B }

  when(io.csrWrite.valid) {
    val data = io.csrWrite.bits.data
    switch(io.csrWrite.bits.address) {
      is(LoongArch.CsrAddress.Crmd.U) {
        currentPrivilege := data(1, 0)
        currentPrivilegeForFrontend := data(1, 0)
        interruptEnable := data(2)
        directAddress := data(3)
        paging := data(4)
        fetchMemoryAttribute := data(6, 5)
        dataMemoryAttribute := data(8, 7)
      }
      is(LoongArch.CsrAddress.Prmd.U) {
        previousPrivilege := data(1, 0)
        previousInterruptEnable := data(2)
      }
      is(LoongArch.CsrAddress.Ecfg.U) {

        interruptEnableMask := Cat(data(12, 11), 0.U(1.W), data(9, 0))
      }
      is(LoongArch.CsrAddress.Estat.U) { softwareInterrupt := data(1, 0) }
      is(LoongArch.CsrAddress.Era.U) { exceptionReturnAddress := data }
      is(LoongArch.CsrAddress.Badv.U) { badAddress := data }
      is(LoongArch.CsrAddress.Eentry.U) { exceptionEntry := data(31, 6) }
      is(LoongArch.CsrAddress.Save0.U) { save(0) := data }
      is(LoongArch.CsrAddress.Save1.U) { save(1) := data }
      is(LoongArch.CsrAddress.Save2.U) { save(2) := data }
      is(LoongArch.CsrAddress.Save3.U) { save(3) := data }
      is(LoongArch.CsrAddress.Tid.U) { threadId := data }
      is(LoongArch.CsrAddress.Tcfg.U) {
        timerCsrEnable := data(0)
        timerPeriodic := data(1)
        timerInitialValue := data(31, 2)
        timerEnabled := data(0)
        timerValue := Mux(data(0), Cat(data(31, 2), 0.U(2.W)), 0.U)
      }
      is(LoongArch.CsrAddress.Ticlr.U) {
        when(data(0)) { timerInterrupt := false.B }
      }
      is(LoongArch.CsrAddress.Dmw0.U) {
        directMap0ForFrontend.privilege0 := data(0)
        directMap0ForFrontend.privilege3 := data(3)
        directMap0ForFrontend.memoryAttribute := data(5, 4)
        directMap0ForFrontend.physicalSegment := data(27, 25)
        directMap0ForFrontend.virtualSegment := data(31, 29)
      }
      is(LoongArch.CsrAddress.Dmw1.U) {
        directMap1ForFrontend.privilege0 := data(0)
        directMap1ForFrontend.privilege3 := data(3)
        directMap1ForFrontend.memoryAttribute := data(5, 4)
        directMap1ForFrontend.physicalSegment := data(27, 25)
        directMap1ForFrontend.virtualSegment := data(31, 29)
      }
      is(LoongArch.CsrAddress.Llbctl.U) {
        keepLoadLinked := data(2)
        when(data(1)) { loadLinked := false.B }
      }
    }
  }

  val updatesBadAddress = Seq(
    LoongArch.ExceptionCode.TlbRefill,
    LoongArch.ExceptionCode.AddressErrorFetch,
    LoongArch.ExceptionCode.AddressErrorMemory,
    LoongArch.ExceptionCode.AddressAlignment,
    LoongArch.ExceptionCode.PageInvalidLoad,
    LoongArch.ExceptionCode.PageInvalidStore,
    LoongArch.ExceptionCode.PageInvalidFetch,
    LoongArch.ExceptionCode.PageModified,
    LoongArch.ExceptionCode.PagePrivilege
  ).map(exception =>
    io.exception.bits.code === exception.code.U && io.exception.bits.subcode === exception.subcode.U
  ).reduce(_ || _)

  val updatesTlbVirtualPage = Seq(
    LoongArch.ExceptionCode.TlbRefill,
    LoongArch.ExceptionCode.PageInvalidLoad,
    LoongArch.ExceptionCode.PageInvalidStore,
    LoongArch.ExceptionCode.PageInvalidFetch,
    LoongArch.ExceptionCode.PageModified,
    LoongArch.ExceptionCode.PagePrivilege
  ).map(exception =>
    io.exception.bits.code === exception.code.U && io.exception.bits.subcode === exception.subcode.U
  ).reduce(_ || _)

  when(io.exception.valid) {
    when(io.exception.bits.code === LoongArch.ExceptionCode.Interrupt.code.U) {
      exceptionCode := LoongArch.ExceptionCode.Interrupt.code.U
      exceptionSubcode := LoongArch.ExceptionCode.Interrupt.subcode.U
    }.otherwise {
      exceptionCode := io.exception.bits.code
      exceptionSubcode := io.exception.bits.subcode
      when(io.exception.bits.isTlbRefill) {
        directAddress := true.B
        paging := false.B
      }
      when(updatesBadAddress) {
        badAddress := io.exception.bits.badAddress
      }
      when(updatesTlbVirtualPage) {
        tlb.io.exceptionVirtualPageUpdate.valid := true.B
      }
    }
    previousPrivilege := currentPrivilege
    previousInterruptEnable := interruptEnable
    currentPrivilege := 0.U
    currentPrivilegeForFrontend := 0.U
    interruptEnable := false.B
    exceptionReturnAddress := io.exception.bits.pc
  }.elsewhen(io.ertn) {
    when(keepLoadLinked) {
      keepLoadLinked := false.B
    }.otherwise {
      loadLinked := false.B
    }
    currentPrivilege := previousPrivilege
    currentPrivilegeForFrontend := previousPrivilege
    interruptEnable := previousInterruptEnable
    when(exceptionCode === LoongArch.ExceptionCode.TlbRefill.code.U) {
      directAddress := false.B
      paging := true.B
    }
  }

  io.loadLinked := loadLinked
  io.waiting := waiting
  io.debug.crmd := crmd
  io.debug.prmd := prmd
  io.debug.euen := 0.U
  io.debug.ecfg := ecfg
  io.debug.estat := estat
  io.debug.era := exceptionReturnAddress
  io.debug.badv := badAddress
  io.debug.eentry := eentry
  io.debug.save := save
  io.debug.tid := threadId
  io.debug.tcfg := tcfg
  io.debug.tval := timerValue
  io.debug.ticlr := 0.U
  io.debug.llbctl := llbctl
}
