package gemmont.privilege

import chisel3._
import chisel3.util._
import gemmont.{DesignParams, TlbConfig}
import gemmont.isa.{LoongArch, MemoryAccess, TlbOperation}

class TranslationExceptions extends Bundle {
  val pageInvalidLoad = Bool()
  val pageInvalidStore = Bool()
  val pageInvalidFetch = Bool()
  val pageModified = Bool()
  val pagePrivilege = Bool()
  val tlbRefill = Bool()
}

class TranslationResult extends Bundle {
  val valid = Bool()
  val physicalAddress = UInt(32.W)
  val cached = Bool()
  val exception = new TranslationExceptions
}

class DirectMapWindow extends Bundle {
  val privilege0 = Bool()
  val privilege3 = Bool()
  val memoryAttribute = UInt(2.W)
  val physicalSegment = UInt(3.W)
  val virtualSegment = UInt(3.W)
}

class TlbPage extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val memoryAttribute = UInt(2.W)
  val privilege = UInt(2.W)
  val physicalPageNumber = UInt(20.W)
}

class TlbEntry extends Bundle {
  val enabled = Bool()
  val asid = UInt(DesignParams.asidWidth.W)
  val global = Bool()
  val pageSize = UInt(6.W)
  val virtualPageNumber = UInt(19.W)
  val page0 = new TlbPage
  val page1 = new TlbPage
}

class TranslationControl extends Bundle {
  val directAddress = Bool()
  val paging = Bool()
  val fetchMemoryAttribute = UInt(2.W)
  val dataMemoryAttribute = UInt(2.W)
  val privilege = UInt(2.W)
  val asid = UInt(DesignParams.asidWidth.W)
  val directMap0 = new DirectMapWindow
  val directMap1 = new DirectMapWindow
}

class TranslationRequest extends Bundle {
  val virtualAddress = UInt(32.W)
  val access = MemoryAccess()
}

class AddressTranslator(config: TlbConfig = TlbConfig()) extends Module {
  val io = IO(new Bundle {
    val request = Input(new TranslationRequest)
    val control = Input(new TranslationControl)
    val entries = Input(Vec(config.entries, new TlbEntry))
    val result = Output(new TranslationResult)
  })

  val virtualAddress = io.request.virtualAddress
  val hits = Wire(Vec(config.entries, Bool()))
  val largePages = Wire(Vec(config.entries, Bool()))
  val pages = Wire(Vec(config.entries, new TlbPage))
  for (index <- 0 until config.entries) {
    val entry = io.entries(index)
    val largePage = entry.pageSize === 21.U
    val highMatch = entry.virtualPageNumber(18, 9) === virtualAddress(31, 22)
    val lowMatch = entry.virtualPageNumber(8, 0) === virtualAddress(21, 13)
    val pageMatches = highMatch && (largePage || lowMatch)
    val oddPage = Mux(largePage, virtualAddress(21), virtualAddress(12))

    largePages(index) := largePage
    pages(index) := Mux(oddPage, entry.page1, entry.page0)
    hits(index) := entry.enabled && (entry.global || entry.asid === io.control.asid) && pageMatches
  }

  val hit = hits.asUInt.orR
  assert(PopCount(hits) <= 1.U)
  val largePage = Mux1H(hits, largePages)

  val selectedPhysicalPageNumber = Mux1H(hits, pages.map(_.physicalPageNumber))
  val selectedValid = Mux1H(hits, pages.map(_.valid))
  val selectedDirty = Mux1H(hits, pages.map(_.dirty))
  val selectedPrivilege = Mux1H(hits, pages.map(_.privilege))
  val selectedCached = Mux1H(hits, pages.map(_.memoryAttribute === 1.U))

  val tlbResult = WireDefault(0.U.asTypeOf(new TranslationResult))
  tlbResult.valid := true.B
  tlbResult.physicalAddress := Mux(
    largePage,
    Cat(selectedPhysicalPageNumber(19, 9), virtualAddress(20, 0)),
    Cat(selectedPhysicalPageNumber, virtualAddress(11, 0))
  )
  tlbResult.cached := selectedCached
  tlbResult.exception.tlbRefill := !hit
  when(!selectedValid) {
    switch(io.request.access) {
      is(MemoryAccess.Fetch) { tlbResult.exception.pageInvalidFetch := true.B }
      is(MemoryAccess.Load) { tlbResult.exception.pageInvalidLoad := true.B }
      is(MemoryAccess.Store) { tlbResult.exception.pageInvalidStore := true.B }
    }
  }
  tlbResult.exception.pagePrivilege := io.control.privilege > selectedPrivilege
  tlbResult.exception.pageModified := io.request.access === MemoryAccess.Store && !selectedDirty

