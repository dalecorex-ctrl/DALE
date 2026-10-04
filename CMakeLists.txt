cmake_minimum_required(VERSION 3.10.2)

project("mobile-os-native")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- whisper.cpp third-party build options (kept minimal, vendored tree) -------
# The vendored whisper.cpp tree is built as a subdirectory so that the ggml
# dependency is compiled and linked. Compiling whisper.cpp alone is not enough:
# it leaves ~101 ggml_* symbols unresolved, which fails to link a shared object
# with --no-undefined (Android NDK default) and fails dlopen at runtime.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp-master/CMakeLists.txt")
    add_subdirectory(whisper.cpp-master EXCLUDE_FROM_ALL)
else()
    message(FATAL_ERROR
        "vendored whisper.cpp-master/CMakeLists.txt not found - "
        "the native runtime requires the vendored whisper.cpp tree.")
endif()

# Include directories for whisper.cpp and ggml.
# ggml/include is required: whisper.h does #include "ggml.h", which lives in
# whisper.cpp-master/ggml/include (proven by compilation, not assumed).
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp-master/include
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp-master/src
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp-master/ggml/include
)

# WHISPER_VERSION is a compile definition normally supplied by whisper's own
# build; it must be defined here too or whisper.cpp fails to compile.
if(NOT DEFINED WHISPER_VERSION)
    set(WHISPER_VERSION "1.9.4")
endif()
add_compile_definitions(WHISPER_VERSION="${WHISPER_VERSION}")

# --- JNI headers ---------------------------------------------------------------
# Android NDK ships jni.h on the default sysroot include path, so nothing is
# needed there. A host build (used for CI/static verification) must locate it.
#
# Note: find_package(JNI) is NOT used here because it requires AWT headers, which
# headless JDKs do not ship, and it caches an empty include path on first run.
if(NOT ANDROID)
    find_path(LANIE_JNI_INCLUDE_DIR
        NAMES jni.h
        HINTS
            "$ENV{JAVA_HOME}/include"
            "${JAVA_HOME}/include"
        PATHS
            /usr/lib/jvm/default-java/include
            /usr/lib/jvm/java-25-openjdk-arm64/include
            /usr/lib/jvm/java-21-openjdk-arm64/include
    )
    if(LANIE_JNI_INCLUDE_DIR)
        include_directories(${LANIE_JNI_INCLUDE_DIR})
        # jni_md.h lives in a platform subdir (linux/, darwin/, win32/).
        if(EXISTS "${LANIE_JNI_INCLUDE_DIR}/linux")
            include_directories("${LANIE_JNI_INCLUDE_DIR}/linux")
        elseif(EXISTS "${LANIE_JNI_INCLUDE_DIR}/darwin")
            include_directories("${LANIE_JNI_INCLUDE_DIR}/darwin")
        elseif(EXISTS "${LANIE_JNI_INCLUDE_DIR}/win32")
            include_directories("${LANIE_JNI_INCLUDE_DIR}/win32")
        endif()
        message(STATUS "JNI headers: ${LANIE_JNI_INCLUDE_DIR}")
    else()
        message(FATAL_ERROR
            "jni.h not found. On Android this is provided by the NDK; on a host "
            "build set JAVA_HOME to a JDK (not a JRE) so the JNI headers can be "
            "located.")
    endif()
endif()

# --- optional agent-native sources -------------------------------------------
# Guarded: this tree has no src/ or include/ yet, so an unguarded glob would be
# a dangling reference. The guard preserves the integration point explicitly.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/agent-native-main/src")
    file(GLOB AGENT_NATIVE_SOURCES
        "agent-native-main/src/*.cpp"
    )
    include_directories(${CMAKE_CURRENT_SOURCE_DIR}/agent-native-main/include)
else()
    set(AGENT_NATIVE_SOURCES "")
    message(STATUS "agent-native-main/src not present - building without agent-native sources")
endif()

# Build the unified shared library
add_library(
    mobile_runtime_native
    SHARED
    ${AGENT_NATIVE_SOURCES}
    native_bridge.cpp
)

# Link whisper (which carries ggml) plus the Android system libraries.
target_link_libraries(
    mobile_runtime_native
    whisper
)

# android/log are Android-only system libraries; on a host build they do not
# exist, so they are linked conditionally rather than dropped.
if(ANDROID)
    target_link_libraries(
        mobile_runtime_native
        android
        log
    )
endif()
