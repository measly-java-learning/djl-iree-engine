# Resolves the IREE runtime into a single `iree::runtime` interface target.
#
# There is no IREERuntimeConfig.cmake to find_package(): the local build at
# IREE_INSTALL was never `ninja install`ed, so we point at the build tree for
# archives and the source tree for headers. When the iree-runtime-dist repo
# lands, IreeRuntimePin.cmake replaces this with a proper package.

set(IREE_INSTALL "/home/corey/workspace/iree-build" CACHE PATH
    "IREE build (or install) tree to link against")
set(IREE_SOURCE "/home/corey/workspace/iree" CACHE PATH
    "IREE source tree supplying public headers")

if(NOT EXISTS "${IREE_INSTALL}/runtime/src/iree/runtime/libiree_runtime_unified.a")
  message(FATAL_ERROR
      "No IREE runtime at IREE_INSTALL=${IREE_INSTALL}. "
      "Expected runtime/src/iree/runtime/libiree_runtime_unified.a. "
      "Point -DIREE_INSTALL at an IREE build tree.")
endif()

# Generated headers (flatbuffer schemas, config) live in the build tree;
# public headers live in the source tree. Both are required.
add_library(iree_runtime_iface INTERFACE)
target_include_directories(iree_runtime_iface INTERFACE
    "${IREE_SOURCE}/runtime/src"
    "${IREE_INSTALL}/runtime/src")

# iree/base/allocator.h only declares iree_allocator_system() when
# IREE_ALLOCATOR_SYSTEM_CTL is defined. IREE's own CMake (runtime/src/iree/base/
# CMakeLists.txt) injects this as a private compile define on its `base`
# target; since we bypass find_package there is no propagation, so we set the
# same default here ("libc", matching the IREE_ALLOCATOR_SYSTEM build cache
# value) explicitly.
target_compile_definitions(iree_runtime_iface INTERFACE
    IREE_ALLOCATOR_SYSTEM_CTL=iree_allocator_libc_ctl)

# libiree_runtime_impl.a only contains the runtime layer itself (call.c,
# instance.c, session.c) — it does not bundle base/hal/vm/drivers, so linking
# it alone leaves iree_hal_*/iree_vm_*/iree_allocator_* undefined. The build
# tree also produces libiree_runtime_unified.a, which bundles the runtime
# layer together with base, hal (including the local-sync driver and embedded
# ELF loader) and vm into one archive; link that instead of assembling the
# individual driver/loader archives by hand.
# --start-group/--end-group because the IREE static archives are mutually
# recursive and single-pass resolution leaves undefined symbols.
# The VM bytecode module verifier (module.c/verifier.c, compiled into the
# unified archive) calls into flatcc's verification API, which lives in
# third_party/flatcc, not in the IREE tree: libflatcc_parsing.a supplies
# flatcc_verify_* (the schema table/vector/union verifiers) and
# libflatcc_runtime.a supplies the flatcc_builder_*/flatcc_refmap_*
# buffer-building support the runtime headers also pull in.
target_link_libraries(iree_runtime_iface INTERFACE
    -Wl,--start-group
    "${IREE_INSTALL}/runtime/src/iree/runtime/libiree_runtime_unified.a"
    "${IREE_INSTALL}/build_tools/third_party/flatcc/libflatcc_parsing.a"
    "${IREE_INSTALL}/build_tools/third_party/flatcc/libflatcc_runtime.a"
    -Wl,--end-group
    ${CMAKE_DL_LIBS} m pthread)

add_library(iree::runtime ALIAS iree_runtime_iface)
