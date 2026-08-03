# Fixture Portability Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every committed `.vmfb` test fixture runnable on any CPU of its architecture, by fixing the one fixture compiled for a specific host CPU and adding a guard that prevents recurrence.

**Architecture:** `tools/export_add.sh` diverges from its peer `tools/export_scale.sh` in target-CPU policy, and the resulting `add.vmfb` carries AVX-512 codegen. Align the script with its peer, regenerate the fixture, and add a checker script that asserts the portability properties of every committed fixture. Wire the checker into the existing Java CI job.

**Tech Stack:** Bash, `iree-compile` 3.11.0 (pip, in `.venv`), GitHub Actions.

## Background — why this matters

`src/test/resources/models/add.vmfb` embeds `cpu = "tigerlake"` with a full
AVX-512 feature set. Every other committed fixture — `scale.vmfb`,
`scale2.vmfb`, and all three under `models/aarch64/` — embeds `cpu = "generic"`.

A `.vmfb` contains a compiled embedded-ELF executable. When IREE loads a fixture
whose code uses instructions the host CPU lacks, the process executes an illegal
instruction. So `add.vmfb` works only on AVX-512 hardware. It happens to pass
today on the CI runners, which is exactly why this went unnoticed.

Root cause, precisely: `tools/export_scale.sh:42,68` passes
`--iree-llvmcpu-target-cpu=generic` unconditionally, adding a triple only when
`IREE_TARGET_TRIPLE` is set. `tools/export_add.sh:65-69` instead falls back to
`--iree-llvmcpu-target-cpu=host` when no triple is given. The comment at
`export_add.sh:60-61` justifies this with "the fixture is only ever run on the
machine that produced it" — which is false. The fixture is committed to the
repository and runs everywhere CI and contributors run.

This is an independent bug fix. It is a prerequisite for Windows support (a Zen 3
Windows build host cannot run the current fixture) but contains no Windows
content and stands on its own.

## Global Constraints

- Compiler MUST be `iree-base-compiler==3.11.0` from pip's plain index, already
  installed at `.venv/bin/iree-compile`. Never a nightly, never `--find-links`,
  never a from-source checkout. Its `--version` embeds commit
  `e4a3b0405d7d23554da26403658d0e8c3c5ecf25`, which must match the
  `runtime_commit` recorded in the linked runtime's manifest.
- The regenerated fixture MUST keep target triple
  `x86_64-unknown-unknown-eabi-elf` — the OS-agnostic embedded-ELF form.
- The regenerated fixture MUST keep exported function `add` with signature
  `sync func @add(%input0: tensor<4xf32>, %input1: tensor<4xf32>) -> (%output0: tensor<4xf32>)`
  plus `__init`. Consumers depend on the fully-qualified name `module.add`.
- Do NOT touch `models/aarch64/` — those fixtures are already correct.
- Do NOT create a `windows-x86_64` fixture directory. Fixture portability is an
  architecture property, not an operating-system one.
- Shell scripts use `#!/usr/bin/env bash` and must run under Git-Bash as well as
  Linux, so prefer `grep -a` over `strings` (binutils is absent on Git-Bash).

## Verified ground truth

These were measured on this repo before the plan was written. Use them as
expected values.

Current state of every committed fixture:

| Fixture | `cpu` | `target_triple` |
| --- | --- | --- |
| `models/add.vmfb` | **`tigerlake`** | `x86_64-unknown-unknown-eabi-elf` |
| `models/scale.vmfb` | `generic` | `x86_64-unknown-unknown-eabi-elf` |
| `models/scale2.vmfb` | `generic` | `x86_64-unknown-unknown-eabi-elf` |
| `models/aarch64/add.vmfb` | `generic` | `aarch64-unknown-unknown-eabi-elf` |
| `models/aarch64/scale.vmfb` | `generic` | `aarch64-unknown-unknown-eabi-elf` |
| `models/aarch64/scale2.vmfb` | `generic` | `aarch64-unknown-unknown-eabi-elf` |

A trial regeneration into a scratch directory confirmed:
- Output carries `cpu = "generic"` and the unchanged triple.
- Exported functions are unchanged (`add`, `__init`).
- The compiler is deterministic: two runs with identical flags produce
  byte-identical output.
