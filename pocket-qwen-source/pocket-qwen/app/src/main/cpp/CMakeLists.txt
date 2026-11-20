cmake_minimum_required(VERSION 3.22)
project(pocketqwen C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    # Arm KleidiAI micro-kernels: big speedup for Q4_0 / Q4_K on recent Arm cores
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
endif()

# Use the llama.cpp checkout in third_party/ if present (git submodule),
# otherwise download the pinned release automatically.
set(LLAMA_CPP_TAG "v1.0.0")
set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    add_subdirectory(${LLAMA_DIR} build-llama)
else()
    message(STATUS "third_party/llama.cpp not found - fetching llama.cpp ${LLAMA_CPP_TAG}")
    include(FetchContent)
    FetchContent_Declare(llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(pocketqwen SHARED qwen_jni.cpp)
target_link_libraries(pocketqwen PRIVATE llama ggml android log)
