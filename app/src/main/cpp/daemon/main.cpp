#include <iostream>
#include <fstream>
#include <thread>
#include <chrono>
#include <cmath>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include "config_watcher.h"
#include "sat_simulator.h"
#include "nmea_generator.h"
#include <sys/stat.h>

#define LOG_TAG "GNSS_SPOOF_DAEMON"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const std::string CONFIG_PATH = "/data/local/tmp/locationspoofer_config.json";
const std::string STATE_PATH = "/data/local/tmp/gnss_spoof_state.json";
const std::string DEVICE_PATH = "/dev/gnss_spoof";

// WGS84 ellipsoid constants
const double a = 6378137.0;
const double f = 1.0 / 298.257223563;
const double b = a * (1.0 - f);
const double e2 = (a*a - b*b) / (a*a);

// Helper to calculate distance and bearing between two points
double getDistance(double lat1, double lng1, double lat2, double lng2) {
    double radLat1 = lat1 * M_PI / 180.0;
    double radLat2 = lat2 * M_PI / 180.0;
    double a_val = radLat1 - radLat2;
    double b_val = lng1 * M_PI / 180.0 - lng2 * M_PI / 180.0;
    double s = 2 * asin(sqrt(pow(sin(a_val/2), 2) + cos(radLat1)*cos(radLat2)*pow(sin(b_val/2), 2)));
    s = s * 6378137.0;
    return s;
}

double getBearing(double lat1, double lng1, double lat2, double lng2) {
    double radLat1 = lat1 * M_PI / 180.0;
    double radLat2 = lat2 * M_PI / 180.0;
    double radLng1 = lng1 * M_PI / 180.0;
    double radLng2 = lng2 * M_PI / 180.0;
    double dLng = radLng2 - radLng1;
    double y = sin(dLng) * cos(radLat2);
    double x = cos(radLat1)*sin(radLat2) - sin(radLat1)*cos(radLat2)*cos(dLng);
    double bearing = atan2(y, x) * 180.0 / M_PI;
    if (bearing < 0) bearing += 360.0;
    return bearing;
}

// Simple GCJ-02 to WGS-84 conversion (approximation)
double gcjTransformLat(double x, double y) {
    double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x));
    ret += (20.0 * sin(6.0 * x * M_PI) + 20.0 * sin(2.0 * x * M_PI)) * 2.0 / 3.0;
    ret += (20.0 * sin(y * M_PI) + 40.0 * sin(y / 3.0 * M_PI)) * 2.0 / 3.0;
    ret += (160.0 * sin(y / 12.0 * M_PI) + 320.0 * sin(y * M_PI / 30.0)) * 2.0 / 3.0;
    return ret;
}

double gcjTransformLng(double x, double y) {
    double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x));
    ret += (20.0 * sin(6.0 * x * M_PI) + 20.0 * sin(2.0 * x * M_PI)) * 2.0 / 3.0;
    ret += (20.0 * sin(x * M_PI) + 40.0 * sin(x / 3.0 * M_PI)) * 2.0 / 3.0;
    ret += (150.0 * sin(x / 12.0 * M_PI) + 300.0 * sin(x / 30.0 * M_PI)) * 2.0 / 3.0;
    return ret;
}

void gcj02ToWgs84(double gcjLat, double gcjLng, double& wgsLat, double& wgsLng) {
    if (gcjLng < 72.004 || gcjLng > 137.8347 || gcjLat < 0.8293 || gcjLat > 55.8271) {
        wgsLat = gcjLat;
        wgsLng = gcjLng;
        return;
    }
    double dLat = gcjTransformLat(gcjLng - 105.0, gcjLat - 35.0);
    double dLng = gcjTransformLng(gcjLng - 105.0, gcjLat - 35.0);
    double radLat = gcjLat / 180.0 * M_PI;
    double magic = sin(radLat);
    magic = 1 - 0.00669342162296594 * magic * magic;
    double sqrtMagic = sqrt(magic);
    double mLat = (dLat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594)) / (magic * sqrtMagic) * M_PI);
    double mLng = (dLng * 180.0) / (6378245.0 / sqrtMagic * cos(radLat) * M_PI);
    wgsLat = gcjLat - mLat;
    wgsLng = gcjLng - mLng;
}

void writeStateJson(double lat, double lng, const SatStatus& status, long long timestamp_ms) {
    nlohmann::json j;
    j["active"] = true;
    j["lat_wgs84"] = lat;
    j["lng_wgs84"] = lng;
    j["satellites_count"] = status.satellites_count;
    j["satellites_used"] = status.satellites_used;
    j["hdop"] = status.hdop;
    j["vdop"] = status.vdop;
    j["pdop"] = status.pdop;
    j["fix_type"] = "3D";
    j["timestamp"] = timestamp_ms;
    
    nlohmann::json sats = nlohmann::json::array();
    for (const auto& s : status.satellites) {
        nlohmann::json sj;
        sj["prn"] = s.prn;
        sj["constellation"] = (int)s.type;
        sj["elevation"] = s.elevation;
        sj["azimuth"] = s.azimuth;
        sj["snr"] = s.snr;
        sj["used"] = s.used_in_fix;
        sats.push_back(sj);
    }
    j["satellites"] = sats;

    std::ofstream out(STATE_PATH);
    if (out.is_open()) {
        out << j.dump(4);
        out.close();
    }
}

