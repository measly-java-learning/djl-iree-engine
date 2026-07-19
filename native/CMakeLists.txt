cmake_minimum_required(VERSION 3.22)
project(iree_djl LANGUAGES C CXX)

# IREE's headers require C++17+; state the standard ourselves because no
# package config supplies INTERFACE_COMPILE_FEATURES. Do not delete.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
include(ResolveIree)

option(IREE_DJL_SANITIZE "Build with ASan/LSan" OFF)
if(IREE_DJL_SANITIZE)
  add_compile_options(-fsanitize=address -fno-omit-frame-pointer -g)
  add_link_options(-fsanitize=address)
endif()

# Link smoke test (Task 1)
add_executable(link_smoke_test test/link_smoke_test.cpp)
target_link_libraries(link_smoke_test PRIVATE iree::runtime)
