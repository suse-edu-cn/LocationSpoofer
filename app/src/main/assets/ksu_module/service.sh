#!/system/bin/sh

MODDIR=${0%/*}

# Wait for system to fully boot
sleep 15

# ===== 1. Launch GNSS Spoof Daemon =====
DAEMON="${MODDIR}/bin/gnss_spoof_daemon"

if [ -f "$DAEMON" ]; then
    chmod 755 "$DAEMON"
    # Kill any existing instance
    killall gnss_spoof_daemon 2>/dev/null
    nohup "$DAEMON" > /dev/null 2>&1 &
fi

# ===== 2. Force GPS-Only Mode =====
# Disable Google Location Accuracy (Network Location Provider)
settings put secure location_mode 3

# ===== 3. Disable WiFi & BLE Scanning =====
settings put global wifi_scan_always_enabled 0
settings put global ble_scan_always_enabled 0

# ===== 4. Block SUPL/A-GPS (iptables) =====
iptables -A OUTPUT -p tcp -d supl.google.com -j DROP 2>/dev/null
iptables -A OUTPUT -p udp -d supl.google.com -j DROP 2>/dev/null
iptables -A OUTPUT -p tcp -d supl.qxwz.com -j DROP 2>/dev/null
iptables -A OUTPUT -p udp -d supl.qxwz.com -j DROP 2>/dev/null
iptables -A OUTPUT -p tcp --dport 7275 -j DROP 2>/dev/null
iptables -A OUTPUT -p udp --dport 7275 -j DROP 2>/dev/null

# ===== 5. Clear Location Cache =====
sleep 5
cmd location providers send-extra-command gps android:clear_location_data 2>/dev/null
