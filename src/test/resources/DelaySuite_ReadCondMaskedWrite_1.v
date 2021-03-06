module DelaySuite_ReadCondMaskedWrite_1(input clk,
    input  io_enable,
    input [31:0] io_addr,
    output[31:0] io_out
);

  wire[31:0] T0;
  reg [31:0] mem [7:0];
  wire[31:0] T1;
  wire[31:0] T2;
  wire[31:0] T3;
  wire[31:0] T4;
  wire[31:0] T5;
  wire[2:0] T6;
  wire[2:0] T7;

`ifndef SYNTHESIS
  integer initvar;
  initial begin
    #0.001;
`ifdef RANDOM_SEED
    initvar = $random(`RANDOM_SEED);
`endif
    #0.001;
    for (initvar = 0; initvar < 8; initvar = initvar+1)
      mem[initvar] = {1{$random}};
  end
`endif

  assign io_out = T0;
  assign T0 = mem[T7];
  assign T2 = T3;
  assign T3 = T5 | T4;
  assign T4 = T0 & 32'hff;
  assign T5 = T0 & 32'hff00;
  assign T6 = io_addr[2'h2:1'h0];
  assign T7 = io_addr[2'h2:1'h0];

  always @(posedge clk) begin
    if (io_enable)
      mem[T6] <= T2;
  end
endmodule

