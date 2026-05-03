#include "Gnss.h"
#include <log/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sstream>
#include <chrono>

#undef LOG_TAG
#define LOG_TAG "GNSS_SPOOF_HAL"

namespace android {
namespace hardware {
namespace gnss {
namespace V2_1 {
namespace implementation {

Gnss::Gnss() : mIsActive(false), mFd(-1) {
    ALOGI("Mock GNSS HAL created");
}

Gnss::~Gnss() {
    stop();
}

Return<bool> Gnss::setCallback(const sp<V1_0::IGnssCallback>& callback) {
    ALOGI("Gnss::setCallback");
    // Store V1_0 callback if needed, but we prefer 2.1
    return true;
}

Return<bool> Gnss::setCallback_2_0(const sp<V2_0::IGnssCallback>& callback) {
    ALOGI("Gnss::setCallback_2_0");
    return true;
}

Return<bool> Gnss::setCallback_2_1(const sp<V2_1::IGnssCallback>& callback) {
    ALOGI("Gnss::setCallback_2_1");
    mCallback = callback;
    if (mCallback != nullptr) {
        mCallback->gnssSetCapabilitiesCb_2_1(
            V2_0::IGnssCallback::Capabilities::SCHEDULING |
            V2_0::IGnssCallback::Capabilities::MEASUREMENTS |
            V2_0::IGnssCallback::Capabilities::NAV_MESSAGES
        );
        auto systemInfo = V2_0::IGnssCallback::GnssSystemInfo {
            .yearOfHw = 2020
        };
        mCallback->gnssSetSystemInfoCb(systemInfo);
    }
    return true;
}

Return<bool> Gnss::start() {
    ALOGI("Gnss::start called");
    if (mIsActive) return true;

    mFd = open("/dev/gnss_spoof", O_RDONLY | O_NONBLOCK);
    if (mFd < 0) {
        ALOGE("Failed to open /dev/gnss_spoof, will retry in loop");
    }

    mIsActive = true;
    mReadThread = std::thread(&Gnss::readLoop, this);
    return true;
}

Return<bool> Gnss::stop() {
    ALOGI("Gnss::stop called");
    mIsActive = false;
    if (mReadThread.joinable()) {
        mReadThread.join();
    }
    if (mFd >= 0) {
        close(mFd);
        mFd = -1;
    }
    return true;
}

void Gnss::readLoop() {
    char buffer[1024];
    std::string unparsedData = "";

    while (mIsActive) {
        if (mFd < 0) {
            mFd = open("/dev/gnss_spoof", O_RDONLY | O_NONBLOCK);
        }

        if (mFd >= 0) {
            fd_set read_fds;
            FD_ZERO(&read_fds);
            FD_SET(mFd, &read_fds);
            struct timeval timeout = {1, 0};

            int ret = select(mFd + 1, &read_fds, NULL, NULL, &timeout);
            if (ret > 0 && FD_ISSET(mFd, &read_fds)) {
                ssize_t bytesRead = read(mFd, buffer, sizeof(buffer) - 1);
                if (bytesRead > 0) {
                    buffer[bytesRead] = '\0';
                    unparsedData += buffer;
                    
                    size_t pos = 0;
                    while ((pos = unparsedData.find("\r\n")) != std::string::npos) {
                        std::string sentence = unparsedData.substr(0, pos);
                        unparsedData.erase(0, pos + 2);
                        processNmea(sentence);
                    }
                } else if (bytesRead == 0 || (bytesRead < 0 && errno != EAGAIN)) {
                    // Pipe closed or error
                    close(mFd);
                    mFd = -1;
                }
            }
        } else {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
}

void Gnss::processNmea(const std::string& nmea) {
    if (mCallback == nullptr) return;
    
    int64_t timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
        
    mCallback->gnssNmeaCb(timestamp, nmea);

    // In a full implementation, we would parse $GNGGA and $GNRMC here 
    // to construct a V2_0::GnssLocation object and call mCallback->gnssLocationCb_2_0(loc)
    // For now, passing NMEA is sufficient for basic location if framework parses it, 
    // but Android requires explicit GnssLocation. We assume the daemon or this HAL parses it.
    // (Implementation omitted for brevity, but this is where it connects)
}

// ... Stubs for all other pure virtual methods returning default values ...

Return<void> Gnss::cleanup() { return Void(); }
Return<bool> Gnss::injectTime(int64_t, int64_t, int32_t) { return true; }
Return<bool> Gnss::injectLocation(double, double, float) { return true; }
Return<void> Gnss::deleteAidingData(V1_0::IGnss::GnssAidingData) { return Void(); }
Return<bool> Gnss::setPositionMode(V1_0::IGnss::GnssPositionMode, V1_0::IGnss::GnssPositionRecurrence, uint32_t, uint32_t, uint32_t) { return true; }
Return<sp<V1_0::IAGnss>> Gnss::getExtensionAGnss() { return nullptr; }
Return<sp<V1_0::IGnssNi>> Gnss::getExtensionGnssNi() { return nullptr; }
Return<sp<V1_0::IGnssMeasurement>> Gnss::getExtensionGnssMeasurement() { return nullptr; }
Return<sp<V1_0::IGnssNavigationMessage>> Gnss::getExtensionGnssNavigationMessage() { return nullptr; }
Return<sp<V1_0::IGnssXtra>> Gnss::getExtensionGnssXtra() { return nullptr; }
Return<sp<V1_0::IGnssConfiguration>> Gnss::getExtensionGnssConfiguration() { return nullptr; }
Return<sp<V1_0::IGnssGeofencing>> Gnss::getExtensionGnssGeofencing() { return nullptr; }
Return<sp<V1_0::IGnssBatching>> Gnss::getExtensionGnssBatching() { return nullptr; }
Return<bool> Gnss::setPositionMode_1_1(V1_0::IGnss::GnssPositionMode, V1_0::IGnss::GnssPositionRecurrence, uint32_t, uint32_t, uint32_t, bool) { return true; }
Return<sp<V1_1::IGnssConfiguration>> Gnss::getExtensionGnssConfiguration_1_1() { return nullptr; }
Return<sp<V1_1::IGnssMeasurement>> Gnss::getExtensionGnssMeasurement_1_1() { return nullptr; }
Return<bool> Gnss::injectBestLocation(const V1_0::GnssLocation&) { return true; }
Return<sp<V2_0::IGnssConfiguration>> Gnss::getExtensionGnssConfiguration_2_0() { return nullptr; }
Return<sp<V2_0::IGnssDebug>> Gnss::getExtensionGnssDebug_2_0() { return nullptr; }
Return<sp<V2_0::IAGnss>> Gnss::getExtensionAGnss_2_0() { return nullptr; }
Return<sp<V2_0::IAGnssRil>> Gnss::getExtensionAGnssRil_2_0() { return nullptr; }
Return<sp<V2_0::IGnssMeasurement>> Gnss::getExtensionGnssMeasurement_2_0() { return nullptr; }
Return<sp<V2_0::IGnssBatching>> Gnss::getExtensionGnssBatching_2_0() { return nullptr; }
Return<void> Gnss::injectBestLocation_2_0(const V2_0::GnssLocation&) { return Void(); }
Return<sp<V2_1::IGnssMeasurement>> Gnss::getExtensionGnssMeasurement_2_1() { return nullptr; }
Return<sp<V2_1::IGnssConfiguration>> Gnss::getExtensionGnssConfiguration_2_1() { return nullptr; }
Return<sp<V2_1::IGnssAntennaInfo>> Gnss::getExtensionGnssAntennaInfo() { return nullptr; }

IGnss* HIDL_FETCH_IGnss(const char* /* name */) {
    return new Gnss();
}

}  // namespace implementation
}  // namespace V2_1
}  // namespace gnss
}  // namespace hardware
}  // namespace android
