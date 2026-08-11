#!/usr/bin/env bash
# OOM-contract fixture for the JNI output-marshalling tests (IreeNativeOomTest,
# ./gradlew oomTest). Peer of export_bigscale.sh: same compiler pin
# (iree-base-compiler MUST be 3.11.0 to match the linked runtime commit
# e4a3b0405d7d23554da26403658d0e8c3c5ecf25), same --iree-hal flags, same
# IREE_TARGET_TRIPLE pass-through.
#
# Writes a single splat module: one f32 input, one 134217728-element f32 output
# (512 MiB). 512 MiB sits BELOW the 2 GiB JNI direct-buffer guard (so the invoke
# reaches the allocation path) and ABOVE the default MaxDirectMemorySize
# (-Xmx128m, so ByteBuffer.allocateDirect throws OutOfMemoryError
# deterministically) — the failure-contract the oomTest suite pins.
#
# The .mlir source is generated here and deleted; never committed. The .vmfb
# lands in ${IREE_FIXTURE_DIR:-build/oom-models} — a test-time artifact, never
# committed (like build/bench-models; **/build/ is ignored).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out_dir="${IREE_FIXTURE_DIR:-${repo_root}/build/oom-models}"
mkdir -p "${out_dir}"

IREE_COMPILE="${IREE_COMPILE:-${repo_root}/.venv/bin/iree-compile}"

if [[ ! -x "${IREE_COMPILE}" ]]; then
  echo "missing ${IREE_COMPILE}. Install with:" >&2
  echo "  uv pip install --python ${repo_root}/.venv 'iree-base-compiler==3.11.0'" >&2
  exit 1
fi

# target-cpu=generic keeps the fixture runnable on any host (same rationale as
# export_bigscale.sh). IREE_TARGET_TRIPLE adds a cross-compilation target.
TRIPLE_ARGS=()
if [[ -n "${IREE_TARGET_TRIPLE:-}" ]]; then
  TRIPLE_ARGS+=(--iree-llvmcpu-target-triple="${IREE_TARGET_TRIPLE}")
fi

# tensor.splat does NOT legalize on iree-compile 3.11.0 ("failed to legalize");
# linalg.fill is the verified formulation for a broadcast-to-134217728 splat.
mlir="${out_dir}/splat_134217728.mlir"
cat > "${mlir}" <<'EOF'
func.func @main(%x: tensor<1xf32>) -> tensor<134217728xf32> {
  %c0 = arith.constant 0 : index
  %s = tensor.extract %x[%c0] : tensor<1xf32>
  %init = tensor.empty() : tensor<134217728xf32>
  %0 = linalg.fill ins(%s : f32) outs(%init : tensor<134217728xf32>) -> tensor<134217728xf32>
  return %0 : tensor<134217728xf32>
}
EOF

"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${TRIPLE_ARGS[@]}" \
  "${mlir}" -o "${out_dir}/splat_134217728.vmfb"
rm -f "${mlir}"

echo "wrote:"
ls -la "${out_dir}/splat_134217728.vmfb"
