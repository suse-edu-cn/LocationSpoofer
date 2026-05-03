#include "nmea_generator.h"
#include <sstream>
#include <iomanip>
#include <ctime>
#include <cmath>
#include <sys/time.h>

std::string NmeaGenerator::calculateChecksum(const std::string& sentence) {
    int checksum = 0;
    for (char c : sentence) {
        checksum ^= c;
    }
    std::stringstream ss;
    ss << std::hex << std::uppercase << std::setfill('0') << std::setw(2) << checksum;
    return ss.str();
}

std::string NmeaGenerator::formatLat(double lat) {
    char ns = lat >= 0 ? 'N' : 'S';
    lat = std::abs(lat);
    int degrees = (int)lat;
    double minutes = (lat - degrees) * 60.0;
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%02d%07.4f,%c", degrees, minutes, ns);
    return std::string(buffer);
}

std::string NmeaGenerator::formatLng(double lng) {
    char ew = lng >= 0 ? 'E' : 'W';
    lng = std::abs(lng);
    int degrees = (int)lng;
    double minutes = (lng - degrees) * 60.0;
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%03d%07.4f,%c", degrees, minutes, ew);
    return std::string(buffer);
}

std::string NmeaGenerator::formatTime(long long timestamp_ms) {
    time_t rawtime = timestamp_ms / 1000;
    struct tm* timeinfo = gmtime(&rawtime);
    char buffer[16];
    strftime(buffer, sizeof(buffer), "%H%M%S.00", timeinfo);
    return std::string(buffer);
}

std::string NmeaGenerator::formatDate(long long timestamp_ms) {
    time_t rawtime = timestamp_ms / 1000;
    struct tm* timeinfo = gmtime(&rawtime);
    char buffer[16];
    strftime(buffer, sizeof(buffer), "%d%m%y", timeinfo);
    return std::string(buffer);
}

std::string NmeaGenerator::generateGGA(double lat, double lng, long long timestamp_ms, const SatStatus& status) {
    std::stringstream ss;
    ss << "GNGGA," << formatTime(timestamp_ms) << ","
       << formatLat(lat) << "," << formatLng(lng) << ","
       << "1," << status.satellites_used << "," << std::fixed << std::setprecision(1) << status.hdop << ","
       << "50.0,M,-3.0,M,,";
    std::string sentence = ss.str();
    return "$" + sentence + "*" + calculateChecksum(sentence) + "\r\n";
}

std::string NmeaGenerator::generateRMC(double lat, double lng, float speed_knots, float bearing, long long timestamp_ms) {
    std::stringstream ss;
    ss << "GNRMC," << formatTime(timestamp_ms) << ",A,"
       << formatLat(lat) << "," << formatLng(lng) << ","
       << std::fixed << std::setprecision(1) << speed_knots << "," << bearing << ","
       << formatDate(timestamp_ms) << ",,,A";
    std::string sentence = ss.str();
    return "$" + sentence + "*" + calculateChecksum(sentence) + "\r\n";
}

std::vector<std::string> NmeaGenerator::generateGSV(const std::string& talker, int constellation_id, const std::vector<SatelliteInfo>& all_sats) {
    std::vector<SatelliteInfo> sats;
    for (const auto& s : all_sats) {
        if ((int)s.type == constellation_id) sats.push_back(s);
    }
    
    std::vector<std::string> results;
    if (sats.empty()) return results;

    int total_msgs = (sats.size() + 3) / 4;
    for (int i = 0; i < total_msgs; ++i) {
        std::stringstream ss;
        ss << talker << "GSV," << total_msgs << "," << (i + 1) << "," << sats.size();
        for (int j = 0; j < 4; ++j) {
            int idx = i * 4 + j;
            if (idx < sats.size()) {
                const auto& s = sats[idx];
                ss << "," << s.prn << "," << (int)s.elevation << "," << (int)s.azimuth << "," << (int)s.snr;
            } else {
                ss << ",,,,";
            }
        }
        std::string sentence = ss.str();
        results.push_back("$" + sentence + "*" + calculateChecksum(sentence) + "\r\n");
    }
    return results;
}

std::string NmeaGenerator::generateGSA(const std::string& talker, int system_id, const std::vector<SatelliteInfo>& all_sats, const SatStatus& status) {
    std::vector<SatelliteInfo> sats;
    for (const auto& s : all_sats) {
        if ((int)s.type == system_id && s.used_in_fix) sats.push_back(s);
    }

    std::stringstream ss;
    ss << talker << "GSA,A,3";
    for (int i = 0; i < 12; ++i) {
        if (i < sats.size()) ss << "," << sats[i].prn;
        else ss << ",";
    }
    ss << "," << std::fixed << std::setprecision(1) << status.pdop << "," 
       << status.hdop << "," << status.vdop;
    if (system_id == 1) ss << ",1"; // System ID (1=GPS) for NMEA 4.10+
    else if (system_id == 2) ss << ",2";
    else if (system_id == 5) ss << ",4"; // Beidou
       
    std::string sentence = ss.str();
    return "$" + sentence + "*" + calculateChecksum(sentence) + "\r\n";
}

std::vector<std::string> NmeaGenerator::generate(double lat, double lng, float speed_knots, float bearing, long long timestamp_ms, const SatStatus& status) {
    std::vector<std::string> output;
    output.push_back(generateGGA(lat, lng, timestamp_ms, status));
    output.push_back(generateRMC(lat, lng, speed_knots, bearing, timestamp_ms));
    
    auto gps_gsv = generateGSV("GP", (int)ConstellationType::GPS, status.satellites);
    output.insert(output.end(), gps_gsv.begin(), gps_gsv.end());
    auto glo_gsv = generateGSV("GL", (int)ConstellationType::GLONASS, status.satellites);
    output.insert(output.end(), glo_gsv.begin(), glo_gsv.end());
    auto bds_gsv = generateGSV("GB", (int)ConstellationType::BDS, status.satellites);
    output.insert(output.end(), bds_gsv.begin(), bds_gsv.end());

    output.push_back(generateGSA("GN", (int)ConstellationType::GPS, status.satellites, status));
    output.push_back(generateGSA("GN", (int)ConstellationType::GLONASS, status.satellites, status));
    output.push_back(generateGSA("GN", (int)ConstellationType::BDS, status.satellites, status));

    return output;
}