- The new file is ~8.7 KB versus the current ~9.7 KB. **A size decrease is
  expected**, not a red flag — the AVX-512 code paths are gone.

## File Structure

| File | Change | Responsibility |
| --- | --- | --- |
| `tools/check_fixture_portability.sh` | Create | Asserts every committed `.vmfb` is CPU-generic and OS-agnostic. The regression guard. |
| `tools/export_add.sh` | Modify `:57-69` | Target-flag policy aligned with `export_scale.sh`. |
| `src/test/resources/models/add.vmfb` | Regenerate | The fixed binary fixture. |
| `.github/workflows/native-build.yml` | Modify | Runs the guard in the existing ubuntu Java job. |

Two tasks. Task 1 is the fix and its guard, committed together because
committing the guard alone would leave `main` red. Task 2 wires the guard into
CI and is independently rejectable — Task 1 stands without it.

---

### Task 1: Fix the fixture and add the portability guard

**Files:**
- Create: `tools/check_fixture_portability.sh`
- Modify: `tools/export_add.sh:57-69`
- Regenerate: `src/test/resources/models/add.vmfb`

**Interfaces:**
- Consumes: `.venv/bin/iree-compile` (pinned 3.11.0), `tools/add.mlir`.
- Produces: `tools/check_fixture_portability.sh`, exit 0 on success and 1 on any
  violation, taking no arguments and discovering fixtures itself. Task 2 invokes
  it as `bash tools/check_fixture_portability.sh` from the repo root.

- [ ] **Step 1: Write the failing test — the portability guard**

Create `tools/check_fixture_portability.sh`:

```bash
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
```

Make it executable:

```bash
chmod +x tools/check_fixture_portability.sh
```

- [ ] **Step 2: Run the guard to verify it fails**

Run: `bash tools/check_fixture_portability.sh; echo "exit=$?"`

Expected: exit=1, with exactly one FAIL line naming `add.vmfb`, and five `ok`
lines. Output should be:

```
ok   src/test/resources/models/aarch64/add.vmfb: cpu = "generic", target_triple = "aarch64-unknown-unknown-eabi-elf"
ok   src/test/resources/models/aarch64/scale.vmfb: cpu = "generic", target_triple = "aarch64-unknown-unknown-eabi-elf"
ok   src/test/resources/models/aarch64/scale2.vmfb: cpu = "generic", target_triple = "aarch64-unknown-unknown-eabi-elf"
FAIL src/test/resources/models/add.vmfb: cpu = "tigerlake" — must be cpu = "generic"
     regenerate with tools/export_add.sh or tools/export_scale.sh
ok   src/test/resources/models/scale.vmfb: cpu = "generic", target_triple = "x86_64-unknown-unknown-eabi-elf"
ok   src/test/resources/models/scale2.vmfb: cpu = "generic", target_triple = "x86_64-unknown-unknown-eabi-elf"
--- fixture portability FAIL (6 fixtures checked) ---
```

**If it reports PASS, stop.** The guard is broken, not the fixture. Do not
proceed to Step 3 — a guard that cannot fail is worse than no guard.

- [ ] **Step 3: Fix the target-flag policy in `tools/export_add.sh`**

Replace lines 57-69 (the comment block and the `TARGET_FLAGS` conditional).

Current:

```bash
# --iree-hal-target-device / --iree-hal-local-target-device-backends are the
# current (3.11.0) flag spelling and were confirmed to work; an older
# compiler might need the legacy --iree-hal-target-backends=llvm-cpu spelling
# instead. --iree-llvmcpu-target-cpu=host silences the "generic CPU" perf
# warning; the fixture is only ever run on the machine that produced it.
# IREE_TARGET_TRIPLE switches to a cross-compiled build (target-cpu=generic,
# since "host" is meaningless across architectures) -- used for the committed
# per-arch fixtures.
if [[ -n "${IREE_TARGET_TRIPLE:-}" ]]; then
  TARGET_FLAGS=(--iree-llvmcpu-target-cpu=generic --iree-llvmcpu-target-triple="${IREE_TARGET_TRIPLE}")
else
  TARGET_FLAGS=(--iree-llvmcpu-target-cpu=host)
fi
```

New:

