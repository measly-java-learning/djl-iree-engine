#!/usr/bin/env bash
# Assert the Windows build links the CRT statically (/MT).
#
# Replaces the ideal test — loading the DLL on a Windows image that has never had a VC++
# redistributable installed — which we cannot run: no such machine is available, and a dev box proves
# nothing because it already has the runtime. These two static checks stand in for it.
#
# MSVC records the CRT choice per-object as /DEFAULTLIB directives:
#   /MT (static)  -> LIBCMT / LIBCPMT
#   /MD (dynamic) -> MSVCRT / MSVCPRT
# A mismatch is NOT reliably caught at link time: measured upstream, a /MD consumer linked a /MT
# ExecuTorch prefix with no LNK2005 and not even an LNK4098. The hazard is at runtime — two CRTs, two
# heaps, corruption when an allocation crosses the boundary. Hence a direct inspection.
#
# TWO TRAPS, both of which cost the producer repo real debugging time:
#  - Flags are passed as -nologo -directives, NOT /nologo. Under MSYS/Git-Bash a leading '/' is
#    path-converted (/nologo -> C:\Program Files\Git\nologo) and dumpbin fails on a garbage filename.
#  - Assertions are POSITIVE: a lib must CARRY the expected marker. An absence-only check
#    ("no MSVCRT found") reports PASS when dumpbin failed to run at all — that exact bug once passed
#    18 libraries green while the tool was erroring on every one of them.
#
# Run inside Git-Bash from an activated VS dev shell (needs dumpbin).
#
# Usage: check_windows_crt.sh <build-dir> [dll-path]
#   <build-dir>  scanned for *.lib; every CRT-bearing one must carry the static marker
#   [dll-path]   optional; when given, its import table is checked too
#
# The DLL argument is optional because this runs against TWO trees. The shim tree (native/build)
# produces a DLL. The QA tree (native/qa) does not — it produces a test executable — but it is the
# tree containing the FetchContent'd Catch2, which is the single most likely place for the static-CRT
# setting to stop propagating, and a /MD Catch2 inside a /MT test exe links with no diagnostic at all.
set -uo pipefail
usage_err() { echo "usage: check_windows_crt.sh <build-dir> [dll-path]" >&2; exit 2; }
BUILD_DIR="${1:-}"; [ -n "${BUILD_DIR}" ] || usage_err
DLL="${2:-}"

command -v dumpbin >/dev/null 2>&1 \
  || { echo "FAIL: dumpbin not on PATH — run inside an activated VS dev shell" >&2; exit 1; }
[ -d "${BUILD_DIR}" ] || { echo "FAIL: no build dir at ${BUILD_DIR}" >&2; exit 1; }
[ -z "${DLL}" ] || [ -f "${DLL}" ] || { echo "FAIL: no DLL at ${DLL}" >&2; exit 1; }

rc=0

echo "== 1/2 CRT directive scan: every CRT-bearing .lib must carry LIBCMT/LIBCPMT =="
# Three-way classification, NOT two-way. The naive version ("must carry the static marker, else FAIL")
# false-positives on import libraries: an import lib (e.g. iree_djl.lib, generated beside the
# DLL) holds import descriptor records rather than COFF objects, so it carries no linker directives at
# all and legitimately has no CRT opinion to state. Measured: dumpbin exits 0 with 0 DEFAULTLIB lines.
#
# The classification keys on whether the archive states a CRT at all, never on its filename:
#   dumpbin fails            -> FAIL   (the condition this gate exists to notice; never swallowed)
#   no DEFAULTLIB: whatever  -> SKIP   (import library, or /Zl) — counted and printed, never silent
#   has DEFAULTLIB:          -> must be static, must not be dynamic
# This cannot let a /MD library through: a /MD archive DOES carry DEFAULTLIB:MSVCRT, so it lands in
# the third bucket and fails there.
#
# All matching is -i: real dumpbin output is mixed-case (LIBCMT but libcpmt).
scanned=0; skipped=0
while IFS= read -r lib; do
  name="$(basename "${lib}")"
  # Capture stdout+stderr AND the exit status. Do NOT `|| true` here: a dumpbin failure is exactly the
  # condition this gate exists to notice, and swallowing it is what made the old scan useless.
  out="$(dumpbin -nologo -directives "${lib}" 2>&1)"; drc=$?
  if [ "${drc}" -ne 0 ]; then
    echo "  ERROR ${name} -> dumpbin exited ${drc}:"
    head -5 <<< "${out}" | sed 's/^/          /'
    rc=1; continue
  fi
  if ! grep -qi 'DEFAULTLIB:' <<< "${out}"; then
    echo "  skip  ${name} (no linker directives — import library or /Zl)"
    skipped=$((skipped+1)); continue
  fi
  scanned=$((scanned+1))
  # Positive assertion. A C-only lib carries LIBCMT with no LIBCPMT, so the pattern is an OR.
  if grep -qiE 'DEFAULTLIB:"?(LIBCMT|LIBCPMT)' <<< "${out}"; then
    if grep -qiE 'DEFAULTLIB:"?(MSVCRT|MSVCPRT)' <<< "${out}"; then
      echo "  MIXED ${name} -> carries BOTH static and dynamic CRT markers"; rc=1
    else
      echo "  ok    ${name}"
    fi
  else
    echo "  FAIL  ${name} -> states a CRT but not a static one"; rc=1
  fi
done < <(find "${BUILD_DIR}" -name '*.lib' -type f)

echo "  -- ${scanned} libs asserted, ${skipped} skipped"
# A scan that asserted nothing must not report success. Guarding on `scanned` rather than on the file
# count is deliberate: a tree containing only import libraries would otherwise pass having proved
# nothing at all.
if [ "${scanned}" -eq 0 ]; then
  echo "FAIL: no CRT-bearing .lib found under ${BUILD_DIR} — the scan asserted nothing" >&2
  rc=1
fi

if [ -z "${DLL}" ]; then
  [ "${rc}" -eq 0 ] && echo "PASS: static CRT across ${scanned} libs in ${BUILD_DIR} (no DLL to check)" \
                    || echo "FAIL: windows CRT check"
  exit "${rc}"
fi

echo "== 2/2 Import table: the DLL must not depend on a redistributable CRT =="
deps="$(dumpbin -nologo -dependents "${DLL}" 2>&1)"; drc=$?
[ "${drc}" -eq 0 ] || { echo "FAIL: dumpbin -dependents exited ${drc}"; echo "${deps}"; exit 1; }
# Positive assertion first: the output must actually look like a dependents listing, otherwise a grep
# that finds no VCRUNTIME below would be meaningless.
grep -qi 'KERNEL32.dll' <<< "${deps}" \
  || { echo "FAIL: dependents listing has no KERNEL32.dll — output is not what we think it is"; echo "${deps}"; exit 1; }
if grep -iE 'VCRUNTIME[0-9]*\.dll|MSVCP[0-9]*\.dll' <<< "${deps}"; then
  echo "FAIL: DLL imports a redistributable CRT (above) — the /MT switch did not fully apply"
  rc=1
else
  echo "  ok    no VCRUNTIME/MSVCP imports"
fi

[ "${rc}" -eq 0 ] && echo "PASS: windows CRT is static" || echo "FAIL: windows CRT check"
exit "${rc}"
