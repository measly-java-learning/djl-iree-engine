// Two f32 tensors of 4 elements, added elementwise.
// Deliberately trivial: this exists to exercise the runtime layer, not math.
func.func @add(%lhs: tensor<4xf32>, %rhs: tensor<4xf32>) -> tensor<4xf32> {
  %result = arith.addf %lhs, %rhs : tensor<4xf32>
  return %result : tensor<4xf32>
}
