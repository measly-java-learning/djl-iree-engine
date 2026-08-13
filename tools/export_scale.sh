#!/usr/bin/env bash
# Regenerates the IRPA fixtures. Peer of export_add.sh; see that file for
# the compiler-version pairing rationale (iree-base-compiler MUST be 3.11.0 to
# match the linked runtime commit e4a3b0405d7d23554da26403658d0e8c3c5ecf25).
#
# Entry point is "module.scale" (fully qualified) for the runtime API, but
# iree-run-module's --function= takes the unqualified "scale".
#
# GOTCHA (verified 2026-07-25, iree 3.11.0): `iree-create-parameters
# --data=KEY=SHAPE=PATTERN` is broken -- it writes a 4096-byte stub then fails
# with a misleading "write failed, possibly out of disk space". Use --splat for
# patterned values, or --data=KEY=SHAPE for zeroed storage. Both work.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out_dir="${IREE_FIXTURE_DIR:-${repo_root}/src/test/resources/models}"
mkdir -p "${out_dir}"

IREE_COMPILE="${IREE_COMPILE:-${repo_root}/.venv/bin/iree-compile}"
IREE_CREATE_PARAMS="${IREE_CREATE_PARAMS:-${repo_root}/.venv/bin/iree-create-parameters}"

for tool in "${IREE_COMPILE}" "${IREE_CREATE_PARAMS}"; do
  if [[ ! -x "${tool}" ]]; then
    echo "missing ${tool}. Install with:" >&2
    echo "  uv pip install --python ${repo_root}/.venv 'iree-base-compiler==3.11.0'" >&2
    exit 1
  fi
done

# target-cpu=generic keeps the fixture runnable on any host and silences
# the generic-CPU perf warning. Perf is irrelevant here. IREE_TARGET_TRIPLE
# adds a cross-compilation target for the committed per-arch fixtures.
TRIPLE_ARGS=()
if [[ -n "${IREE_TARGET_TRIPLE:-}" ]]; then
  TRIPLE_ARGS+=(--iree-llvmcpu-target-triple="${IREE_TARGET_TRIPLE}")
fi

"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${TRIPLE_ARGS[@]}" \
  "${here}/scale.mlir" -o "${out_dir}/scale.vmfb"

# Splat: value 2.0, NO on-disk storage. Used by the wiring/math tests.
"${IREE_CREATE_PARAMS}" --splat=weight=4xf32=2.0 \
  --output="${out_dir}/scale_weights.irpa"

# Zeroed: real on-disk storage. Used by the FILE-backed checks, which need an
# entry whose bytes actually live on disk. (Not "needs mapped bytes" -- IREE
# pread(2)s the data spans off the fd it owns; it only mmaps the file
# transiently to parse the index. A splat archive has no on-disk storage at
# all, so it never takes the FILE branch.)
#
# MUST stay --data= (FILE-backed entry, type 0x02), never --splat= (SPLAT entry,
# type 0x01, no on-disk storage): iree_params_test's FILE-backed test and the
# leak harness's parameter cycle both depend on this fixture taking the FILE
# branch in iree_io_parameter_index_add. A splat here would make those checks
# pass vacuously -- see docs/2026-07-25-irpa-spike-findings.md's FILE-backed
# differential section.
"${IREE_CREATE_PARAMS}" --data=weight=4xf32 \
  --output="${out_dir}/scale_weights_zero.irpa"

"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${TRIPLE_ARGS[@]}" \
  "${here}/scale2.mlir" -o "${out_dir}/scale2.vmfb"

# Second archive, bound under a different scope at load time.
"${IREE_CREATE_PARAMS}" --splat=offset=4xf32=10.0 \
  --output="${out_dir}/scale2_bias.irpa"

echo "wrote:"
ls -la "${out_dir}"/scale.vmfb "${out_dir}"/scale2.vmfb "${out_dir}"/scale_weights*.irpa "${out_dir}"/scale2_bias.irpa
