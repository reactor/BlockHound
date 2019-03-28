cmake_minimum_required(VERSION 3.0)

project(BlockHound)

set(CMAKE_CXX_STANDARD 11)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-everything -std=c++11")

include_directories(src/main/headers)
if(WIN32)
    # Linking mingw libs statically
    set(CMAKE_CXX_STANDARD_LIBRARIES "-static-libgcc -static-libstdc++ ${CMAKE_CSS_STANDARD_LIBRARIES}")
    include_directories(src/main/headers/win32)
endif()
if(APPLE)
    include_directories(src/main/headers/darwin)
endif()
if(UNIX AND NOT APPLE)
    include_directories(src/main/headers/linux)
endif()
set(SOURCE_FILES src/main/cpp/agent.cpp)

add_library(BlockHound SHARED ${SOURCE_FILES})
set_property(TARGET BlockHound PROPERTY NO_SONAME 1)