int main() {
    LOGI("GNSS Spoof Daemon started.");

    ConfigWatcher watcher(CONFIG_PATH);
    watcher.start();

    SatSimulator satSimulator;
    NmeaGenerator nmeaGenerator;

    // Create a named pipe (FIFO) or character device substitute if it doesn't exist
    // In a real KernelSU environment, we'd use mknod to create a character device,
    // but for compatibility with OverlayFS and simplicity, a FIFO works exactly the same for reading.
    unlink(DEVICE_PATH.c_str());
    if (mkfifo(DEVICE_PATH.c_str(), 0666) != 0) {
        LOGE("Failed to create FIFO at %s", DEVICE_PATH.c_str());
        // Fallback to regular file or continue? We should probably fail, but let's just open with O_CREAT
    }
    chmod(DEVICE_PATH.c_str(), 0666);

    long long current_timestamp_ms = 0;
    double current_lat = 0;
    double current_lng = 0;
    float current_speed_knots = 0;
    float current_bearing = 0;
    int current_route_index = 0;

    while (true) {
        SpoofConfig config = watcher.getConfig();

        if (!config.active) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
            continue;
        }

        // Handle initialization
        if (current_timestamp_ms == 0 || !config.is_route_mode) {
            current_timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            gcj02ToWgs84(config.lat, config.lng, current_lat, current_lng);
            current_bearing = config.sim_bearing;
            current_speed_knots = 0; // Assume static or handled by joystick UI which just updates lat/lng directly

            // Small jitter like LocationHooker
            double driftLat = sin(current_timestamp_ms / 10000.0) * 0.000015;
            double driftLng = cos(current_timestamp_ms / 12000.0) * 0.000015;
            current_lat += driftLat;
            current_lng += driftLng;

        } else if (config.is_route_mode && config.route.size() > 1) {
            // Simplified route walking logic (for demonstration)
            if (current_route_index >= config.route.size()) {
                current_route_index = 0; // loop
            }
            double target_lat, target_lng;
            gcj02ToWgs84(config.route[current_route_index].lat, config.route[current_route_index].lng, target_lat, target_lng);
            
            double dist = getDistance(current_lat, current_lng, target_lat, target_lng);
            if (dist < 5.0) { // reached point
                current_route_index++;
            } else {
                current_bearing = getBearing(current_lat, current_lng, target_lat, target_lng);
                double speed_m_s = 5.0; // 5 m/s (~18 km/h)
                current_speed_knots = speed_m_s * 1.94384;
                
                // Move towards target
                double radBearing = current_bearing * M_PI / 180.0;
                double d = speed_m_s / 6378137.0; // angular distance
                double radLat = current_lat * M_PI / 180.0;
                double radLng = current_lng * M_PI / 180.0;
                
                double newRadLat = asin(sin(radLat)*cos(d) + cos(radLat)*sin(d)*cos(radBearing));
                double newRadLng = radLng + atan2(sin(radBearing)*sin(d)*cos(radLat), cos(d)-sin(radLat)*sin(newRadLat));
                
                current_lat = newRadLat * 180.0 / M_PI;
                current_lng = newRadLng * 180.0 / M_PI;
            }
            current_timestamp_ms += 1000;
        }

        SatStatus satStatus = satSimulator.simulate(current_lat, current_lng, current_timestamp_ms);
        std::vector<std::string> nmeaSentences = nmeaGenerator.generate(current_lat, current_lng, current_speed_knots, current_bearing, current_timestamp_ms, satStatus);

        writeStateJson(current_lat, current_lng, satStatus, current_timestamp_ms);

        // Write NMEA to FIFO device. Open with O_WRONLY | O_NONBLOCK. If no reader (HAL), it fails, which is fine.
        int fd = open(DEVICE_PATH.c_str(), O_WRONLY | O_NONBLOCK);
        if (fd >= 0) {
            for (const auto& sentence : nmeaSentences) {
                write(fd, sentence.c_str(), sentence.length());
            }
            close(fd);
        } else {
            // LOGI("Failed to write NMEA to %s (no reader)", DEVICE_PATH.c_str());
            // It's normal if HAL is not reading yet.
            // If it's not a FIFO but a file, it would just open successfully, but we made it a FIFO.
        }

        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    watcher.stop();
    return 0;
}
