#!/system/bin/sh
# Anti-NLP Spoof Module - Disables Network Location Provider
# This prevents cell tower-based location from overriding GPS spoofing

# Wait until system is fully booted
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done

# Small delay to let other services initialize
sleep 5

# 1. Set location mode to GPS-only (device only), disabling network-based location
settings put secure location_mode 1

# 2. Remove 'network' from allowed location providers
settings put secure location_providers_allowed gps

# 3. Disable A-GPS assisted location
settings put global assisted_gps_enabled 0

# 4. Disable location accuracy improvement
settings put global location_accuracy_enabled 0 2>/dev/null

# 5. Disable enhanced location accuracy
settings put secure enhanced_location_accuracy 0 2>/dev/null

# 6. Remove cell and wifi from airplane_mode_radios
settings put global airplane_mode_radios "bluetooth,nfc,wimax"
settings put global airplane_mode_toggleable_radios "bluetooth,wifi,nfc"

# 7. Disable known NLP packages with correct syntax
disable_pkg() {
    pm disable-user --user 0 "$1" 2>/dev/null || pm disable "$1" 2>/dev/null || true
}

disable_pkg "com.google.android.gms.location.nlp"
disable_pkg "com.google.android.location"
disable_pkg "com.amap.android.location"
disable_pkg "com.amap.android.ams"
disable_pkg "com.baidu.map.location"
disable_pkg "com.tencent.android.location"
disable_pkg "com.huawei.lbs"
disable_pkg "com.huawei.location"
disable_pkg "com.xiaomi.metoknlp"
disable_pkg "com.coloros.pcmcs"
disable_pkg "com.coloros.location"
disable_pkg "com.oplus.location"
disable_pkg "com.vivo.lbs"
disable_pkg "com.qualcomm.location"
disable_pkg "com.mediatek.location"

# 8. Force stop GMS location service to apply changes
am force-stop com.google.android.gms 2>/dev/null
