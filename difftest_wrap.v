module MycpuDifftestInstrCommit(
    input         clock,
    input  [7:0]  coreid,
    input  [7:0]  index,
    input         valid,
    input  [63:0] pc,
    input  [31:0] instr,
    input         skip,
    input         isTLBFill,
    input  [4:0]  tlbFillIndex,
    input         isCntInst,
    input  [63:0] timer64Value,
    input         wen,
    input  [7:0]  wdest,
    input  [63:0] wdata,
    input         csrRstat,
    input  [31:0] csrData
);
`ifdef DIFFTEST_EN
    DifftestInstrCommit u_difftest_instr_commit (
        .clock         (clock),
        .coreid        (coreid),
        .index         (index),
        .valid         (valid),
        .pc            (pc),
        .instr         (instr),
        .skip          (skip),
        .is_TLBFILL    (isTLBFill),
        .TLBFILL_index (tlbFillIndex),
        .is_CNTinst    (isCntInst),
        .timer_64_value(timer64Value),
        .wen           (wen),
        .wdest         (wdest),
        .wdata         (wdata),
        .csr_rstat     (csrRstat),
        .csr_data      (csrData)
    );
`endif
endmodule

module MycpuDifftestExcpEvent(
    input         clock,
    input  [7:0]  coreid,
    input         excpValid,
    input         eret,
    input  [31:0] intrNo,
    input  [31:0] cause,
    input  [63:0] exceptionPc,
    input  [31:0] exceptionInst
);
`ifdef DIFFTEST_EN
    DifftestExcpEvent u_difftest_excp_event (
        .clock         (clock),
        .coreid        (coreid),
        .excp_valid    (excpValid),
        .eret          (eret),
        .intrNo        (intrNo),
        .cause         (cause),
        .exceptionPC   (exceptionPc),
        .exceptionInst (exceptionInst)
    );
`endif
endmodule

module MycpuDifftestTrapEvent(
    input         clock,
    input  [7:0]  coreid,
    input         valid,
    input  [2:0]  code,
    input  [63:0] pc,
    input  [63:0] cycleCnt,
    input  [63:0] instrCnt
);
`ifdef DIFFTEST_EN
    DifftestTrapEvent u_difftest_trap_event (
        .clock    (clock),
        .coreid   (coreid),
        .valid    (valid),
        .code     (code),
        .pc       (pc),
        .cycleCnt (cycleCnt),
        .instrCnt (instrCnt)
    );
`endif
endmodule

module MycpuDifftestStoreEvent(
    input         clock,
    input  [7:0]  coreid,
    input  [7:0]  index,
    input  [7:0]  valid,
    input  [63:0] storePAddr,
    input  [63:0] storeVAddr,
    input  [63:0] storeData
);
`ifdef DIFFTEST_EN
    DifftestStoreEvent u_difftest_store_event (
        .clock      (clock),
        .coreid     (coreid),
        .index      (index),
        .valid      (valid),
        .storePAddr (storePAddr),
        .storeVAddr (storeVAddr),
        .storeData  (storeData)
    );
`endif
endmodule

module MycpuDifftestLoadEvent(
    input         clock,
    input  [7:0]  coreid,
    input  [7:0]  index,
    input  [7:0]  valid,
    input  [63:0] paddr,
    input  [63:0] vaddr
);
`ifdef DIFFTEST_EN
    DifftestLoadEvent u_difftest_load_event (
        .clock  (clock),
        .coreid (coreid),
        .index  (index),
        .valid  (valid),
        .paddr  (paddr),
        .vaddr  (vaddr)
    );
`endif
endmodule

