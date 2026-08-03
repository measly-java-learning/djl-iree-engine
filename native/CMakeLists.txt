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

include(FetchContent)
FetchContent_Declare(iree_runtime_dist
    URL      "${IREE_RUNTIME_URL_default_linux-x86_64}"
    URL_HASH SHA256=${IREE_RUNTIME_SHA256_default_linux-x86_64}
)
FetchContent_MakeAvailable(iree_runtime_dist)

list(APPEND CMAKE_PREFIX_PATH "${iree_runtime_dist_SOURCE_DIR}")
find_package(IreeRuntimeDist REQUIRED)

option(IREE_DJL_SANITIZE "Build with ASan/LSan" OFF)
option(IREE_DJL_TSAN "Build with ThreadSanitizer" OFF)

# ASan and TSan are mutually exclusive -- never enable both at once. Checked
# immediately after both options are declared and before either flag block
# below, so generation fails before any conflicting flags are ever applied.
if(IREE_DJL_SANITIZE AND IREE_DJL_TSAN)
  message(FATAL_ERROR "IREE_DJL_SANITIZE (ASan) and IREE_DJL_TSAN are mutually exclusive; enable at most one.")
endif()

if(IREE_DJL_SANITIZE)
  add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=address)
endif()

if(IREE_DJL_TSAN)
  add_compile_options(-fsanitize=thread -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=thread)
endif()

# Link smoke test (Task 1)
add_executable(link_smoke_test test/link_smoke_test.cpp)
target_link_libraries(link_smoke_test PRIVATE iree-runtime-dist::runtime)

# RAII facade over the IREE C API (Task 3)
add_library(iree_djl_core STATIC core/iree_runtime.cpp)
target_include_directories(iree_djl_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(iree_djl_core PUBLIC iree-runtime-dist::runtime)
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
    GIT_TAG v3.15.2)
FetchContent_MakeAvailable(Catch2)

add_executable(iree_runtime_test test/iree_runtime_test.cpp)
target_link_libraries(iree_runtime_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_runtime_test PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb"
    IREE_DIST_ADD_VMFB="${IREE_RUNTIME_DIST_ADD_VMFB}")

add_executable(iree_params_test test/iree_params_test.cpp)
target_link_libraries(iree_params_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_params_test PRIVATE
    IREE_DJL_SCALE_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale.vmfb"
    IREE_DJL_SCALE_IRPA="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale_weights.irpa"
    IREE_DJL_SCALE_IRPA_ZERO="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale_weights_zero.irpa"
    IREE_DJL_SCALE2_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale2.vmfb"
    IREE_DJL_SCALE2_BIAS_IRPA="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/scale2_bias.irpa"
    IREE_DJL_TEST_TMP_DIR="${CMAKE_CURRENT_BINARY_DIR}")

# ASan/LSan leak harness — the go/no-go gate (Task 6)
add_executable(iree_leak_harness harness/iree_leak_harness.cpp)
target_link_libraries(iree_leak_harness PRIVATE iree_djl_core)
target_compile_definitions(iree_leak_harness PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb")

# JNI shim (Task 7): thin marshalling layer over iree_djl_core. Skipped under any
# sanitizer: sanitized builds are QA-only, never shipped, and JVM-free — so they must
# not require a JDK. The Catch2 units and the leak harness link iree_djl_core directly.
if(NOT IREE_DJL_SANITIZE AND NOT IREE_DJL_TSAN)
  find_package(JNI REQUIRED)
  add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
  target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(iree_djl PRIVATE iree_djl_core)
  # Hide IREE's symbols so they cannot collide with anything else in the JVM.
  target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
endif()
