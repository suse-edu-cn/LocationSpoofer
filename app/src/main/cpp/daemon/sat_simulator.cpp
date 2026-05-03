#include "sat_simulator.h"
#include <cmath>
#include <cstdlib>

SatSimulator::SatSimulator() {
    srand(12345); // Seed
}

float SatSimulator::randomFloat(float min, float max) {
    float scale = rand() / (float) RAND_MAX;
    return min + scale * (max - min);
}

SatStatus SatSimulator::simulate(double lat, double lng, long long timestamp_ms) {
    SatStatus status;
    status.hdop = 1.1f + randomFloat(-0.2f, 0.2f);
    status.vdop = 1.4f + randomFloat(-0.2f, 0.2f);
    status.pdop = 1.8f + randomFloat(-0.2f, 0.2f);

    // Simple fixed distribution for demonstration, in a real scenario we'd use time and orbit data.
    // GPS: 8 satellites
    for (int i = 1; i <= 8; ++i) {
        SatelliteInfo sat;
        sat.prn = i * 3; 
        sat.type = ConstellationType::GPS;
        sat.elevation = 20.0f + (i * 10.0f);
        if (sat.elevation > 85.0f) sat.elevation = 85.0f;
        sat.azimuth = (i * 45.0f);
        sat.snr = 25.0f + (sat.elevation / 90.0f) * 20.0f + randomFloat(-3.0f, 3.0f);
        sat.used_in_fix = true;
        status.satellites.push_back(sat);
    }

    // GLONASS: 4 satellites
    for (int i = 1; i <= 4; ++i) {
        SatelliteInfo sat;
        sat.prn = 64 + i * 2;
        sat.type = ConstellationType::GLONASS;
        sat.elevation = 30.0f + (i * 15.0f);
        sat.azimuth = (i * 90.0f) + 15.0f;
        sat.snr = 25.0f + (sat.elevation / 90.0f) * 20.0f + randomFloat(-2.0f, 2.0f);
        sat.used_in_fix = true;
        status.satellites.push_back(sat);
    }

    // BDS: 4 satellites
    for (int i = 1; i <= 4; ++i) {
        SatelliteInfo sat;
        sat.prn = 200 + i * 4;
        sat.type = ConstellationType::BDS;
        sat.elevation = 40.0f + (i * 10.0f);
        sat.azimuth = (i * 90.0f) + 45.0f;
        sat.snr = 28.0f + (sat.elevation / 90.0f) * 20.0f + randomFloat(-3.0f, 3.0f);
        sat.used_in_fix = true;
        status.satellites.push_back(sat);
    }

    status.satellites_count = status.satellites.size();
    status.satellites_used = status.satellites.size();

    return status;
}
