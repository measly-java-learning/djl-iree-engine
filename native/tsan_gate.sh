#!/usr/bin/env bash
# TSan gate for the local-task worker pool. NOT a GitHub CI job: TSan needs ASLR
# disabled (setarch -R), unavailable on GitHub-hosted container runners.
#
# STATUS (2026-07-22): BLOCKED — this has reported FALSE POSITIVES on the first observed
# iteration in every run to date (a measured result, not a construction guarantee), not a
# passing gate. TSan needs the whole program instrumented, but the iree-runtime-dist `default`
# artifact is an uninstrumented Release build (BUILDINFO: variant=default,
# CMAKE_BUILD_TYPE=Release; zero __tsan symbols in lib/*.a). So TSan cannot see IREE's own
# synchronization (iree_atomic, task-executor semaphores, resource-set refcounts) and flags
# the normal main<->worker submit/execute and refcounted-free handoffs as races. The harness
# itself completes correctly every run. This becomes a REAL gate only once the dist ships a
# TSan-instrumented runtime variant.
#   Tracking: https://github.com/measly-java-learning/iree-runtime-dist/issues/9
#
# Kept in the tree because the wiring is correct and ready: the moment an instrumented
# runtime variant exists, this script is the gate. It drives the leak harness with
# local-task so IREE's worker pool actually runs — the only configuration where TSan could
# find a race. The ASan/LSan gate (native/build_qa.sh) stays local-sync/deterministic and
# IS enforced.
#
# NOTE: until #9 is resolved this script EXITS NON-ZERO (TSan's exit code) on the documented
# false positives — that is expected, not a gate failure. See STATUS above before reacting.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-500}"
BUILD_DIR="${BUILD_DIR:-native/tsan}"

cmake -S native -B "${BUILD_DIR}" -DIREE_DJL_TSAN=ON -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=thread -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=thread"
cmake --build "${BUILD_DIR}" --target iree_leak_harness

echo "--- TSan leak harness (local-task, ${ITERS} iterations) ---"
# setarch -R disables ASLR for this process; without it TSan's shadow mapping fails.
setarch "$(uname -m)" -R \
  ./"${BUILD_DIR}"/iree_leak_harness src/test/resources/models/add.vmfb "${ITERS}" local-task
echo "--- TSan gate PASS ---"
