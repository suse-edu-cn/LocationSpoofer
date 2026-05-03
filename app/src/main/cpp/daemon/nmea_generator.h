#pragma once

#include "sat_simulator.h"
#include <string>
#include <vector>

class NmeaGenerator {
public:
    // Generate a set of NMEA strings (GGA, RMC, GSV, GSA) for the given location and satellite status
    std::vector<std::string> generate(double lat, double lng, float speed_knots, float bearing, long long timestamp_ms, const SatStatus& status);

private:
    std::string formatLat(double lat);
    std::string formatLng(double lng);
    std::string formatTime(long long timestamp_ms);
    std::string formatDate(long long timestamp_ms);
    std::string calculateChecksum(const std::string& sentence);
    
    std::string generateGGA(double lat, double lng, long long timestamp_ms, const SatStatus& status);
    std::string generateRMC(double lat, double lng, float speed_knots, float bearing, long long timestamp_ms);
    std::vector<std::string> generateGSV(const std::string& talker, int constellation_id, const std::vector<SatelliteInfo>& sats);
    std::string generateGSA(const std::string& talker, int system_id, const std::vector<SatelliteInfo>& sats, const SatStatus& status);
};
