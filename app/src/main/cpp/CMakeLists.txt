cmake_minimum_required(VERSION 3.22)
project(nuondroid CXX C)

# Upstream NuanceResurrection checkout (git submodule, see ../../../../../.gitmodules).
set(NUANCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/NuanceResurrection"
    CACHE PATH "NuanceResurrection source tree")
get_filename_component(NUANCE_DIR "${NUANCE_DIR}" ABSOLUTE)

if(NOT EXISTS "${NUANCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "NuanceResurrection not found at ${NUANCE_DIR} - run: git submodule update --init")
endif()

# The core is an interpreter on arm64; an unoptimised build is unusably slow,
# so force Release whatever variant Gradle asked for.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Build upstream's libretro core, unmodified, as a library we link against.
set(BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
add_subdirectory("${NUANCE_DIR}" nuance)

# Upstream names the output "nuance_libretro.so" (PREFIX ""). Android's package
# manager and loader are happier with the lib prefix.
set_target_properties(nuance_libretro PROPERTIES PREFIX "lib")

add_library(nuondroid SHARED host.cpp)
target_include_directories(nuondroid PRIVATE "${NUANCE_DIR}/src")
target_compile_options(nuondroid PRIVATE -O2 -Wall)
target_link_libraries(nuondroid PRIVATE nuance_libretro GLESv3 EGL aaudio android log)
