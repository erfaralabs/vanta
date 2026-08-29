cmake_minimum_required(VERSION 3.22)
project(vanta_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Target: ARM64 (dotprod/fp16; NEON is on by default on aarch64) ────────
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

# ── Speed flags applied ONLY to the JNI wrapper (ggml/llama use their own) ──
add_link_options(-O3 -flto -Wl,--gc-sections)

# ── llama.cpp backend: Vulkan only, everything else off ───────────────────
set(LLAMA_VULKAN   ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL    OFF CACHE BOOL "" FORCE)
set(LLAMA_CUBLAS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BLAS     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# llama.cpp source (vendored as a git submodule at src/main/cpp/llama.cpp)
add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

# ── Vanta JNI bindings (chat-only) ────────────────────────────────────────
add_library(vanta_llama SHARED vanta_llama_jni.cpp)
target_include_directories(vanta_llama PRIVATE llama.cpp)
# Aggressive flags for our wrapper only — they'd break ggml/llama if applied globally.
target_compile_options(vanta_llama PRIVATE -O3 -ffast-math -fno-exceptions -fno-rtti -fno-strict-aliasing)
target_link_libraries(vanta_llama PRIVATE llama)
target_link_libraries(vanta_llama PRIVATE android log)
