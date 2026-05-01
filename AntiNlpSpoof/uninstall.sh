#!/system/bin/sh
# Anti-NLP Spoof Module - Uninstall cleanup
# Restores network location provider settings

# Re-enable network location provider
settings put secure location_providers_allowed "+network"
settings put secure location_mode 3

# Re-enable A-GPS
settings put global assisted_gps_enabled 1
settings put global location_accuracy_enabled 1 2>/dev/null
settings put secure enhanced_location_accuracy 1 2>/dev/null

# Restore airplane mode radios
settings put global airplane_mode_radios "cell,bluetooth,wifi,nfc,wimax"

# Re-enable known NLP packages
NLP_PACKAGES="
com.google.android.gms.location.nlp
com.google.android.location
com.amap.android.location
com.amap.android.ams
com.baidu.map.location
com.tencent.android.location
com.huawei.lbs
com.huawei.location
com.xiaomi.metoknlp
com.coloros.pcmcs
com.coloros.location
com.oplus.location
com.vivo.lbs
com.qualcomm.location
com.mediatek.location
"

for pkg in $NLP_PACKAGES; do
    pm enable "$pkg" 2>/dev/null
done
