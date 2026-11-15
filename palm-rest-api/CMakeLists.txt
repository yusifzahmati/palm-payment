cmake_minimum_required(VERSION 3.10)
project(palm_service)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(-D_WIN32_WINNT=0x0A00)

add_executable(palm_service
        main.cpp
        PalmService.cpp
)

target_include_directories(palm_service PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/vendor/vp930_sdk/include
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_link_directories(palm_service PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/vendor/vp930_sdk/lib
)

target_link_libraries(palm_service
        palm_sdk
        ws2_32
)
