#!/usr/bin/env bash
# Ownership handling for scripts run under native/local_build_wrapper.sh.
#
# The wrapper bind-mounts the repo into a container that runs as root, so anything a build or QA
# run creates comes back root-owned on the host. The wrapper passes HOST_UID/HOST_GID so we can
# hand the outputs back on the way out.
#
# Sourced, never executed. Both native/build.sh and native/build_qa.sh use it; keeping it in one
# place is the point, because two copies of this trap will drift.

# Paths registered by ir_chown_outputs_on_exit, chowned by ir_chown_cleanup.
IR_CHOWN_PATHS=()

ir_chown_cleanup() {
  rc=$?
  if [ -n "${HOST_UID:-}" ] && [ "${#IR_CHOWN_PATHS[@]}" -gt 0 ]; then
    # Deliberately unquoted: entries may be globs (src/main/resources/native/linux*) that must
    # expand HERE, at exit, rather than at registration time when the dirs may not exist yet.
    # `|| true` so a chown failure never masks the script's real exit status.
    chown -R "${HOST_UID}:${HOST_GID}" ${IR_CHOWN_PATHS[@]} 2>/dev/null || true
  fi
  exit "$rc"
}

# Usage: ir_chown_outputs_on_exit native/build 'src/main/resources/native/linux*'
# Quote glob arguments at the call site so they survive to exit-time expansion.
ir_chown_outputs_on_exit() {
  IR_CHOWN_PATHS=("$@")
  trap ir_chown_cleanup EXIT
}
