package gemmont.cache

import chisel3._
import chisel3.util.HasBlackBoxInline

private[cache] class DCacheDataRam extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "DCacheDataRam"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val writeEnable = Input(UInt(64.W))
    val writeAddress = Input(UInt(6.W))
    val writeData = Input(UInt(512.W))
    val lineReadEnable = Input(Bool())
    val lineReadAddress = Input(UInt(6.W))
    val lineReadData = Output(UInt(512.W))
    val wordReadEnable = Input(Bool())
    val wordReadAddress = Input(UInt(10.W))
    val wordReadData = Output(UInt(32.W))
  })

  setInline(
    "DCacheDataRam.sv",
    """
module DCacheDataRam (
  input  wire         clock,
  input  wire [63:0]  writeEnable,
  input  wire [5:0]   writeAddress,
  input  wire [511:0] writeData,
  input  wire         lineReadEnable,
  input  wire [5:0]   lineReadAddress,
  output wire [511:0] lineReadData,
  input  wire         wordReadEnable,
  input  wire [9:0]   wordReadAddress,
  output wire [31:0]  wordReadData
);
  wire         portAWrite = |writeEnable;
  wire [511:0] ramLineReadData;
  wire [31:0]  ramWordReadData;

`ifdef SYNTHESIS
  // 7-series block RAM permits a 4:1 asymmetric width ratio. Read one
  // 128-bit quarter through port B and select its registered word lane.
  wire [127:0] ramQuarterReadData;
  reg  [1:0]   wordReadLaneReg;

  xpm_memory_tdpram #(
    .ADDR_WIDTH_A(6),
    .ADDR_WIDTH_B(8),
    .AUTO_SLEEP_TIME(0),
    .BYTE_WRITE_WIDTH_A(8),
    .BYTE_WRITE_WIDTH_B(8),
    .CLOCKING_MODE("common_clock"),
    .ECC_MODE("no_ecc"),
    .MEMORY_INIT_FILE("none"),
    .MEMORY_INIT_PARAM(""),
    .MEMORY_OPTIMIZATION("true"),
    .MEMORY_PRIMITIVE("block"),
    .MEMORY_SIZE(32768),
    .MESSAGE_CONTROL(0),
    .READ_DATA_WIDTH_A(512),
    .READ_DATA_WIDTH_B(128),
    .READ_LATENCY_A(1),
    .READ_LATENCY_B(1),
    .READ_RESET_VALUE_A("0"),
    .READ_RESET_VALUE_B("0"),
    .RST_MODE_A("SYNC"),
    .RST_MODE_B("SYNC"),
    .SIM_ASSERT_CHK(0),
    .USE_EMBEDDED_CONSTRAINT(0),
    .USE_MEM_INIT(0),
    .WAKEUP_TIME("disable_sleep"),
    .WRITE_DATA_WIDTH_A(512),
    .WRITE_DATA_WIDTH_B(128),
    .WRITE_MODE_A("read_first"),
    .WRITE_MODE_B("read_first")
  ) data_ram (
    .sleep(1'b0),
    .clka(clock),
    .ena(portAWrite || lineReadEnable),
    .wea(writeEnable),
    .addra(portAWrite ? writeAddress : lineReadAddress),
    .dina(writeData),
    .injectsbiterra(1'b0),
    .injectdbiterra(1'b0),
    .rsta(1'b0),
    .regcea(1'b1),
    .douta(ramLineReadData),
    .sbiterra(),
    .dbiterra(),
    .clkb(clock),
    .rstb(1'b0),
    .enb(wordReadEnable),
    .web(16'b0),
    .addrb(wordReadAddress[9:2]),
    .dinb(128'b0),
    .regceb(1'b1),
    .doutb(ramQuarterReadData),
    .injectsbiterrb(1'b0),
    .injectdbiterrb(1'b0),
    .sbiterrb(),
    .dbiterrb()
  );

  always @(posedge clock) begin
    if (wordReadEnable)
      wordReadLaneReg <= wordReadAddress[1:0];
  end

  assign ramWordReadData =
    ramQuarterReadData[wordReadLaneReg * 32 +: 32];
`else
  reg [511:0] memory [0:63];
  reg [511:0] behavioralLineReadData;
  reg [31:0] behavioralWordReadData;
  integer byteLane;

  always @(posedge clock) begin
    for (byteLane = 0; byteLane < 64; byteLane = byteLane + 1) begin
      if (writeEnable[byteLane])
        memory[writeAddress][byteLane * 8 +: 8] <= writeData[byteLane * 8 +: 8];
    end
    if (lineReadEnable && !portAWrite)
      behavioralLineReadData <= memory[lineReadAddress];
    if (wordReadEnable)
      behavioralWordReadData <=
        memory[wordReadAddress[9:4]][wordReadAddress[3:0] * 32 +: 32];
  end

  assign ramLineReadData = behavioralLineReadData;
  assign ramWordReadData = behavioralWordReadData;
`endif

  assign lineReadData = ramLineReadData;
  assign wordReadData = ramWordReadData;
endmodule
"""
  )
}
