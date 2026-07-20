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
if(IREE_DJL_SANITIZE)
  add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=address)
endif()

# Link smoke test (Task 1)
add_executable(link_smoke_test test/link_smoke_test.cpp)
target_link_libraries(link_smoke_test PRIVATE iree-runtime-dist::runtime)

# RAII facade over the IREE C API (Task 3)
add_library(iree_djl_core STATIC core/iree_runtime.cpp)
target_include_directories(iree_djl_core PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(iree_djl_core PUBLIC iree-runtime-dist::runtime)

include(FetchContent)
FetchContent_Declare(Catch2
    GIT_REPOSITORY https://github.com/catchorg/Catch2.git
    GIT_TAG v3.5.2)
FetchContent_MakeAvailable(Catch2)

add_executable(iree_runtime_test test/iree_runtime_test.cpp)
target_link_libraries(iree_runtime_test PRIVATE iree_djl_core Catch2::Catch2WithMain)
target_compile_definitions(iree_runtime_test PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb"
    IREE_DIST_ADD_VMFB="${IREE_RUNTIME_DIST_ADD_VMFB}")

# ASan/LSan leak harness — the go/no-go gate (Task 6)
add_executable(iree_leak_harness harness/iree_leak_harness.cpp)
target_link_libraries(iree_leak_harness PRIVATE iree_djl_core)
target_compile_definitions(iree_leak_harness PRIVATE
    IREE_DJL_ADD_VMFB="${CMAKE_CURRENT_LIST_DIR}/../src/test/resources/models/add.vmfb")

# JNI shim (Task 7): thin marshalling layer over iree_djl_core.
find_package(JNI REQUIRED)
add_library(iree_djl SHARED jni/iree_djl_jni.cpp)
target_include_directories(iree_djl PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(iree_djl PRIVATE iree_djl_core)
# Hide IREE's symbols so they cannot collide with anything else in the JVM.
target_link_options(iree_djl PRIVATE -Wl,--exclude-libs,ALL)
