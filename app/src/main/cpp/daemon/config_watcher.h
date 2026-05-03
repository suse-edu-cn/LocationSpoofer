#pragma once

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <thread>
#include <nlohmann/json.hpp>

struct RoutePoint {
    double lat;
    double lng;
};

struct SpoofConfig {
    bool active = false;
    double lat = 0.0;
    double lng = 0.0;
    std::string sim_mode = "STATIC";
    float sim_bearing = 0.0f;
    long long start_timestamp = 0;
    bool is_route_mode = false;
    std::vector<RoutePoint> route;
};

class ConfigWatcher {
public:
    ConfigWatcher(const std::string& configPath);
    ~ConfigWatcher();

    void start();
    void stop();

    SpoofConfig getConfig();

private:
    void watchLoop();
    void parseConfig(const std::string& path);

    std::string mConfigPath;
    std::atomic<bool> mRunning;
    std::thread mWatcherThread;
    
    std::mutex mConfigMutex;
    SpoofConfig mCurrentConfig;
};
