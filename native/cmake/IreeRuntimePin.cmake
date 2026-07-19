# GENERATED SEAM — placeholder.
#
# When the iree-runtime-dist repo exists this file is replaced wholesale with
# the pin asset from its release (hash-pinned FetchContent of a build-attested
# tarball), exactly as native/cmake/EtRuntimePin.cmake works in
# djl-executorch-engine. The SHA256 change is the supply-chain review gate.
#
# Until then this is intentionally unreachable: ResolveIree.cmake handles
# everything via IREE_INSTALL. Do not add logic here — replace the file.

if(NOT DEFINED IREE_INSTALL OR IREE_INSTALL STREQUAL "")
  message(FATAL_ERROR
      "No IREE_INSTALL set and no runtime pin is available yet. "
      "Set -DIREE_INSTALL=/path/to/iree-build.")
endif()
