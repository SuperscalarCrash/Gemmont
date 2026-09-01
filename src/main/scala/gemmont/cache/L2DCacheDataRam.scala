package gemmont.cache

import chisel3._
import chisel3.util.HasBlackBoxInline

private[cache] class L2DCacheDataRam(addressWidth: Int, lineBits: Int)
    extends BlackBox
    with HasBlackBoxInline {
  require(addressWidth > 0)
  require(lineBits > 0)

  private val depth = 1 << addressWidth
  override def desiredName: String = "L2DCacheDataRam"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(lineBits.W))
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(lineBits.W))
  })

  setInline(
    "L2DCacheDataRam.sv",
    s"""
module L2DCacheDataRam (
  input  wire        clock,
  input  wire        writeEnable,
  input  wire [${addressWidth - 1}:0] writeAddress,
  input  wire [${lineBits - 1}:0] writeData,
  input  wire        readEnable,
  input  wire [${addressWidth - 1}:0] readAddress,
  output wire [${lineBits - 1}:0] readData
);
  wire [${lineBits - 1}:0] ramReadData;

`ifdef SYNTHESIS
  xpm_memory_sdpram #(
    .ADDR_WIDTH_A(${addressWidth}),
    .ADDR_WIDTH_B(${addressWidth}),
    .AUTO_SLEEP_TIME(0),
    .BYTE_WRITE_WIDTH_A(${lineBits}),
    .CLOCKING_MODE("common_clock"),
    .ECC_MODE("no_ecc"),
    .MEMORY_INIT_FILE("none"),
    .MEMORY_INIT_PARAM(""),
    .MEMORY_OPTIMIZATION("true"),
    .MEMORY_PRIMITIVE("block"),
    .MEMORY_SIZE(${depth * lineBits}),
    .MESSAGE_CONTROL(0),
    .READ_DATA_WIDTH_B(${lineBits}),
    .READ_LATENCY_B(2),
    .READ_RESET_VALUE_B("0"),
    .RST_MODE_A("SYNC"),
    .RST_MODE_B("SYNC"),
    .SIM_ASSERT_CHK(0),
    .USE_EMBEDDED_CONSTRAINT(0),
    .USE_MEM_INIT(0),
    .WAKEUP_TIME("disable_sleep"),
    .WRITE_DATA_WIDTH_A(${lineBits}),
    .WRITE_MODE_B("read_first")
  ) data_ram (
    .sleep(1'b0),
    .clka(clock),
    .ena(writeEnable),
    .wea(writeEnable),
    .addra(writeAddress),
    .dina(writeData),
    .injectsbiterra(1'b0),
    .injectdbiterra(1'b0),
    .clkb(clock),
    .rstb(1'b0),
    .enb(readEnable),
    .regceb(1'b1),
    .addrb(readAddress),
    .doutb(ramReadData),
    .sbiterrb(),
    .dbiterrb()
  );
`else
  reg [${lineBits - 1}:0] memory [0:${depth - 1}];
  reg [${lineBits - 1}:0] behavioralReadData;
  reg [${lineBits - 1}:0] behavioralOutputData;

  always @(posedge clock) begin
    if (writeEnable)
      memory[writeAddress] <= writeData;
    if (readEnable)
      behavioralReadData <= memory[readAddress];
    behavioralOutputData <= behavioralReadData;
  end

  assign ramReadData = behavioralOutputData;
`endif

  assign readData = ramReadData;
endmodule
"""
  )
}