  def directMapValid(window: DirectMapWindow): Bool = {
    val privilegeValid =
      (window.privilege0 && io.control.privilege === 0.U) ||
        (window.privilege3 && io.control.privilege === 3.U)
    privilegeValid && virtualAddress(31, 29) === window.virtualSegment
  }

  val directMap0Valid = directMapValid(io.control.directMap0)
  val directMap1Valid = directMapValid(io.control.directMap1)
  val directMapValidAny = directMap0Valid || directMap1Valid
  val directMapResult = WireDefault(0.U.asTypeOf(new TranslationResult))
  directMapResult.valid := directMapValidAny
  directMapResult.physicalAddress := Mux(
    directMap0Valid,
    Cat(io.control.directMap0.physicalSegment, virtualAddress(28, 0)),
    Cat(io.control.directMap1.physicalSegment, virtualAddress(28, 0))
  )
  val directMapMemoryAttribute = Mux(
    directMap0Valid,
    io.control.directMap0.memoryAttribute,
    io.control.directMap1.memoryAttribute
  )
  directMapResult.cached := directMapMemoryAttribute === 1.U

  io.result := 0.U.asTypeOf(io.result)
  when(!io.control.directAddress && io.control.paging) {
    io.result := Mux(directMapValidAny, directMapResult, tlbResult)
  }.elsewhen(io.control.directAddress && !io.control.paging) {
    io.result.valid := true.B
    io.result.physicalAddress := virtualAddress
    val directMemoryAttribute = Mux(
      io.request.access === MemoryAccess.Fetch,
      io.control.fetchMemoryAttribute,
      io.control.dataMemoryAttribute
    )
    io.result.cached := directMemoryAttribute === 1.U
  }
}

class TlbCsrState extends Bundle {
  val index = UInt(5.W)
  val pageSize = UInt(6.W)
  val notExist = Bool()
  val virtualPageNumber = UInt(19.W)
  val page0 = new TlbPage
  val page1 = new TlbPage
  val global0 = Bool()
  val global1 = Bool()
  val asid = UInt(DesignParams.asidWidth.W)
  val pageDirectoryLow = UInt(20.W)
  val pageDirectoryHigh = UInt(20.W)
  val tlbRefillEntry = UInt(26.W)
  val directMap0 = new DirectMapWindow
  val directMap1 = new DirectMapWindow
}

class TlbCsrWrite extends Bundle {
  val address = UInt(14.W)
  val data = UInt(32.W)
}

class TlbFile(config: TlbConfig = TlbConfig()) extends Module {
  val io = IO(new Bundle {
    val csrWrite = Input(Valid(new TlbCsrWrite))
    val csrReadAddress = Input(UInt(14.W))
    val badAddress = Input(UInt(32.W))
    val operation = Input(TlbOperation())
    val invalidateAsid = Input(UInt(DesignParams.asidWidth.W))
    val invalidateVirtualPageNumber = Input(UInt(19.W))
    val exceptionCode = Input(UInt(6.W))
    val exceptionVirtualPageUpdate = Input(Valid(UInt(19.W)))

    val csrReadData = Output(UInt(32.W))
    val csrReadMapped = Output(Bool())
    val state = Output(new TlbCsrState)
    val entries = Output(Vec(config.entries, new TlbEntry))
    val victimIndex = Output(UInt(config.indexWidth.W))
  })

  val state = RegInit(0.U.asTypeOf(new TlbCsrState))
  val entries = RegInit(VecInit(Seq.fill(config.entries)(0.U.asTypeOf(new TlbEntry))))
  val victimIndex = RegInit(0.U(config.indexWidth.W))

  io.state := state
  io.entries := entries
  io.victimIndex := victimIndex

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

