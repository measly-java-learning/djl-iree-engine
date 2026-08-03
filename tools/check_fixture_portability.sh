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
#   target_triple arch   The triple's architecture prefix must match the fixture's
#                        directory: fixtures directly under models/ must start
#                        x86_64-, fixtures under models/aarch64/ must start aarch64-.
#                        This is what actually ties a fixture to the host it's meant
#                        for — without it, e.g. running export_add.sh with no
#                        IREE_TARGET_TRIPLE set on an aarch64 host silently overwrites
#                        the x86_64 fixture with an aarch64 one that still passes a
#                        triple-suffix-only check.
#
# Assertions are POSITIVE — a fixture must CARRY the expected value. An absence-only
# check ("no tigerlake found") reports PASS when grep never matched anything at all,
# e.g. against a truncated or non-vmfb file. For the same reason this script FAILS
# when it finds zero fixtures: a silent no-op that exits 0 is the failure mode a
# guard like this is most likely to rot into.
#
# Every occurrence of `cpu = ` and `target_triple = ` in a file is checked, not just
# the first — a multi-target module whose first executable is generic but whose
# second is host-specific must still fail.
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

  # Expected architecture is derived from the fixture's directory, not a
  # hardcoded per-file list, so new fixtures are covered automatically.
  case "${rel}" in
    */aarch64/*) expected_arch="aarch64-" ;;
    *)           expected_arch="x86_64-" ;;
  esac

  cpu_count=0
  last_cpu=""
  while IFS= read -r cpu; do
    cpu_count=$((cpu_count + 1))
    last_cpu="${cpu}"
    if [ "${cpu}" != 'cpu = "generic"' ]; then
      echo "FAIL ${rel}: ${cpu} — must be cpu = \"generic\""
      echo "     regenerate with tools/export_add.sh or tools/export_scale.sh"
      bad=1
    fi
  done < <(LC_ALL=C grep -a -o 'cpu = "[^"]*"' "${vmfb}")
  if [ "${cpu_count}" -eq 0 ]; then
    echo "FAIL ${rel}: no 'cpu = ' attribute found — not a vmfb, or truncated"
    bad=1
  fi

  triple_count=0
  last_triple=""
  while IFS= read -r triple; do
    triple_count=$((triple_count + 1))
    last_triple="${triple}"
    if ! printf '%s' "${triple}" | grep -q -- '-elf"$'; then
      echo "FAIL ${rel}: ${triple} — must be an embedded-ELF triple ending in -elf"
      bad=1
    fi
    triple_value="${triple#target_triple = \"}"
    triple_value="${triple_value%\"}"
    case "${triple_value}" in
      "${expected_arch}"*) ;;
      *)
        echo "FAIL ${rel}: ${triple} — must start with '${expected_arch}' to match its directory"
        echo "     regenerate with IREE_TARGET_TRIPLE set to the correct architecture triple"
        bad=1
        ;;
    esac
  done < <(LC_ALL=C grep -a -o 'target_triple = "[^"]*"' "${vmfb}")
  if [ "${triple_count}" -eq 0 ]; then
    echo "FAIL ${rel}: no 'target_triple = ' attribute found"
    bad=1
  fi

  if [ "${bad}" -eq 0 ]; then
    echo "ok   ${rel}: ${last_cpu}, ${last_triple} (${cpu_count} cpu, ${triple_count} triple occurrence(s))"
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
