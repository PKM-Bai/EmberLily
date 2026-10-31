cmake_minimum_required(VERSION 3.22.1)
project(emberlily LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(MGBA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba" ABSOLUTE)
if(NOT EXISTS "${MGBA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Run git submodule update --init third_party/mgba before building")
endif()

# The pinned upstream core supplies its own blip_buf and inih sources.
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(DISABLE_FRONTENDS ON CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL ON CACHE BOOL "" FORCE)
foreach(OPTION BUILD_SHARED M_CORE_GB SKIP_LIBRARY BUILD_QT BUILD_SDL BUILD_GL
        BUILD_GLES2 BUILD_GLES3 BUILD_PERF BUILD_TEST BUILD_SUITE BUILD_CINEMA
        BUILD_ROM_TEST BUILD_EXAMPLE BUILD_LIBRETRO BUILD_PYTHON BUILD_DOCGEN
        BUILD_LTO ENABLE_SCRIPTING USE_DEBUGGERS USE_GDB_STUB USE_EDITLINE
        USE_FFMPEG USE_DISCORD_RPC USE_LZMA USE_LUA USE_ELF USE_SQLITE3
        USE_LIBZIP USE_MINIZIP USE_PNG USE_ZLIB)
    set(${OPTION} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${MGBA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

add_library(emberlily SHARED emberlily_jni.c)
target_include_directories(emberlily PRIVATE
    "${MGBA_DIR}/include" "${MGBA_DIR}/src" "${CMAKE_CURRENT_BINARY_DIR}/mgba/include")
# Keep public struct layouts consistent with mGBA's Android build.
target_compile_definitions(emberlily PRIVATE M_CORE_GBA USE_PTHREADS)
target_compile_options(emberlily PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
target_link_libraries(emberlily PRIVATE mgba)
