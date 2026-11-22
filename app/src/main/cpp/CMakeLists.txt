cmake_minimum_required(VERSION 3.22.1)
project(oralis_mp3 C)

# LAME 3.100 encoder (LGPL-2.0-or-later), built as part of a separate shared
# library so it stays dynamically linked and replaceable (LGPL requirement).
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)

add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC ${LAME_DIR}/include PRIVATE ${LAME_DIR} ${LAME_DIR}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H STDC_HEADERS)
target_compile_options(mp3lame PRIVATE -O2 -w -ffast-math)
if (ANDROID)
    target_link_libraries(mp3lame m)
    # 16 KB page size compatibility (Android 15+ devices).
    target_link_options(mp3lame PRIVATE "-Wl,-z,max-page-size=16384")
else ()
    target_link_libraries(mp3lame m)
endif ()

add_library(oralis_mp3 SHARED oralis_mp3.c)
target_include_directories(oralis_mp3 PRIVATE ${LAME_DIR}/include)
target_link_libraries(oralis_mp3 mp3lame)
if (ANDROID)
    target_link_options(oralis_mp3 PRIVATE "-Wl,-z,max-page-size=16384")
else ()
    # Host build (JVM unit tests) needs the JDK JNI headers.
    find_package(JNI REQUIRED)
    target_include_directories(oralis_mp3 PRIVATE ${JNI_INCLUDE_DIRS})
endif ()
