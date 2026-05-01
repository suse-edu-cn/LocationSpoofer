#!/system/bin/sh
# Wait until system is fully booted
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done

# Remove cell and wifi from the list of radios that get disabled in airplane mode
settings put global airplane_mode_radios "bluetooth,nfc,wimax"

# Ensure they remain toggleable manually
settings put global airplane_mode_toggleable_radios "bluetooth,wifi,nfc"
