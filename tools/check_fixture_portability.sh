#!/usr/bin/env bash
# Every committed .vmfb must run on any CPU of its architecture and on any OS.
# Two properties, both asserted POSITIVELY:
#
#   cpu = "generic"      No host-specific ISA extensions baked into the embedded
#                        code. A fixture compiled with --iree-llvmcpu-target-cpu=host
#                        on an AVX-512 machine executes illegal instructions on a
#                        Zen 3 box. That regression shipped once (add.vmfb, cpu =
#                        "tigerlake"); this guard exists so it cannot ship twice.
#
#   target_triple *-elf  The OS-agnostic embedded-ELF form, e.g.
#                        x86_64-unknown-unknown-eabi-elf. IREE's embedded-ELF loader
#                        reads these on any operating system, which is why ONE x86_64
#                        fixture set serves Linux and Windows alike and no per-OS
#                        fixture directory is needed.
#
# Assertions are POSITIVE — a fixture must CARRY the expected value. An absence-only
# check ("no tigerlake found") reports PASS when grep never matched anything at all,
# e.g. against a truncated or non-vmfb file. For the same reason this script FAILS
# when it finds zero fixtures: a silent no-op that exits 0 is the failure mode a
# guard like this is most likely to rot into.
#
# Uses `grep -a`, not `strings`: binutils is not present on every host this may run
# on (Git-Bash notably lacks it), and grep -a reads the attribute text embedded in
# the module perfectly well.
set -uo pipefail

repo_root="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
models_dir="${repo_root}/src/test/resources/models"

[ -d "${models_dir}" ] || { echo "FAIL: no fixture dir at ${models_dir}" >&2; exit 1; }

rc=0
checked=0

while IFS= read -r vmfb; do
  rel="${vmfb#"${repo_root}/"}"
  checked=$((checked + 1))
  bad=0

  cpu="$(LC_ALL=C grep -a -o -m1 'cpu = "[^"]*"' "${vmfb}" | head -1)"
  if [ -z "${cpu}" ]; then
    echo "FAIL ${rel}: no 'cpu = ' attribute found — not a vmfb, or truncated"
    bad=1
  elif [ "${cpu}" != 'cpu = "generic"' ]; then
    echo "FAIL ${rel}: ${cpu} — must be cpu = \"generic\""
    echo "     regenerate with tools/export_add.sh or tools/export_scale.sh"
    bad=1
  fi

  triple="$(LC_ALL=C grep -a -o -m1 'target_triple = "[^"]*"' "${vmfb}" | head -1)"
  if [ -z "${triple}" ]; then
    echo "FAIL ${rel}: no 'target_triple = ' attribute found"
    bad=1
  elif ! printf '%s' "${triple}" | grep -q -- '-elf"$'; then
    echo "FAIL ${rel}: ${triple} — must be an embedded-ELF triple ending in -elf"
    bad=1
  fi

  if [ "${bad}" -eq 0 ]; then
    echo "ok   ${rel}: ${cpu}, ${triple}"
  else
    rc=1
  fi
done < <(find "${models_dir}" -name '*.vmfb' | sort)

if [ "${checked}" -eq 0 ]; then
  echo "FAIL: found no .vmfb fixtures under ${models_dir} — this check was a no-op" >&2
  exit 1
fi

if [ "${rc}" -eq 0 ]; then
  echo "--- fixture portability PASS (${checked} fixtures) ---"
else
  echo "--- fixture portability FAIL (${checked} fixtures checked) ---" >&2
fi
exit "${rc}"
