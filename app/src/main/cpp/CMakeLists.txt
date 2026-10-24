cmake_minimum_required(VERSION 3.22.1)
project(localai_agent C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources are missing.\n"
        "Run:  bash scripts/fetch_deps.sh\n"
        "Expected at: ${LLAMA_DIR}")
endif()

# Build only what the app needs.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN             ${LLAMA_VULKAN} CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(localai SHARED llama_jni.cpp)
target_include_directories(localai PRIVATE "${LLAMA_DIR}/include")
target_link_libraries(localai PRIVATE llama android log)
target_compile_options(localai PRIVATE
    -O3
    -fexceptions
    -frtti
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-unused-function)
