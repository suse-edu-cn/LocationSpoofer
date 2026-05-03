#include "config_watcher.h"
#include <sys/inotify.h>
#include <unistd.h>
#include <fstream>
#include <iostream>
#include <android/log.h>

#define LOG_TAG "GNSS_SPOOF_CONFIG"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

ConfigWatcher::ConfigWatcher(const std::string& configPath) : mConfigPath(configPath), mRunning(false) {
    parseConfig(mConfigPath);
}

ConfigWatcher::~ConfigWatcher() {
    stop();
}

void ConfigWatcher::start() {
    if (mRunning) return;
    mRunning = true;
    mWatcherThread = std::thread(&ConfigWatcher::watchLoop, this);
}

void ConfigWatcher::stop() {
    mRunning = false;
    if (mWatcherThread.joinable()) {
        mWatcherThread.join();
    }
}

SpoofConfig ConfigWatcher::getConfig() {
    std::lock_guard<std::mutex> lock(mConfigMutex);
    return mCurrentConfig;
}

void ConfigWatcher::parseConfig(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        LOGE("Failed to open config file: %s", path.c_str());
        return;
    }

    try {
        nlohmann::json j;
        file >> j;

        SpoofConfig newConfig;
        newConfig.active = j.value("active", false);
        newConfig.lat = j.value("lat", 0.0);
        newConfig.lng = j.value("lng", 0.0);
        newConfig.sim_mode = j.value("sim_mode", "STATIC");
        newConfig.sim_bearing = j.value("sim_bearing", 0.0f);
        newConfig.start_timestamp = j.value("start_timestamp", 0LL);
        newConfig.is_route_mode = j.value("is_route_mode", false);

        if (j.contains("route_json") && j["route_json"].is_string()) {
            std::string routeStr = j["route_json"].get<std::string>();
            try {
                auto routeJson = nlohmann::json::parse(routeStr);
                for (const auto& pt : routeJson) {
                    newConfig.route.push_back({pt.value("lat", 0.0), pt.value("lng", 0.0)});
                }
            } catch (...) {
                LOGE("Failed to parse route_json array");
            }
        }

        std::lock_guard<std::mutex> lock(mConfigMutex);
        mCurrentConfig = newConfig;
        LOGI("Config updated: active=%d, lat=%f, lng=%f", newConfig.active, newConfig.lat, newConfig.lng);

    } catch (const std::exception& e) {
        LOGE("Error parsing config JSON: %s", e.what());
    }
}

void ConfigWatcher::watchLoop() {
    int fd = inotify_init();
    if (fd < 0) {
        LOGE("inotify_init failed");
        return;
    }

    // Try to watch the directory instead of the file, because apps usually write by replacing the file (mv),
    // which breaks the watch on the file itself.
    std::string dirPath = mConfigPath.substr(0, mConfigPath.find_last_of('/'));
    std::string fileName = mConfigPath.substr(mConfigPath.find_last_of('/') + 1);

    int wd = inotify_add_watch(fd, dirPath.c_str(), IN_MODIFY | IN_CREATE | IN_MOVED_TO);
    if (wd < 0) {
        LOGE("inotify_add_watch failed for %s", dirPath.c_str());
        close(fd);
        return;
    }

    char buffer[1024];
    while (mRunning) {
        // Non-blocking read or poll with timeout to allow clean shutdown
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(fd, &fds);
        struct timeval timeout = {1, 0}; // 1 second timeout

        int ret = select(fd + 1, &fds, NULL, NULL, &timeout);
        if (ret > 0 && FD_ISSET(fd, &fds)) {
            int length = read(fd, buffer, 1024);
            if (length < 0) {
                LOGE("inotify read failed");
                break;
            }

            int i = 0;
            while (i < length) {
                struct inotify_event* event = (struct inotify_event*)&buffer[i];
                if (event->len > 0) {
                    std::string name(event->name);
                    if (name == fileName) {
                        LOGI("Config file %s changed", name.c_str());
                        parseConfig(mConfigPath);
                    }
                }
                i += sizeof(struct inotify_event) + event->len;
            }
        }
    }

    inotify_rm_watch(fd, wd);
    close(fd);
}
