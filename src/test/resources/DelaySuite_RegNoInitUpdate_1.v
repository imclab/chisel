module DelaySuite_RegNoInitUpdate_1(input clk,
    output[31:0] io_out
);

  reg[31:0] res;
  wire[31:0] T0;

`ifndef SYNTHESIS
  integer initvar;
  initial begin
    #0.001;
`ifdef RANDOM_SEED
    initvar = $random(`RANDOM_SEED);
`endif
    #0.001;
    res = {1{$random}};
  end
`endif

  assign io_out = res;
  assign T0 = res + 32'h1;

  always @(posedge clk) begin
    res <= T0;
  end
endmodule

