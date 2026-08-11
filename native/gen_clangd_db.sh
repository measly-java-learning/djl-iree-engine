#!/usr/bin/env bash
# Regenerates the clangd compile database in native/build-clangd/. See .clangd for why this exists.
#
# Run it once after cloning, and again after any bump of native/cmake/IreeRuntimePin.cmake or a
# change to the compile flags in native/CMakeLists.txt -- the database is otherwise never
# refreshed, and a stale one makes clangd resolve against the OLD runtime headers silently.
#
# Configure only: no compilation happens, the database is written at CMake configure time.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_DIR="${REPO_ROOT}/native/build-clangd"

# find_package(JNI REQUIRED) in native/CMakeLists.txt is fatal without JDK headers, so a JDK
# must be findable on the host. Honor an explicit JAVA_HOME; otherwise derive one from `java`
# on PATH, then from /usr/lib/jvm (or /usr/java). Fail loudly, with a message that names the
# cause, before cmake gets a chance to.
if [ -z "${JAVA_HOME:-}" ]; then
  jni_h=""
  if command -v java >/dev/null 2>&1; then
    java_bin="$(readlink -f "$(command -v java)")"
    if [[ "${java_bin}" == */bin/java ]]; then
      jni_h="${java_bin%/bin/java}/include/jni.h"
    fi
  fi
  if [ -z "${jni_h}" ] || [ ! -f "${jni_h}" ]; then
    jni_h="$(find /usr/lib/jvm /usr/java -path '*/include/jni.h' -print -quit 2>/dev/null)"
  fi
  if [ -n "${jni_h}" ] && [ -f "${jni_h}" ]; then
    JAVA_HOME="${jni_h%/include/jni.h}"
    export JAVA_HOME
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "${JAVA_HOME}/include/jni.h" ]; then
  echo "gen_clangd_db.sh: no JDK found. native/CMakeLists.txt needs JNI headers" >&2
  echo "(find_package(JNI REQUIRED)); install a JDK or set JAVA_HOME to its root." >&2
  exit 1
fi

# The shipping tree (native/build) is configured by native/build.sh inside the manylinux
# container, where the repo sits at /workspace -- a database cached there holds container paths
# that host clangd cannot resolve. This tree is written by nothing but this script, and the QA
# tree (native/qa) is separate and sanitizer-only: its flags are not the ones clangd should
# show. One configure covers every source -- native/CMakeLists.txt adds core/, jni/, test/,
# harness/, and bench/ unconditionally here (the JNI shim is skipped only under
# -DIREE_DJL_SANITIZE/-DIREE_DJL_TSAN, which we never pass). native/CMakeLists.txt does not
# itself set CMAKE_EXPORT_COMPILE_COMMANDS, so pass it explicitly.
cmake -S "${REPO_ROOT}/native" -B "${DB_DIR}" -G Ninja \
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON >/dev/null

python3 - "${DB_DIR}" <<'PY'
import json, pathlib, sys

db = pathlib.Path(sys.argv[1]) / "compile_commands.json"
entries = json.loads(db.read_text())
own = sorted(e["file"].split("/native/")[-1] for e in entries if "_deps" not in e["file"])
print(f"{len(entries)} entries -> {db}")
print("project sources indexed: " + ", ".join(own))
PY