```bash
# --iree-hal-target-device / --iree-hal-local-target-device-backends are the
# current (3.11.0) flag spelling and were confirmed to work; an older
# compiler might need the legacy --iree-hal-target-backends=llvm-cpu spelling
# instead.
#
# target-cpu=generic ALWAYS, matching export_scale.sh. This fixture is committed
# to the repository and runs on every contributor's machine and every CI runner,
# not just the one that produced it. A previous version used target-cpu=host,
# which baked the producer's AVX-512 (cpu = "tigerlake") into the embedded
# executable and made the fixture crash with an illegal instruction on any host
# without AVX-512. tools/check_fixture_portability.sh guards against a repeat.
# The generic-CPU perf warning is expected and irrelevant: this fixture adds two
# 4-element vectors.
#
# IREE_TARGET_TRIPLE adds a cross-compilation target, used for the committed
# per-arch fixtures (see src/test/resources/models/aarch64/).
TRIPLE_ARGS=()
if [[ -n "${IREE_TARGET_TRIPLE:-}" ]]; then
  TRIPLE_ARGS+=(--iree-llvmcpu-target-triple="${IREE_TARGET_TRIPLE}")
fi
```

Then update the invocation at what was lines 71-75 to use the new array name and
the unconditional flag:

```bash
"${IREE_COMPILE}" \
  --iree-hal-target-device=local \
  --iree-hal-local-target-device-backends=llvm-cpu \
  --iree-llvmcpu-target-cpu=generic \
  "${TRIPLE_ARGS[@]}" \
  "${here}/add.mlir" -o "${out}"
```

This is now structurally identical to `export_scale.sh:34-44`.

- [ ] **Step 4: Regenerate the fixture**

Run from the repo root:

```bash
IREE_TARGET_TRIPLE=x86_64-unknown-unknown-eabi-elf bash tools/export_add.sh
```

The explicit triple is passed for reproducibility of intent, matching how the
`aarch64` fixtures were produced. It also pins the exact bytes: the compiler is
deterministic given fixed flags, but omitting the triple flag produces a
slightly different (still correct) module.

Expected tail of output — the script self-verifies the entry point:

```
wrote /home/corey/workspace/djl-iree-engine/src/test/resources/models/add.vmfb
--- exported functions ---
Exported Functions:
  [  0] add(!vm.ref<?>, !vm.ref<?>) -> (!vm.ref<?>)
        iree.abi.declaration: sync func @add(%input0: tensor<4xf32>, %input1: tensor<4xf32>) -> (%output0: tensor<4xf32>)
  [  1] __init() -> ()
```

The exported functions block MUST match exactly. If `add`'s signature changed,
stop — something other than the target flags changed.

`git status` will show `src/test/resources/models/add.vmfb` modified, shrinking
from ~9.7 KB to ~8.7 KB. The shrink is expected; the AVX-512 paths are gone.

- [ ] **Step 5: Run the guard to verify it passes**

Run: `bash tools/check_fixture_portability.sh; echo "exit=$?"`

Expected: exit=0, six `ok` lines, and:

```
--- fixture portability PASS (6 fixtures) ---
```

- [ ] **Step 6: Verify the native suite still passes**

The fixture is consumed by the C++ Catch2 suite and the leak harness. Run the
full native QA inside the blessed container:

```bash
./native/local_build_wrapper.sh native/build_qa.sh
```

Expected: ends with `--- native QA PASS ---`. This exercises `add.vmfb` through
`iree_runtime_test` and through three `iree_leak_harness` invocations.

Note: per `native/local_build_wrapper.sh:12-13`, `build_qa.sh` leaves
root-owned directories behind (`native/qa`). That is pre-existing behaviour and
not something to fix here.

- [ ] **Step 7: Verify the Java suite still passes**

`add.vmfb` is loaded from the classpath by `IreeNativeTest` and `AddModelIT`.
Build the shim, then run the Java tests:

```bash
./native/local_build_wrapper.sh
./gradlew test
```

Expected: BUILD SUCCESSFUL. If `IreeNativeTest` reports "add.vmfb missing", the
regeneration in Step 4 wrote to the wrong directory — check `IREE_FIXTURE_DIR`
is not set in your environment.