  val pgd = Mux(io.badAddress(31), state.pageDirectoryHigh, state.pageDirectoryLow)
  io.csrReadData := 0.U
  io.csrReadMapped := Seq(
    LoongArch.CsrAddress.Tlbidx,
    LoongArch.CsrAddress.Tlbehi,
    LoongArch.CsrAddress.Tlbelo0,
    LoongArch.CsrAddress.Tlbelo1,
    LoongArch.CsrAddress.Asid,
    LoongArch.CsrAddress.Pgdl,
    LoongArch.CsrAddress.Pgdh,
    LoongArch.CsrAddress.Pgd,
    LoongArch.CsrAddress.TlbRentry,
    LoongArch.CsrAddress.Dmw0,
    LoongArch.CsrAddress.Dmw1
  ).map(address => io.csrReadAddress === address.U).reduce(_ || _)
  switch(io.csrReadAddress) {
    is(LoongArch.CsrAddress.Tlbidx.U) {
      io.csrReadData := Cat(state.notExist, 0.U(1.W), state.pageSize, 0.U(19.W), state.index)
    }
    is(LoongArch.CsrAddress.Tlbehi.U) { io.csrReadData := Cat(state.virtualPageNumber, 0.U(13.W)) }
    is(LoongArch.CsrAddress.Tlbelo0.U) { io.csrReadData := packPage(state.page0, state.global0) }
    is(LoongArch.CsrAddress.Tlbelo1.U) { io.csrReadData := packPage(state.page1, state.global1) }
    is(LoongArch.CsrAddress.Asid.U) {
      io.csrReadData := Cat(
        0.U(8.W),
        DesignParams.asidWidth.U(8.W),
        0.U(6.W),
        state.asid
      )
    }
    is(LoongArch.CsrAddress.Pgdl.U) { io.csrReadData := Cat(state.pageDirectoryLow, 0.U(12.W)) }
    is(LoongArch.CsrAddress.Pgdh.U) { io.csrReadData := Cat(state.pageDirectoryHigh, 0.U(12.W)) }
    is(LoongArch.CsrAddress.Pgd.U) { io.csrReadData := Cat(pgd, 0.U(12.W)) }
    is(LoongArch.CsrAddress.TlbRentry.U) { io.csrReadData := Cat(state.tlbRefillEntry, 0.U(6.W)) }
    is(LoongArch.CsrAddress.Dmw0.U) {
      io.csrReadData := Cat(
        state.directMap0.virtualSegment,
        0.U(1.W),
        state.directMap0.physicalSegment,
        0.U(19.W),
        state.directMap0.memoryAttribute,
        state.directMap0.privilege3,
        0.U(2.W),
        state.directMap0.privilege0
      )
    }
    is(LoongArch.CsrAddress.Dmw1.U) {
      io.csrReadData := Cat(
        state.directMap1.virtualSegment,
        0.U(1.W),
        state.directMap1.physicalSegment,
        0.U(19.W),
        state.directMap1.memoryAttribute,
        state.directMap1.privilege3,
        0.U(2.W),
        state.directMap1.privilege0
      )
    }
  }

  when(io.csrWrite.valid) {
    val data = io.csrWrite.bits.data
    switch(io.csrWrite.bits.address) {
      is(LoongArch.CsrAddress.Tlbidx.U) {
        state.index := data(4, 0)
        state.pageSize := data(29, 24)
        state.notExist := data(31)
      }
      is(LoongArch.CsrAddress.Tlbehi.U) { state.virtualPageNumber := data(31, 13) }
      is(LoongArch.CsrAddress.Tlbelo0.U) {
        state.page0.valid := data(0)
        state.page0.dirty := data(1)
        state.page0.privilege := data(3, 2)
        state.page0.memoryAttribute := data(5, 4)
        state.global0 := data(6)
        state.page0.physicalPageNumber := data(27, 8)
      }
      is(LoongArch.CsrAddress.Tlbelo1.U) {
        state.page1.valid := data(0)
        state.page1.dirty := data(1)
        state.page1.privilege := data(3, 2)
        state.page1.memoryAttribute := data(5, 4)
        state.global1 := data(6)
        state.page1.physicalPageNumber := data(27, 8)
      }
      is(LoongArch.CsrAddress.Asid.U) {
        state.asid := data(DesignParams.asidWidth - 1, 0)
      }
      is(LoongArch.CsrAddress.Pgdl.U) { state.pageDirectoryLow := data(31, 12) }
      is(LoongArch.CsrAddress.Pgdh.U) { state.pageDirectoryHigh := data(31, 12) }
      is(LoongArch.CsrAddress.TlbRentry.U) { state.tlbRefillEntry := data(31, 6) }
      is(LoongArch.CsrAddress.Dmw0.U) {
        state.directMap0.privilege0 := data(0)
        state.directMap0.privilege3 := data(3)
        state.directMap0.memoryAttribute := data(5, 4)
        state.directMap0.physicalSegment := data(27, 25)
        state.directMap0.virtualSegment := data(31, 29)
      }
      is(LoongArch.CsrAddress.Dmw1.U) {
        state.directMap1.privilege0 := data(0)
        state.directMap1.privilege3 := data(3)
        state.directMap1.memoryAttribute := data(5, 4)
        state.directMap1.physicalSegment := data(27, 25)
        state.directMap1.virtualSegment := data(31, 29)
      }
    }
  }

