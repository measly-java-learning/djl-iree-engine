cmake_minimum_required(VERSION 3.22)
project(iree_djl LANGUAGES C CXX)

# IREE's headers require C++17+; state the standard ourselves because no
# package config supplies INTERFACE_COMPILE_FEATURES. Do not delete.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")

# IreeRuntimePin.cmake (published by iree-runtime-dist) only sets the
# hash-pinned URL/SHA256 variables for each variant/platform -- it does not
# itself fetch anything or call find_package(). We do that here: fetch the
# tarball via FetchContent (which re-verifies the SHA-256 on every build),
# then point find_package() at the extracted prefix.
include(IreeRuntimePin)

# Platform identity, derived from the build host (WIN32 before the processor,
# because MSVC's CMAKE_SYSTEM_PROCESSOR is AMD64, which would otherwise win).
# A CACHE variable rather than a plain set(): on a Linux box,
#   cmake -DIREE_DJL_PLATFORM=windows-x86_64
# resolves the Windows pin row and fetches/verifies the tarball with no Windows
# machine involved — the host-test seam.
if(WIN32)
  set(_IREE_DJL_PLATFORM_DEFAULT "windows-x86_64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
  set(_IREE_DJL_PLATFORM_DEFAULT "linux-aarch64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  set(_IREE_DJL_PLATFORM_DEFAULT "linux-x86_64")
else()
  message(FATAL_ERROR "djl-iree-engine: unsupported processor '${CMAKE_SYSTEM_PROCESSOR}'")
endif()
set(IREE_DJL_PLATFORM "${_IREE_DJL_PLATFORM_DEFAULT}" CACHE STRING
    "Platform identity: linux-x86_64 | linux-aarch64 | windows-x86_64")

# The pinned Windows runtime is /MT (its BUILDINFO records crt=MT). Match it:
# a /MD consumer linking a /MT prefix is NOT reliably caught at link time
# (measured upstream: no LNK2005, not even an LNK4098) and corrupts at runtime
# — two CRTs, two heaps. Static linkage also means end users need no VC++
# redistributable.
if(WIN32)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded" CACHE STRING
      "Static CRT: ship without a VC++ redist")
endif()

iree_runtime_dist_url("default" "${IREE_DJL_PLATFORM}" _iree_runtime_url _iree_runtime_sha)

include(FetchContent)
FetchContent_Declare(iree_runtime_dist
    URL      "${_iree_runtime_url}"
    URL_HASH SHA256=${_iree_runtime_sha}
)
FetchContent_MakeAvailable(iree_runtime_dist)

list(APPEND CMAKE_PREFIX_PATH "${iree_runtime_dist_SOURCE_DIR}")
find_package(IreeRuntimeDist REQUIRED)

# --- IREE_STATISTICS_ENABLE agreement with the dist ---
#
# RuntimeStats::statisticsAvailable and the device byte gauges compile against
# iree_hal_allocator_statistics_t, whose fields are #if IREE_STATISTICS_ENABLE.
# The header defaults it to 1; if the dist's archives were built with 0, our
# struct layout and theirs disagree — a silent ABI mismatch, not a missing
# feature. Read the dist's recorded flags and match them explicitly.
set(_iree_buildinfo "${iree_runtime_dist_SOURCE_DIR}/BUILDINFO")
if(EXISTS "${_iree_buildinfo}")
  file(READ "${_iree_buildinfo}" _iree_buildinfo_text)
  if(_iree_buildinfo_text MATCHES "-DIREE_STATISTICS_ENABLE=([01])")
    set(IREE_DJL_STATISTICS_ENABLE "${CMAKE_MATCH_1}")
    message(STATUS
      "iree-runtime-dist BUILDINFO sets IREE_STATISTICS_ENABLE=${IREE_DJL_STATISTICS_ENABLE}; matching it.")
  else()
    set(IREE_DJL_STATISTICS_ENABLE "1")
    message(STATUS
      "iree-runtime-dist BUILDINFO does not override IREE_STATISTICS_ENABLE; using the header default (1).")
  endif()
else()
  set(IREE_DJL_STATISTICS_ENABLE "1")
  message(WARNING
    "iree-runtime-dist BUILDINFO not found at ${_iree_buildinfo}; "
    "assuming IREE_STATISTICS_ENABLE=1. If the dist was built with statistics "
    "disabled, the device byte gauges will be wrong rather than absent.")
endif()

# Fixtures are per-arch: .vmfb embeds an arch-specific ELF executable (the dist's own
# sample add.vmfb is x86_64-only, so aarch64 builds must NOT use it). The .irpa files are
# pure data and ride along in the same dir for a complete per-arch fixture set. Stopgap:
# override IREE_RUNTIME_DIST_ADD_VMFB until the dist ships per-platform samples
# (measly-java-learning/iree-runtime-dist#15); drop the override when the pin's config does it.
# windows-x86_64 deliberately falls through to the default x86_64 fixture directory below:
# fixtures are arch-keyed, not OS-keyed (embedded-ELF loads on any OS); a windows-x86_64
# fixture dir is never created.
if(IREE_DJL_PLATFORM STREQUAL "linux-aarch64")
  set(IREE_DJL_FIXTURE_DIR "${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/aarch64")
  set(IREE_RUNTIME_DIST_ADD_VMFB "${IREE_DJL_FIXTURE_DIR}/add.vmfb")
else()
  set(IREE_DJL_FIXTURE_DIR "${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models")
endif()

option(IREE_DJL_SANITIZE "Build with ASan/LSan" OFF)
option(IREE_DJL_TSAN "Build with ThreadSanitizer" OFF)

# ASan and TSan are mutually exclusive -- never enable both at once. Checked
# immediately after both options are declared and before either flag block
# below, so generation fails before any conflicting flags are ever applied.
if(IREE_DJL_SANITIZE AND IREE_DJL_TSAN)
  message(FATAL_ERROR "IREE_DJL_SANITIZE (ASan) and IREE_DJL_TSAN are mutually exclusive; enable at most one.")
endif()

if(IREE_DJL_SANITIZE)
  if(WIN32)
    add_compile_options(/fsanitize=address)
    add_link_options(/fsanitize=address /INCREMENTAL:NO)
  else()
    add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
    add_link_options(-fsanitize=address)
  endif()
endif()

if(IREE_DJL_TSAN)
  if(WIN32)
    message(FATAL_ERROR "IREE_DJL_TSAN is unsupported on Windows: MSVC has no ThreadSanitizer")
  else()
    add_compile_options(-fsanitize=thread -fno-omit-frame-pointer -g)
    add_link_options(-fsanitize=thread)
  endif()
endif()

# Link smoke test (Task 1)
add_executable(link_smoke_test test/link_smoke_test.cpp)
target_link_libraries(link_smoke_test PRIVATE iree-runtime-dist::runtime)

# RAII facade over the IREE C API (Task 3)
add_library(iree_djl_core STATIC core/iree_runtime.cpp)
target_include_directories(iree_djl_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}" "${CMAKE_CURRENT_LIST_DIR}/jni")
target_link_libraries(iree_djl_core PUBLIC iree-runtime-dist::runtime)
target_compile_definitions(iree_djl_core
  PUBLIC IREE_STATISTICS_ENABLE=${IREE_DJL_STATISTICS_ENABLE})
# The curated iree-runtime-dist::runtime umbrella (libiree_runtime_unified.a)
# does NOT include the io_parameters stack -- confirmed by nm: only
# iree_io_file_handle_open lives in the unified archive. Everything else
# needed to bind an IRPA archive to a scope and expose it as a VM module
# lives in separate archives from the same IREETargets-Runtime export set,
# so link them explicitly:
#   - iree_io_formats_parser_registry: iree_io_parse_file_index (dispatches
#     by extension) -- and it has HARD (non-weak) undefined refs to all three
#     format parsers, so all three format archives must be present even
#     though we only use IRPA.
#   - iree_io_formats_irpa_irpa / _gguf_gguf / _safetensors_safetensors
#   - iree_io_parameter_index: the index container
#   - iree_io_parameter_provider: base provider interface
#     (iree_io_parameter_provider_release/retain etc.)
#   - iree_io_parameter_index_provider: iree_io_parameter_index_provider_create
#   - iree_modules_io_parameters_parameters: iree_io_parameters_module_create
target_link_libraries(iree_djl_core PUBLIC
    iree_io_formats_parser_registry
    iree_io_formats_irpa_irpa
    iree_io_formats_gguf_gguf
    iree_io_formats_safetensors_safetensors
    iree_io_parameter_index
    iree_io_parameter_provider
    iree_io_parameter_index_provider
    iree_modules_io_parameters_parameters)

include(FetchContent)
FetchContent_Declare(Catch2
    GIT_REPOSITORY https://github.com/catchorg/Catch2.git
    GIT_TAG v3.15.3)
FetchContent_MakeAvailable(Catch2)

add_executable(iree_runtime_test test/iree_runtime_test.cpp)
target_link_libraries(iree_runtime_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_runtime_test PRIVATE
    IREE_DJL_ADD_VMFB="${IREE_DJL_FIXTURE_DIR}/add.vmfb"
    IREE_DIST_ADD_VMFB="${IREE_RUNTIME_DIST_ADD_VMFB}")

add_executable(iree_params_test test/iree_params_test.cpp)
target_link_libraries(iree_params_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_params_test PRIVATE
    IREE_DJL_SCALE_VMFB="${IREE_DJL_FIXTURE_DIR}/scale.vmfb"
    IREE_DJL_SCALE_IRPA="${IREE_DJL_FIXTURE_DIR}/scale_weights.irpa"
    IREE_DJL_SCALE_IRPA_ZERO="${IREE_DJL_FIXTURE_DIR}/scale_weights_zero.irpa"
    IREE_DJL_SCALE2_VMFB="${IREE_DJL_FIXTURE_DIR}/scale2.vmfb"
    IREE_DJL_SCALE2_BIAS_IRPA="${IREE_DJL_FIXTURE_DIR}/scale2_bias.irpa"
    IREE_DJL_TEST_TMP_DIR="${CMAKE_CURRENT_BINARY_DIR}")

# ASan/LSan leak harness — the go/no-go gate (Task 6)
add_executable(iree_leak_harness harness/iree_leak_harness.cpp)
target_link_libraries(iree_leak_harness PRIVATE iree_djl_core)
target_compile_definitions(iree_leak_harness PRIVATE
    IREE_DJL_ADD_VMFB="${IREE_DJL_FIXTURE_DIR}/add.vmfb")

# W2 copy-cost measurement (spike 2026-08-04): memcpy cost vs kernel time per
# tensor size. Same pattern as iree_leak_harness — plain main over iree_djl_core,
# JVM-free, no fixture compile definitions (paths come from argv; fixtures are
# generated at bench time by tools/export_bigscale.sh, never committed).
add_executable(iree_copy_bench bench/iree_copy_bench.cpp)
target_link_libraries(iree_copy_bench PRIVATE iree_djl_core)

# JNI shim (Task 7): thin marshalling layer over iree_djl_core. Skipped under any
# sanitizer: sanitized builds are QA-only, never shipped, and JVM-free — so they must
# not require a JDK. The Catch2 units and the leak harness link iree_djl_core directly.
if(NOT IREE_DJL_SANITIZE AND NOT IREE_DJL_TSAN)
  find_package(JNI REQUIRED)
  add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
  target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(iree_djl PRIVATE iree_djl_core)
  # Hide IREE's symbols so they cannot collide with anything else in the JVM.
  if(NOT WIN32)
    target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
  endif()
endif()
