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
# The local IREE build has IREE_BUILD_COMPILER=OFF, so it has no iree-compile.
# Enabling it would pull in a full LLVM build (hours, tens of GB) for a tool we
# invoke twice and never link. pip ships a prebuilt binary instead.
#
# The local IREE build (/home/corey/workspace/iree-build) is also built from a
# newer/different commit than the pinned pip compiler/runtime (3.11.0), which
# causes a HAL ABI mismatch at VM context creation time if you mix the two
# (`iree-compile` from pip + `iree-run-module` from the local build). Use the
# matching pip-installed `iree-base-runtime==3.11.0` for `iree-run-module` /
# `iree-dump-module` instead of the local build's copies.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out="${here}/../src/test/resources/models/add.vmfb"
mkdir -p "$(dirname "${out}")"

IREE_COMPILE="${IREE_COMPILE:-$(command -v iree-compile || echo "${repo_root}/.venv/bin/iree-compile")}"

if [[ ! -x "${IREE_COMPILE}" ]] && ! command -v "${IREE_COMPILE}" >/dev/null 2>&1; then
  echo "iree-compile not found (looked for '${IREE_COMPILE}'). Install it with:" >&2
  echo "  uv pip install --python ${repo_root}/.venv/bin/python iree-base-compiler" >&2
  exit 1
fi

# --iree-hal-target-device / --iree-hal-local-target-device-backends are the
# current (3.11.0) flag spelling and were confirmed to work; the brief warned
# an older compiler might need the legacy --iree-hal-target-backends=llvm-cpu
# spelling instead. --iree-llvmcpu-target-cpu=host silences the "generic CPU"
# perf warning; the fixture is only ever run on the machine that produced it.
"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=host \
  "${here}/add.mlir" -o "${out}"

echo "wrote ${out}"

# Confirm the exported symbol rather than assuming it (spec §12). Prefer the
# pip-installed dump tool (matching iree-compile's ABI/version) and fall back
# to the local build's copy only if pip's isn't present.
DUMP_MODULE="${IREE_DUMP_MODULE:-}"
if [[ -z "${DUMP_MODULE}" ]]; then
  if [[ -x "${repo_root}/.venv/bin/iree-dump-module" ]]; then
    DUMP_MODULE="${repo_root}/.venv/bin/iree-dump-module"
  else
    DUMP_MODULE="${IREE_INSTALL:-/home/corey/workspace/iree-build}/tools/iree-dump-module"
  fi
fi
if [[ -x "${DUMP_MODULE}" ]]; then
  echo "--- exported functions ---"
  "${DUMP_MODULE}" "${out}" | awk '/^Exported Functions:/{f=1} f{print} f && /^$/{exit}'
fi
