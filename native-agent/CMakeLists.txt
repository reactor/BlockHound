cmake_minimum_required(VERSION 3.3)
project(BlockHound)

set(CMAKE_CXX_STANDARD 14)

find_package(JNI)
include_directories(${JNI_INCLUDE_DIRS})

set(SOURCE_FILES src/main/cpp/agent.cpp)