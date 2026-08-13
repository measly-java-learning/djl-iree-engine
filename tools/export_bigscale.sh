#!/usr/bin/env bash
# Bench-time fixtures for the copy-cost measurement (native/bench/iree_copy_bench).
# Peer of export_scale.sh: same compiler pin (iree-base-compiler MUST be 3.11.0
# to match the linked runtime commit e4a3b0405d7d23554da26403658d0e8c3c5ecf25),
# same --iree-hal flags, same IREE_TARGET_TRIPLE pass-through.
#
# Writes four fixed-shape elementwise-mul modules (y = 2*x, one f32 input, one
# f32 output) over element counts {4096, 65536, 1048576, 16777216} — i.e.
# 16KB / 256KB / 4MB / 64MB per tensor. Output size == input size, which makes
# the per-call copy cost exactly input_copy + output_copy = 2 * memcpy(N).
#
# The .mlir sources are generated here and deleted; never committed. Output
# .vmfb files land in ${IREE_FIXTURE_DIR:-build/bench-models} — bench-time
# artifacts, never committed (like example/build/models; **/build/ is ignored).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out_dir="${IREE_FIXTURE_DIR:-${repo_root}/build/bench-models}"
mkdir -p "${out_dir}"

IREE_COMPILE="${IREE_COMPILE:-${repo_root}/.venv/bin/iree-compile}"

if [[ ! -x "${IREE_COMPILE}" ]]; then
  echo "missing ${IREE_COMPILE}. Install with:" >&2
  echo "  uv pip install --python ${repo_root}/.venv 'iree-base-compiler==3.11.0'" >&2
  exit 1
fi

# target-cpu=generic keeps the fixture runnable on any host (same rationale as
# export_scale.sh). IREE_TARGET_TRIPLE adds a cross-compilation target.
TRIPLE_ARGS=()
if [[ -n "${IREE_TARGET_TRIPLE:-}" ]]; then
  TRIPLE_ARGS+=(--iree-llvmcpu-target-triple="${IREE_TARGET_TRIPLE}")
fi

for n in 4096 65536 1048576 16777216; do
  mlir="${out_dir}/bigscale_${n}.mlir"
  cat > "${mlir}" <<EOF
func.func @main(%x: tensor<${n}xf32>) -> tensor<${n}xf32> {
  %c = arith.constant dense<2.0> : tensor<${n}xf32>
  %y = arith.mulf %x, %c : tensor<${n}xf32>
  return %y : tensor<${n}xf32>
}
EOF
  "${IREE_COMPILE}" \
    --iree-hal-target-device=local \
    --iree-hal-local-target-device-backends=llvm-cpu \
    --iree-llvmcpu-target-cpu=generic \
    "${TRIPLE_ARGS[@]}" \
    "${mlir}" -o "${out_dir}/bigscale_${n}.vmfb"
  rm -f "${mlir}"
done

echo "wrote:"
ls -la "${out_dir}"/bigscale_*.vmfb
