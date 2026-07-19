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
# IMPORTANT - compiler must match the LINKED runtime, not just any pip release:
# the C++ facade links against /home/corey/workspace/iree-build (source commit
# a869dc3, dated 2026-07-17, version line 3.12.0.dev) - that is the runtime
# add.vmfb is actually loaded into in production, not whatever iree-run-module
# happens to be on PATH. Pip's *stable* release (3.11.0) is a different, older
# ABI and produced a real HAL signature mismatch when the resulting vmfb was
# run under iree-build's own iree-run-module:
#   INTERNAL; import function hal.command_buffer.dispatch signature mismatch
#   between module and source hal; expected ...IID_v but got ...IID_v
# The fix is to compile with the pip *nightly* whose date matches the linked
# build: iree-base-compiler==3.12.0rc20260717 (installed via
# `uv pip install --python .venv --find-links https://iree.dev/pip-release-links.html
# 'iree-base-compiler==3.12.0rc20260717'` - pip's plain index has no nightlies,
# they're only on iree.dev's find-links page). This was verified definitively
# by running the recompiled add.vmfb through iree-build's OWN
# /home/corey/workspace/iree-build/tools/iree-run-module (the a869dc3 binary,
# not the venv's copy) and confirming no signature mismatch and the correct
# `4xf32=11 22 33 44` output. iree-base-runtime==3.12.0rc20260717 was also
# installed into .venv so the venv's iree-run-module/iree-dump-module (used
# for quick local iteration) match the same ABI as the linked build.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "${here}" rev-parse --show-toplevel)"
out="${here}/../src/test/resources/models/add.vmfb"
mkdir -p "$(dirname "${out}")"

IREE_COMPILE="${IREE_COMPILE:-$(command -v iree-compile || echo "${repo_root}/.venv/bin/iree-compile")}"

if [[ ! -x "${IREE_COMPILE}" ]] && ! command -v "${IREE_COMPILE}" >/dev/null 2>&1; then
  echo "iree-compile not found (looked for '${IREE_COMPILE}'). Install it with:" >&2
  echo "  uv pip install --python ${repo_root}/.venv --find-links https://iree.dev/pip-release-links.html 'iree-base-compiler==3.12.0rc20260717'" >&2
  exit 1
fi

# --iree-hal-target-device / --iree-hal-local-target-device-backends are the
# current (3.12.0rc20260717) flag spelling and were confirmed to work; the
# brief warned an older compiler might need the legacy
# --iree-hal-target-backends=llvm-cpu spelling instead. --iree-llvmcpu-target-cpu=host
# silences the "generic CPU" perf warning; the fixture is only ever run on the
# machine that produced it.
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
