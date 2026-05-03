#pragma once

#include <vector>
#include <cstdint>

enum class ConstellationType {
    GPS = 1,
    GLONASS = 2,
    BDS = 5
};

struct SatelliteInfo {
    int prn;
    ConstellationType type;
    float elevation;
    float azimuth;
    float snr;
    bool used_in_fix;
};

struct SatStatus {
    std::vector<SatelliteInfo> satellites;
    int satellites_count = 0;
    int satellites_used = 0;
    float hdop = 1.0f;
    float vdop = 1.0f;
    float pdop = 1.5f;
};

class SatSimulator {
public:
    SatSimulator();
    
    // Simulate satellite constellation based on time and location
    SatStatus simulate(double lat, double lng, long long timestamp_ms);

private:
    float randomFloat(float min, float max);
};
