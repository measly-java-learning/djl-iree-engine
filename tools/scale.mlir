// One parameter-backed global and one function that uses it.
// Deliberately trivial: this exercises the parameter-loading path, not math.
//
// NOTE: the global must REFERENCE a parameter (#stream.parameter.named) rather
// than hold a constant. A constant global is const-eval'd away before the
// parameter-export pass runs, which is why --iree-parameter-export produces no
// archive at all for a constant. Verified 2026-07-25.
util.global private @weight = #stream.parameter.named<"model"::"weight"> : tensor<4xf32>
func.func @scale(%input: tensor<4xf32>) -> tensor<4xf32> {
  %w = util.global.load @weight : tensor<4xf32>
  %result = arith.mulf %input, %w : tensor<4xf32>
  return %result : tensor<4xf32>
}
