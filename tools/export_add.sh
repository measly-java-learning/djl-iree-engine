#!/usr/bin/env bash
# CONFIRMED ENTRY POINT (iree-dump-module, 2026-07-19): module.add
#
# `iree-dump-module` lists the exported symbol as unqualified `add` inside a
# module named `module` (MLIR gave the func no module wrapper, so IREE used
# the default module name "module"). The IREE runtime API consumed by the
# JNI engine (`iree_runtime_session_lookup_function`, which downstream tasks
# 3/4/7/9 call) resolves fully-qualified names of the form
# "<module_name>.<function_name>" (see runtime/src/iree/runtime/session.h),
# defaulting the module name to "module" when the source has none - so the
# fully-qualified entry point is "module.add". Note this differs from the
# `iree-run-module` CLI's `--function=` flag, which intentionally takes the
# *unqualified* name ("add") because it resolves directly against the single
# loaded module rather than through context resolution (see
# runtime/src/iree/tooling/run_module.c, comment: "shorthand --function= flag
# that doesn't need the module name"). Verified empirically: `--function=add`
# succeeds, `--function=module.add` fails with NOT_FOUND on this CLI.
#
# Produces add.vmfb using pip's iree-compile.
#
# The engine now links the published `iree-runtime-dist` v3.11.0-3 artifact
# (native/cmake/IreeRuntimePin.cmake), not a local IREE source build. There is
# no local IREE tree and no local iree-compile to enable: the dist ships
# IREE_BUILD_COMPILER=OFF by design (it is a runtime-only artifact) and pip
# supplies the compiler instead.
#
# IMPORTANT - compiler must match the LINKED runtime, not just any pip release:
# the dist's manifest.json records the pairing contract directly --
#   iree_compile_version: "3.11.0"
#   runtime_commit:        e4a3b0405d7d23554da26403658d0e8c3c5ecf25
#   iree_tag:               v3.11.0
# so the correct compiler is stable `iree-base-compiler==3.11.0` from pip's
# plain index (`uv pip install --python .venv 'iree-base-compiler==3.11.0'`) --
# never a nightly, never --find-links, never a `main`/from-source checkout.
# Mixing a main-branch runtime with a stable compiler (or vice versa) is
# exactly the import-signature mismatch this project hit previously; both
# sides of the pair must be the same tagged, stable release. Confirmed: the
# installed compiler's own --version output embeds commit
# e4a3b0405d7d23554da26403658d0e8c3c5ecf25, matching the dist's
# runtime_commit exactly.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out="${here}/../src/test/resources/models/add.vmfb"
mkdir -p "$(dirname "${out}")"

IREE_COMPILE="${IREE_COMPILE:-$(command -v iree-compile || echo "${repo_root}/.venv/bin/iree-compile")}"

if [[ ! -x "${IREE_COMPILE}" ]] && ! command -v "${IREE_COMPILE}" >/dev/null 2>&1; then
  echo "iree-compile not found (looked for '${IREE_COMPILE}'). Install it with:" >&2
  echo "  uv pip install --python ${repo_root}/.venv 'iree-base-compiler==3.11.0'" >&2
  exit 1
fi

# --iree-hal-target-device / --iree-hal-local-target-device-backends are the
# current (3.11.0) flag spelling and were confirmed to work; an older
# compiler might need the legacy --iree-hal-target-backends=llvm-cpu spelling
# instead. --iree-llvmcpu-target-cpu=host silences the "generic CPU" perf
# warning; the fixture is only ever run on the machine that produced it.
"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=host \
  "${here}/add.mlir" -o "${out}"

echo "wrote ${out}"

# Confirm the exported symbol rather than assuming it (spec §12). Use the
# pip-installed dump tool, which matches iree-compile's version/ABI.
DUMP_MODULE="${IREE_DUMP_MODULE:-${repo_root}/.venv/bin/iree-dump-module}"
if [[ -x "${DUMP_MODULE}" ]]; then
  echo "--- exported functions ---"
  "${DUMP_MODULE}" "${out}" | awk '/^Exported Functions:/{f=1} f{print} f && /^$/{exit}'
fi
