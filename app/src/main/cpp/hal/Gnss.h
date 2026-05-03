#pragma once

#include <android/hardware/gnss/2.1/IGnss.h>
#include <android/hardware/gnss/2.1/IGnssCallback.h>
#include <android/hardware/gnss/2.1/IGnssMeasurement.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>
#include <thread>
#include <atomic>
#include <mutex>
#include <vector>

namespace android {
namespace hardware {
namespace gnss {
namespace V2_1 {
namespace implementation {

using ::android::hardware::hidl_array;
using ::android::hardware::hidl_memory;
using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::sp;

struct Gnss : public V2_1::IGnss {
    Gnss();
    ~Gnss();

    // Methods from V1_0::IGnss follow.
    Return<bool> setCallback(const sp<V1_0::IGnssCallback>& callback) override;
    Return<bool> start() override;
    Return<bool> stop() override;
    Return<void> cleanup() override;
    Return<bool> injectTime(int64_t timeMs, int64_t timeReferenceMs, int32_t uncertaintyMs) override;
    Return<bool> injectLocation(double latitudeDegrees, double longitudeDegrees, float accuracyMeters) override;
    Return<void> deleteAidingData(V1_0::IGnss::GnssAidingData aidingData) override;
    Return<bool> setPositionMode(V1_0::IGnss::GnssPositionMode mode, V1_0::IGnss::GnssPositionRecurrence recurrence, uint32_t minIntervalMs, uint32_t preferredAccuracyMeters, uint32_t preferredTimeMs) override;
    
    Return<sp<V1_0::IAGnss>> getExtensionAGnss() override;
    Return<sp<V1_0::IGnssNi>> getExtensionGnssNi() override;
    Return<sp<V1_0::IGnssMeasurement>> getExtensionGnssMeasurement() override;
    Return<sp<V1_0::IGnssNavigationMessage>> getExtensionGnssNavigationMessage() override;
    Return<sp<V1_0::IGnssXtra>> getExtensionGnssXtra() override;
    Return<sp<V1_0::IGnssConfiguration>> getExtensionGnssConfiguration() override;
    Return<sp<V1_0::IGnssGeofencing>> getExtensionGnssGeofencing() override;
    Return<sp<V1_0::IGnssBatching>> getExtensionGnssBatching() override;

    // Methods from V1_1::IGnss follow.
    Return<bool> setPositionMode_1_1(V1_0::IGnss::GnssPositionMode mode, V1_0::IGnss::GnssPositionRecurrence recurrence, uint32_t minIntervalMs, uint32_t preferredAccuracyMeters, uint32_t preferredTimeMs, bool lowPowerMode) override;
    Return<sp<V1_1::IGnssConfiguration>> getExtensionGnssConfiguration_1_1() override;
    Return<sp<V1_1::IGnssMeasurement>> getExtensionGnssMeasurement_1_1() override;
    Return<bool> injectBestLocation(const V1_0::GnssLocation& location) override;

    // Methods from V2_0::IGnss follow.
    Return<bool> setCallback_2_0(const sp<V2_0::IGnssCallback>& callback) override;
    Return<sp<V2_0::IGnssConfiguration>> getExtensionGnssConfiguration_2_0() override;
    Return<sp<V2_0::IGnssDebug>> getExtensionGnssDebug_2_0() override;
    Return<sp<V2_0::IAGnss>> getExtensionAGnss_2_0() override;
    Return<sp<V2_0::IAGnssRil>> getExtensionAGnssRil_2_0() override;
    Return<sp<V2_0::IGnssMeasurement>> getExtensionGnssMeasurement_2_0() override;
    Return<sp<V2_0::IGnssBatching>> getExtensionGnssBatching_2_0() override;
    Return<void> injectBestLocation_2_0(const V2_0::GnssLocation& location) override;

    // Methods from V2_1::IGnss follow.
    Return<bool> setCallback_2_1(const sp<V2_1::IGnssCallback>& callback) override;
    Return<sp<V2_1::IGnssMeasurement>> getExtensionGnssMeasurement_2_1() override;
    Return<sp<V2_1::IGnssConfiguration>> getExtensionGnssConfiguration_2_1() override;
    Return<sp<V2_1::IGnssAntennaInfo>> getExtensionGnssAntennaInfo() override;

private:
    void readLoop();
    void processNmea(const std::string& nmea);

    sp<V2_1::IGnssCallback> mCallback;
    std::atomic<bool> mIsActive;
    std::thread mReadThread;
    int mFd;
};

}  // namespace implementation
}  // namespace V2_1
}  // namespace gnss
}  // namespace hardware
}  // namespace android
