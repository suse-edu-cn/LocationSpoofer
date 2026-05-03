cmake_minimum_required(VERSION 3.22.1)

project("gnss_spoof")

set(CMAKE_CXX_STANDARD 17)

# Download nlohmann_json if not exists
if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/nlohmann/json.hpp)
    file(MAKE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/nlohmann)
    file(DOWNLOAD https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp 
         ${CMAKE_CURRENT_SOURCE_DIR}/nlohmann/json.hpp)
endif()

include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# gnss_spoof_daemon executable
add_executable(gnss_spoof_daemon
    daemon/main.cpp
    daemon/config_watcher.cpp
    daemon/nmea_generator.cpp
    daemon/sat_simulator.cpp
)

target_link_libraries(gnss_spoof_daemon log)

set_target_properties(gnss_spoof_daemon PROPERTIES OUTPUT_NAME "gnss_spoof_daemon")
set_target_properties(gnss_spoof_daemon PROPERTIES SUFFIX ".so")
set_target_properties(gnss_spoof_daemon PROPERTIES PREFIX "lib")
