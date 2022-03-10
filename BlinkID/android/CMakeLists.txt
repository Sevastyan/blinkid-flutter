cmake_minimum_required(VERSION 3.10)

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

include_directories(src/main/cpp/include)

set(distribution_DIR ${CMAKE_SOURCE_DIR}/../../../../src/main/jniLibs)

add_library(yuv SHARED IMPORTED)
set_target_properties(yuv PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libyuv.so)

target_link_libraries(native-lib yuv ${log-lib})