- [ ] **Step 8: Commit**

```bash
git add tools/check_fixture_portability.sh tools/export_add.sh src/test/resources/models/add.vmfb
git commit -m "$(cat <<'EOF'
fix(models): compile add.vmfb for a generic CPU, not the build host

export_add.sh fell back to --iree-llvmcpu-target-cpu=host when no target
triple was given, unlike its peer export_scale.sh which always passes
generic. The committed add.vmfb consequently embedded cpu = "tigerlake"
with a full AVX-512 feature set, so it executed illegal instructions on any
x86_64 host without AVX-512. Every other committed fixture is generic.

Align export_add.sh with export_scale.sh, regenerate the fixture, and add
tools/check_fixture_portability.sh, which asserts that every committed
.vmfb carries cpu = "generic" and an OS-agnostic embedded-ELF triple.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Run the portability guard in CI

**Files:**
- Modify: `.github/workflows/native-build.yml`

**Interfaces:**
- Consumes: `tools/check_fixture_portability.sh` from Task 1 — no arguments,
  run from the repo root, exit 0 pass / 1 fail.
- Produces: nothing consumed by later tasks.

The guard belongs in the `build-java-package` job, which already runs on
`ubuntu-latest` on every push and PR. Deliberately **not** in
`native-build-job.yml`: that file is being restructured by the Windows amd64
work, and putting the step there would create a needless conflict. The check is
pure file inspection — no toolchain, no container, sub-second.

- [ ] **Step 1: Add the guard step to the workflow**

In `.github/workflows/native-build.yml`, in the `build-java-package` job, insert
a step immediately after `- uses: actions/checkout@v7` and before the
`setup-java` step:

```yaml
        # Fixture portability: every committed .vmfb must carry cpu = "generic" and an
        # OS-agnostic embedded-ELF triple, so it runs on any CPU of its architecture and
        # any OS. Pure file inspection — no toolchain needed, so it sits in the Java job
        # rather than the native matrix. Runs before anything expensive: a bad fixture
        # should fail in seconds, not after a full container build.
        - name: Check fixture portability
          run: bash tools/check_fixture_portability.sh
```

- [ ] **Step 2: Verify the workflow file is valid YAML**

Run:

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/native-build.yml')); print('YAML ok')"
```

Expected: `YAML ok`.

- [ ] **Step 3: Verify the step reproduces locally exactly as CI will run it**

Run from the repo root, the identical command the workflow uses:

```bash
bash tools/check_fixture_portability.sh; echo "exit=$?"
```

Expected: exit=0, `--- fixture portability PASS (6 fixtures) ---`.

- [ ] **Step 4: Verify the guard still catches a regression**

Prove the CI step is not decorative. Temporarily corrupt a fixture, confirm the
guard fails, then restore:

```bash
cp src/test/resources/models/add.vmfb /tmp/add.vmfb.bak
printf 'not a vmfb' > src/test/resources/models/add.vmfb
bash tools/check_fixture_portability.sh; echo "exit=$?"
cp /tmp/add.vmfb.bak src/test/resources/models/add.vmfb
rm /tmp/add.vmfb.bak
```

Expected: exit=1, with
`FAIL src/test/resources/models/add.vmfb: no 'cpu = ' attribute found — not a vmfb, or truncated`.

Then confirm the restore worked:

```bash
git status --porcelain src/test/resources/models/add.vmfb
```

Expected: empty output (the file matches the Task 1 commit).

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/native-build.yml
git commit -m "$(cat <<'EOF'
ci: check fixture portability on every build

Runs tools/check_fixture_portability.sh in the existing ubuntu Java job.
Placed there rather than in native-build-job.yml because the check needs no
toolchain and that file is being restructured by the Windows amd64 work.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

## Out of scope

- **Regenerating `scale.vmfb` / `scale2.vmfb`.** Already `generic`; rewriting
  them would produce a binary diff with no behaviour change.
- **Anything under `models/aarch64/`.** Already correct.
- **Windows support.** See
  `docs/superpowers/specs/2026-08-03-windows-amd64-support-design.md`. This plan
  unblocks that work but contains none of it.
- **Checking `.irpa` files.** They are architecture-neutral data with no embedded
  code and no target attributes.
