#!/usr/bin/env bash
# TSan gate for the local-task worker pool. NOT a GitHub CI job: TSan needs ASLR
# disabled (setarch -R), unavailable on GitHub-hosted container runners.
#
# This is a REAL gate: a report here is a finding, not noise. Treat a non-zero exit as a
# failure to triage.
#
# What makes it real is -DIREE_RUNTIME_VARIANT=tsan below. TSan needs the whole program
# instrumented; against the shipping `default` runtime (an uninstrumented Release build,
# zero __tsan symbols) it could not see IREE's own synchronization — iree_atomic, the
# task-executor semaphores, resource-set refcounts — and reported every normal
# main<->worker handoff as a race. The pin now carries a `tsan` variant of the same release
# built with -fsanitize=thread, so those handoffs are visible and the gate runs clean.
# CMake refuses the mismatched pairings (see native/CMakeLists.txt) rather than letting a
# run look enforced while measuring nothing.
#
# It drives the leak harness with local-task so IREE's worker pool actually runs — the only
# configuration where a race is reachable. The ASan/LSan gate (native/build_qa.sh) stays
# local-sync/deterministic.
#
# Remaining limits, both about coverage rather than correctness:
#   - Linux only, and not a GitHub CI job (see the ASLR note above). Nothing enforces this
#     automatically; it is a local gate someone has to run.
#   - The dist is clang-built and the shim here is built by the host compiler. The TSan
#     runtime ABI is shared, so the mix links and runs, but the two halves are instrumented
#     by different compilers.
#   - Only the vmfb the harness loads is exercised. A race in a code path no fixture reaches
#     is still invisible.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-500}"
BUILD_DIR="${BUILD_DIR:-native/tsan}"

cmake -S native -B "${BUILD_DIR}" -DIREE_DJL_TSAN=ON -DIREE_RUNTIME_VARIANT=tsan \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=thread -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=thread"
cmake --build "${BUILD_DIR}" --target iree_leak_harness

echo "--- TSan leak harness (local-task, ${ITERS} iterations) ---"
# setarch -R disables ASLR for this process; without it TSan's shadow mapping fails.
setarch "$(uname -m)" -R \
  ./"${BUILD_DIR}"/iree_leak_harness src/test/resources/models/add.vmfb "${ITERS}" local-task
echo "--- TSan gate PASS ---"
