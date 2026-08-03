// Two parameters in DIFFERENT scopes, to prove a provider array composes.
util.global private @weight = #stream.parameter.named<"model"::"weight"> : tensor<4xf32>
util.global private @offset = #stream.parameter.named<"bias"::"offset"> : tensor<4xf32>
func.func @scale2(%input: tensor<4xf32>) -> tensor<4xf32> {
  %w = util.global.load @weight : tensor<4xf32>
  %b = util.global.load @offset : tensor<4xf32>
  %scaled = arith.mulf %input, %w : tensor<4xf32>
  %result = arith.addf %scaled, %b : tensor<4xf32>
  return %result : tensor<4xf32>
}