  when(io.exceptionVirtualPageUpdate.valid) {
    state.virtualPageNumber := io.exceptionVirtualPageUpdate.bits
  }

  val searchHits = Wire(Vec(config.entries, Bool()))
  for (index <- 0 until config.entries) {
    val entry = entries(index)
    val largePage = entry.pageSize === 21.U
    searchHits(index) := entry.enabled && (entry.asid === state.asid || entry.global) && Mux(
      largePage,
      entry.virtualPageNumber(18, 9) === state.virtualPageNumber(18, 9),
      entry.virtualPageNumber === state.virtualPageNumber
    )
  }

  def writeEntry(index: UInt): Unit = {
    val entry = entries(index)
    entry.enabled := Mux(
      io.exceptionCode === LoongArch.ExceptionCode.TlbRefill.code.U,
      true.B,
      !state.notExist
    )
    entry.asid := state.asid
    entry.global := state.global0 && state.global1
    entry.pageSize := state.pageSize
    entry.virtualPageNumber := state.virtualPageNumber
    entry.page0 := state.page0
    entry.page1 := state.page1
  }

  switch(io.operation) {
    is(TlbOperation.Search) {
      when(searchHits.asUInt.orR) {
        state.index := PriorityEncoder(searchHits)
        state.notExist := false.B
      }.otherwise {
        state.notExist := true.B
      }
    }
    is(TlbOperation.Read) {
      val entry = entries(state.index)
      when(entry.enabled) {
        state.virtualPageNumber := entry.virtualPageNumber
        state.pageSize := entry.pageSize
        state.notExist := false.B
        state.asid := entry.asid
        state.page0 := entry.page0
        state.page1 := entry.page1
        state.global0 := entry.global
        state.global1 := entry.global
      }.otherwise {
        state.notExist := true.B
        state.asid := 0.U
        state.virtualPageNumber := 0.U
        state.pageSize := 0.U
        state.page0 := 0.U.asTypeOf(state.page0)
        state.page1 := 0.U.asTypeOf(state.page1)
        state.global0 := false.B
        state.global1 := false.B
      }
    }
    is(TlbOperation.Write) { writeEntry(state.index) }
    is(TlbOperation.Fill) {
      writeEntry(victimIndex)
      victimIndex := victimIndex + 1.U
    }
    is(
      TlbOperation.Invalidate1,
      TlbOperation.Invalidate2,
      TlbOperation.Invalidate3,
      TlbOperation.Invalidate4,
      TlbOperation.Invalidate5,
      TlbOperation.Invalidate6
    ) {
      for (index <- 0 until config.entries) {
        val entry = entries(index)
        val largePage = entry.pageSize === 21.U
        val virtualPageMatches = Mux(
          largePage,
          entry.virtualPageNumber(18, 9) === io.invalidateVirtualPageNumber(18, 9),
          entry.virtualPageNumber === io.invalidateVirtualPageNumber
        )
        val asidMatches = entry.asid === io.invalidateAsid
        val invalidate = MuxLookup(io.operation.asUInt, true.B)(
          Seq(
            TlbOperation.Invalidate2.asUInt -> entry.global,
            TlbOperation.Invalidate3.asUInt -> !entry.global,
            TlbOperation.Invalidate4.asUInt -> (!entry.global && asidMatches),
            TlbOperation.Invalidate5.asUInt -> (!entry.global && asidMatches && virtualPageMatches),
            TlbOperation.Invalidate6.asUInt -> ((entry.global || asidMatches) && virtualPageMatches)
          )
        )
        when(invalidate) { entry.enabled := false.B }
      }
    }
  }
}
