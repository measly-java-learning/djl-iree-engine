#!/usr/bin/env bash
# Build + run the native QA targets (Catch2 units + ASan/LSan leak harness) against the
# resolved iree-runtime-dist runtime. NOT part of the shipping build: the QA targets are
# built with AddressSanitizer/LeakSanitizer into a distinct tree (native/qa), separate from
# the Release .so (native/build via native/build.sh).
#
# JVM-free: under a sanitizer, native/CMakeLists.txt skips the JNI shim, so NO JDK/JAVA_HOME
# is needed. In GitHub Actions, run this in the SAME manylinux_2_28 container as native/build.sh.
# TSan is intentionally absent — it needs ASLR disabled and runs as a local gate only
# (native/tsan_gate.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

ITERS="${ITERS:-1000}"

# QA is the only ASan consumer; install the toolset's ASan runtime here.
if command -v dnf >/dev/null 2>&1; then
  echo "--- Installing ASan runtime (dnf), may appear to hang ---"
  TOOLSET_VER="$(gcc -dumpversion | cut -d. -f1)"
  dnf install -y -q "gcc-toolset-${TOOLSET_VER}-libasan-devel" || true
fi

JOBS="${JOBS:-$(nproc)}"
rm -rf native/qa
cmake -B native/qa -S native -G "Unix Makefiles" -DIREE_DJL_SANITIZE=ON \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer -g" \
  -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address"
cmake --build native/qa --target iree_runtime_test iree_leak_harness -j"${JOBS}"

echo "--- Catch2 unit suite ---"
./native/qa/iree_runtime_test

echo "--- ASan/LSan leak harness (${ITERS} iterations, local-sync) ---"
./native/qa/iree_leak_harness src/test/resources/models/add.vmfb "${ITERS}"

echo "--- native QA PASS ---"
