#!/usr/bin/env bash
# Bench-only fixture isolating input-marshalling cost (as opposed to compute or
# per-tensor byte-copy cost, which bigscale_*.vmfb isolates). Peer of
# export_bigscale.sh: same compiler pin (iree-base-compiler MUST be 3.11.0 to
# match the linked runtime commit e4a3b0405d7d23554da26403658d0e8c3c5ecf25),
# same --iree-hal flags, same IREE_TARGET_TRIPLE pass-through.
#
# Writes a single module with ${IREE_MANYINPUTS_COUNT:-30} tensor<4xf32>
# inputs summed elementwise into one tensor<4xf32> output -- each input tiny
# (16 bytes), so JNI per-input marshalling overhead dominates end-to-end time
# rather than being masked by memcpy cost. Capped at 30 (31 HAL bindings
# including the output), not the 41 the analogous djl-executorch-engine
# benchmark uses (PR #85 there): the addf chain fuses into a single dispatch,
# and IREE's HAL command buffer rejects more than 32 bindings per dispatch
# (OUT_OF_RANGE at load, confirmed empirically at 41: "binding count 42 > 32").
#
# The .mlir source is generated here and deleted; never committed. The
# .vmfb lands in ${IREE_FIXTURE_DIR:-example/build/models} -- a bench-time
# artifact, never committed (like example/build/models/mobilenet_v2.vmfb and
# build/bench-models/bigscale_*.vmfb; **/build/ is ignored).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out_dir="${IREE_FIXTURE_DIR:-${repo_root}/example/build/models}"
mkdir -p "${out_dir}"

n="${IREE_MANYINPUTS_COUNT:-30}"
out="${out_dir}/manyinputs_${n}.vmfb"

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

mlir="${out_dir}/manyinputs_${n}.mlir"
{
  args=""
  for ((i = 0; i < n; i++)); do
    args+="%i${i}: tensor<4xf32>"
    if ((i < n - 1)); then args+=", "; fi
  done
  echo "func.func @main(${args}) -> tensor<4xf32> {"
  echo "  %acc0 = arith.addf %i0, %i1 : tensor<4xf32>"
  for ((i = 2; i < n; i++)); do
    echo "  %acc$((i - 1)) = arith.addf %acc$((i - 2)), %i${i} : tensor<4xf32>"
  done
  echo "  return %acc$((n - 2)) : tensor<4xf32>"
  echo "}"
} > "${mlir}"

"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${TRIPLE_ARGS[@]}" \
  "${mlir}" -o "${out}"
rm -f "${mlir}"

echo "wrote ${out}"
