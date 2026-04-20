cmake_minimum_required(VERSION 3.22.1)
project("wim_whisper")

# Disable whisper.cpp examples and tests
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Avoid building CLI tools
set(WHISPER_BUILD_CLI OFF CACHE BOOL "" FORCE)

# Let whisper.cpp define its static library
add_subdirectory(whisper)

# Define our JNI bridge
add_library(wim_whisper SHARED whisper_jni.cpp)

# Link log, whisper, and Android native libs
find_library(log-lib log)

target_include_directories(wim_whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper/include
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper/ggml/include
)

target_link_libraries(wim_whisper whisper ${log-lib})