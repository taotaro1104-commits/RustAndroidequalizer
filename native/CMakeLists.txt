cmake_minimum_required(VERSION 3.22.1)
project(rusteq LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(oboe REQUIRED CONFIG)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TRIPLE aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TRIPLE x86_64-linux-android)
else()
    message(FATAL_ERROR "Supported ABIs: arm64-v8a, x86_64")
endif()
add_library(rust_dsp STATIC IMPORTED)
set_target_properties(rust_dsp PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../target/${RUST_TRIPLE}/release/libffi_android.a")
add_library(rusteq SHARED oboe_bridge.cpp)
target_include_directories(rusteq PRIVATE include)
target_compile_options(rusteq PRIVATE -Wall -Wextra -Werror)
# 16 KB ELF alignment for current Android devices.
target_link_options(rusteq PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(rusteq PRIVATE rust_dsp oboe::oboe android log OpenSLES aaudio dl m)