module MycpuDifftestCSRRegState(
    input         clock,
    input  [7:0]  coreid,
    input  [63:0] crmd,
    input  [63:0] prmd,
    input  [63:0] euen,
    input  [63:0] ecfg,
    input  [63:0] estat,
    input  [63:0] era,
    input  [63:0] badv,
    input  [63:0] eentry,
    input  [63:0] tlbidx,
    input  [63:0] tlbehi,
    input  [63:0] tlbelo0,
    input  [63:0] tlbelo1,
    input  [63:0] asid,
    input  [63:0] pgdl,
    input  [63:0] pgdh,
    input  [63:0] save0,
    input  [63:0] save1,
    input  [63:0] save2,
    input  [63:0] save3,
    input  [63:0] tid,
    input  [63:0] tcfg,
    input  [63:0] tval,
    input  [63:0] ticlr,
    input  [63:0] llbctl,
    input  [63:0] tlbrentry,
    input  [63:0] dmw0,
    input  [63:0] dmw1
);
`ifdef DIFFTEST_EN
    DifftestCSRRegState u_difftest_csr_state (
        .clock     (clock),
        .coreid    (coreid),
        .crmd      (crmd),
        .prmd      (prmd),
        .euen      (euen),
        .ecfg      (ecfg),
        .estat     (estat),
        .era       (era),
        .badv      (badv),
        .eentry    (eentry),
        .tlbidx    (tlbidx),
        .tlbehi    (tlbehi),
        .tlbelo0   (tlbelo0),
        .tlbelo1   (tlbelo1),
        .asid      (asid),
        .pgdl      (pgdl),
        .pgdh      (pgdh),
        .save0     (save0),
        .save1     (save1),
        .save2     (save2),
        .save3     (save3),
        .tid       (tid),
        .tcfg      (tcfg),
        .tval      (tval),
        .ticlr     (ticlr),
        .llbctl    (llbctl),
        .tlbrentry (tlbrentry),
        .dmw0      (dmw0),
        .dmw1      (dmw1)
    );
`endif
endmodule

module MycpuDifftestGRegState(
    input         clock,
    input  [7:0]  coreid,
    input  [63:0] gpr_0,
    input  [63:0] gpr_1,
    input  [63:0] gpr_2,
    input  [63:0] gpr_3,
    input  [63:0] gpr_4,
    input  [63:0] gpr_5,
    input  [63:0] gpr_6,
    input  [63:0] gpr_7,
    input  [63:0] gpr_8,
    input  [63:0] gpr_9,
    input  [63:0] gpr_10,
    input  [63:0] gpr_11,
    input  [63:0] gpr_12,
    input  [63:0] gpr_13,
    input  [63:0] gpr_14,
    input  [63:0] gpr_15,
    input  [63:0] gpr_16,
    input  [63:0] gpr_17,
    input  [63:0] gpr_18,
    input  [63:0] gpr_19,
    input  [63:0] gpr_20,
    input  [63:0] gpr_21,
    input  [63:0] gpr_22,
    input  [63:0] gpr_23,
    input  [63:0] gpr_24,
    input  [63:0] gpr_25,
    input  [63:0] gpr_26,
    input  [63:0] gpr_27,
    input  [63:0] gpr_28,
    input  [63:0] gpr_29,
    input  [63:0] gpr_30,
    input  [63:0] gpr_31
);
`ifdef DIFFTEST_EN
    DifftestGRegState u_difftest_gpr_state (
        .clock  (clock),
        .coreid (coreid),
        .gpr_0  (gpr_0),
        .gpr_1  (gpr_1),
        .gpr_2  (gpr_2),
        .gpr_3  (gpr_3),
        .gpr_4  (gpr_4),
        .gpr_5  (gpr_5),
        .gpr_6  (gpr_6),
        .gpr_7  (gpr_7),
        .gpr_8  (gpr_8),
        .gpr_9  (gpr_9),
        .gpr_10 (gpr_10),
        .gpr_11 (gpr_11),
        .gpr_12 (gpr_12),
        .gpr_13 (gpr_13),
        .gpr_14 (gpr_14),
        .gpr_15 (gpr_15),
        .gpr_16 (gpr_16),
        .gpr_17 (gpr_17),
        .gpr_18 (gpr_18),
        .gpr_19 (gpr_19),
        .gpr_20 (gpr_20),
        .gpr_21 (gpr_21),
        .gpr_22 (gpr_22),
        .gpr_23 (gpr_23),
        .gpr_24 (gpr_24),
        .gpr_25 (gpr_25),
        .gpr_26 (gpr_26),
        .gpr_27 (gpr_27),
        .gpr_28 (gpr_28),
        .gpr_29 (gpr_29),
        .gpr_30 (gpr_30),
        .gpr_31 (gpr_31)
    );
`endif
endmodule
