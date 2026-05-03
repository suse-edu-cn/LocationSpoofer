# GNSS HAL 级虚拟定位 —— KernelSU 模块强硬接管方案

## 一、背景与问题

### 当前架构

本项目 LocationSpoofer 采用三层架构：

| 层级 | 实现 | 作用 |
|------|------|------|
| Layer 1 | LSPosed Hook (`LocationHooker.kt`, 1217行) | Hook Java API 替换坐标、抹除 mock 标记、伪造 WiFi/Cell/BLE |
| Layer 2 | ContentProvider IPC (`SpooferProvider.kt`) | 进程间传递配置 (坐标/路线/WiFi/Cell JSON) |
| Layer 3 | Test Provider (`SpoofingService.kt` + `LocationInjector.kt`) | `addTestProvider()` / `cmd location` 注入位置 |

另有 KSU 防御模块：`service.sh` iptables 阻断 SUPL，`gps.conf` 关闭 A-GPS 辅助。

### 实际问题

**核心现象：关闭飞行模式后，模拟定位失效。**

注意：飞行模式下开启 WiFi 是可以正常定位的（WiFi 定位可用），且此时模拟定位**可以工作**。问题出在**关闭飞行模式后**——真实 GPS/基站/WiFi 全部上线，真实定位数据淹没了虚拟数据。

根本原因：

1. **Test Provider 被真实数据覆盖**：`SpoofingService` 通过 `setTestProviderLocation()` 注入位置，但真实 GNSS 硬件同时在向 `GnssLocationProvider` 报告真实坐标。`FusedLocationProvider` 同时收到真实 GPS 和虚拟 GPS，真实数据优先级更高，虚拟数据被覆盖。

2. **Hook 无法拦截 HAL 层数据流**：`LocationHooker` Hook 了 `Location.getLatitude()` 等 Java API，但真实位置通过 GNSS HAL → `GnssNative` (JNI) → `GnssLocationProvider` → `LocationProviderManager` 这条链路直接注入系统。Hook 在这条链路的最末端（Java API 层），无法阻止真实数据在上游就已经进入了系统。

3. **进程生命周期依赖**：`SpoofingService` 是 app 进程的前台服务。app 被杀、切后台、系统回收内存时，Service 被终止，虚拟定位立即失效。而真实 GPS 硬件不受影响，继续报告真实位置。

4. **网络定位未被完全控制**：WiFi/基站定位在关闭飞行模式后恢复，`NetworkLocationProvider` 开始报告真实网络位置。即使 Hook 了 `WifiInfo`/`TelephonyManager`，底层的 NLP 数据流未被阻断。

### 真正的需求

1. **强硬接管**：不是"模拟"定位，而是**成为系统唯一的定位来源**。所有 app（包括 GMS FLP）只能从模块获取位置。
2. **模块独立运行**：模块是一个独立的 native daemon，不依赖 app 进程。app 被杀不影响定位。
3. **app 只是遥控器**：app 的唯一职责是写入配置（坐标、路线等），模块读取配置后独立执行。
4. **全场景可用**：飞行模式开/关、WiFi 开/关、蜂窝开/关，定位始终受控。

---

## 二、技术架构

### 设计原则

**从最底层切断真实数据，注入虚拟数据。** 不是在 Java 层拦截，而是在 GNSS HAL 层直接替换。

### 整体架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                         App 进程 (仅 UI)                          │
│                                                                  │
│  MainViewModel ──→ LocationRepository ──→ 写入配置文件            │
│  (高德地图选点)     startSpoofing()        /data/local/tmp/       │
│                     stopSpoofing()        locationspoofer_       │
│                                            config.json           │
│                                                                  │
│  SpooferProvider (ContentProvider) ← 保留，兼容旧版 LSPosed Hook  │
└────────────────────────────┬─────────────────────────────────────┘
                             │ 写入配置文件 / Unix Socket
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│              KernelSU 模块 (独立于 app，系统级常驻)                 │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  gnss_spoof_daemon (C/C++, root native 进程)               │  │
│  │                                                            │  │
│  │  1. inotify 监听配置文件变化                                │  │
│  │  2. 读取 GCJ-02 坐标 → 转换 WGS-84                        │  │
│  │  3. 轨迹模拟 (路线模式/运动模式)                            │  │
│  │  4. 卫星星座模拟 (GPS/GLONASS/BDS, 仰角/方位角/SNR)        │  │
│  │  5. 生成完整 NMEA 语句 (GGA/RMC/GSV/GSA)                  │  │
│  │  6. 通过 /dev/gnss_spoof (字符设备) 输出到自定义 HAL        │  │
│  │  7. 同时更新 /data/local/tmp/gnss_spoof_state.json         │  │
│  │     (供 LSPosed 模块读取卫星数据做反检测)                   │  │
│  └────────────────────────────────────────────────────────────┘  │
│                             │                                    │
│                             ▼                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  自定义 GNSS HAL (替换 vendor HAL)                          │  │
│  │                                                            │  │
│  │  KernelSU OverlayFS 替换:                                  │  │
│  │  /vendor/lib64/hw/android.hardware.gnss@2.1-impl.so       │  │
│  │                                                            │  │
│  │  实现完整的 IGnss HIDL/AIDL 接口:                          │  │
│  │  - setCallback() → 保存 IGnssCallback 引用                 │  │
│  │  - start() → 从 /dev/gnss_spoof 读取 NMEA                 │  │
│  │  - 解析 NMEA → 调用 callback.gnssLocationCb()             │  │
│  │  - 解析 GSV → 调用 callback.gnssSvStatusCb()              │  │
│  │  - stop() → 停止读取                                       │  │
│  │                                                            │  │
│  │  效果: LocationManagerService 收到的全是虚拟数据            │  │
│  │  真实 GNSS 硬件: 被完全绕过，HAL 不从硬件读取任何数据       │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  service.sh (开机脚本)                                      │  │
│  │                                                            │  │
│  │  1. 启动 gnss_spoof_daemon                                 │  │
│  │  2. 强制 GPS-only 模式 (关闭网络定位)                       │  │
│  │     settings put secure location_mode 3                    │  │
│  │  3. 禁用 WiFi 扫描定位                                     │  │
│  │     settings put global wifi_scan_always_enabled 0         │  │
│  │  4. 阻断 SUPL/A-GPS (iptables, 已有)                      │  │
│  │  5. 清除已有位置缓存                                       │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  sepolicy.rule (SELinux 策略)                               │  │
│  │                                                            │  │
│  │  - 允许 gnss_spoof_daemon 读写 /dev/gnss_spoof             │  │
│  │  - 允许 gnss_spoof_daemon 读取 /data/local/tmp/ 配置       │  │
│  │  - 允许自定义 HAL 访问字符设备                              │  │
│  │  - 允许 service.sh 执行 settings/iptables 命令             │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                             │
                             │ OverlayFS 覆盖
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Android 系统 (system_server)                   │
│                                                                  │
│  GNSS HAL 进程 (android.hardware.gnss@2.1-service)               │
│       │                                                          │
│       │ 加载的是被替换后的 android.hardware.gnss@2.1-impl.so     │
│       │ → 从 /dev/gnss_spoof 读取虚拟 NMEA，不读真实硬件         │
│       │                                                          │
│       ▼                                                          │
│  GnssNative (JNI)                                                │
│       │                                                          │
│       ▼                                                          │
│  GnssLocationProvider (Java)                                     │
│       │                                                          │
│       ▼                                                          │
│  LocationProviderManager → LocationManagerService                │
│       │                                                          │
│       ▼                                                          │
│  所有 App 的 LocationListener / FusedLocationProviderClient       │
│  → 收到的全部是虚拟坐标                                          │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│              LSPosed 模块 (LocationHooker, 增强版)                │
│                                                                  │
│  保留现有 Hook (Java 层反检测):                                   │
│  - isMock/isFromMockProvider → false                             │
│  - AMap SDK mock 标记清除                                        │
│  - WiFi/Cell/BLE 伪造                                           │
│                                                                  │
│  新增 Hook (GNSS 信号反检测):                                     │
│  - GnssStatus.Callback → 从 state.json 读取模拟卫星数据          │
│  - GnssMeasurement → 返回模拟测量值                              │
│  - Location extras → 返回合理的卫星数量                           │
│                                                                  │
│  读取: /data/local/tmp/gnss_spoof_state.json (由 daemon 写入)    │
└──────────────────────────────────────────────────────────────────┘
```

### 为什么这个方案能解决问题

| 问题 | 当前方案为何失败 | 新方案如何解决 |
|------|------------------|----------------|
| 关闭飞行模式后真实 GPS 覆盖虚拟数据 | Hook 在 Java 层，HAL 层的真实数据已经进入系统 | 自定义 HAL 从源头替换，真实 GNSS 硬件数据根本不进入系统 |
| FLP 混合真实/虚拟数据 | 只 spoof GPS，NLP (WiFi/Cell) 仍在报告真实位置 | service.sh 强制 GPS-only 模式 + 禁用 WiFi 扫描定位 |
| app 被杀后定位失效 | SpoofingService 随 app 进程死亡 | daemon 是独立 root 进程，不依赖 app |
| 切后台后 Service 被回收 | 前台服务可能被系统回收 | daemon 由 init 启动，OOM 优先级最低 |
| mock 标记被检测 | 已有 Hook 但 HAL 层数据不一致 | HAL 层本身输出的就是"真实" GPS 数据，无 mock 标记 |

---

## 三、模块文件结构

```
/data/adb/modules/location_spoof_master/
├── module.prop                              # 模块描述
│
├── post-fs-data.sh                          # 早期启动 (设置环境)
├── service.sh                               # 系统启动后 (启动 daemon + 配置系统)
├── sepolicy.rule                            # SELinux 策略
│
├── system/
│   └── vendor/
│       ├── lib64/
│       │   └── hw/
│       │       └── android.hardware.gnss@2.1-impl.so   # 自定义 GNSS HAL (arm64)
│       ├── lib/
│       │   └── hw/
│       │       └── android.hardware.gnss@2.1-impl.so   # 自定义 GNSS HAL (arm32)
│       └── etc/
│           ├── gps.conf                      # GPS 配置 (已有，关闭 A-GPS)
│           └── vintf/
│               └── manifest.xml              # VINTF 清单 (可选，覆盖 HAL 声明)
│
├── bin/
│   ├── arm64/
│   │   └── gnss_spoof_daemon                # NMEA 生成守护进程 (arm64)
│   └── arm/
│       └── gnss_spoof_daemon                # NMEA 生成守护进程 (arm32)
│
└── config/
    └── default.json                         # 默认配置 (首次安装时使用)
```

---

## 四、核心组件详细设计

### 4.1 自定义 GNSS HAL (`android.hardware.gnss@2.1-impl.so`)

这是整个方案的核心。通过 KernelSU 的 OverlayFS 替换 vendor 分区的 GNSS HAL 实现。

#### 原理

Android GNSS HAL 是一个独立进程，通过 HIDL (Android 8-12) 或 AIDL (Android 13+) 与 system_server 通信：

```
GNSS HAL 进程 → hwbinder → GnssNative (JNI) → GnssLocationProvider → LocationProviderManager
```

替换 HAL 后，system_server 完全不知道底层换了实现。它照常调用 `IGnss::start()`，我们的 HAL 照常通过 `IGnssCallback::gnssLocationCb()` 回报位置——只不过回报的是虚拟坐标。

#### 必须实现的接口

**HIDL 版本 (Android 8-12)：**

```cpp
// android.hardware.gnss@1.0::IGnss
class Gnss : public V2_1::IGnss {
    // 核心方法
    Return<bool> setCallback(const sp<V1_0::IGnssCallback>& callback);
    Return<bool> start();
    Return<bool> stop();
    Return<bool> setPositionMode(V1_0::GnssPositionMode mode,
                                  V1_0::GnssPositionRecurrence recurrence,
                                  uint32_t minIntervalMs,
                                  uint32_t preferredAccuracyMeters,
                                  uint32_t preferredTimeMs);
    Return<bool> deleteAidingData(V1_0::IGnss::GnssAidingData aidingData);

    // 扩展接口 (返回空实现或 stub)
    Return<sp<V1_0::IAGnss>> getExtensionAGnss();
    Return<sp<V1_0::IGnssNi>> getExtensionGnssNi();
    Return<sp<V1_0::IGnssMeasurement>> getExtensionGnssMeasurement();
    Return<sp<V1_0::IGnssNavigationMessage>> getExtensionGnssNavigationMessage();
    Return<sp<V1_0::IGnssXtra>> getExtensionGnssXtra();
    Return<sp<V1_0::IGnssConfiguration>> getExtensionGnssConfiguration();
    Return<sp<V1_0::IGnssGeofencing>> getExtensionGnssGeofencing();
    Return<sp<V1_0::IGnssBatching>> getExtensionGnssBatching();
    Return<sp<V1_0::IGnssDebug>> getExtensionGnssDebug();
    Return<sp<V2_0::IGnssConfiguration>> getExtensionGnssConfiguration_2_0();
    Return<sp<V2_1::IGnssMeasurement>> getExtensionGnssMeasurement_2_1();
    Return<sp<V2_1::IGnssPowerIndication>> getExtensionGnssPowerIndication();
    Return<bool> setCallback_2_0(const sp<V2_0::IGnssCallback>& callback);
    Return<bool> setCallback_2_1(const sp<V2_1::IGnssCallback>& callback);
};
```

**AIDL 版本 (Android 13+)：**

```cpp
// android.hardware.gnss.IGnss
class Gnss : public BnGnss {
    ndk::ScopedAStatus setCallback(const std::shared_ptr<IGnssCallback>& callback);
    ndk::ScopedAStatus start();
    ndk::ScopedAStatus stop();
    ndk::ScopedAStatus setPositionMode(const PositionModeOptions& options);
    ndk::ScopedAStatus deleteAidingData(GnssAidingData aidingData);
    ndk::ScopedAStatus injectTime(const GnssUtcTime& time, int64_t timeReferenceMs, int32_t uncertaintyMs);
    ndk::ScopedAStatus injectLocation(const GnssLocation& location);
    // ... getExtension*() 方法
};
```

#### 内部工作流程

```
start() 被调用:
  1. 打开 /dev/gnss_spoof 字符设备 (由 daemon 创建)
  2. 启动读取线程

读取线程 (1Hz):
  1. 从字符设备读取 NMEA 数据流
  2. 解析 $GNGGA → 提取 lat, lng, alt, fix_quality, num_satellites, hdop
  3. 解析 $GNRMC → 提取 speed, bearing, timestamp
  4. 解析 $GPGSV/$GLGSV/$GBGSV → 提取卫星信息 (PRN, elevation, azimuth, SNR)
  5. 解析 $GNGSA → 提取 DOP 值
  6. 构造 GnssLocation 对象:
     - latitude, longitude, altitude
     - speed, bearing
     - horizontalAccuracyMeters, verticalAccuracyMeters
     - timestamp (UTC)
  7. 调用 mCallback->gnssLocationCb(gnssLocation) → 位置上报到 framework
  8. 构造 GnssSvStatus 对象:
     - svCount = 可见卫星数
     - svList[] = {svid, constellation, cn0DbHz, elevation, azimuth, ephemerisExists, almanacExists, usedInFix}
  9. 调用 mCallback->gnssSvStatusCb(svStatus) → 卫星状态上报
  10. 调用 mCallback->gnssNmeaCb(timestamp, nmea) → NMEA 原始数据上报

stop() 被调用:
  1. 关闭字符设备
  2. 停止读取线程
```

#### OverlayFS 替换

KernelSU 的模块系统通过 OverlayFS 将模块的 `system/` 目录覆盖到系统分区：

```
模块: /data/adb/modules/location_spoof_master/system/vendor/lib64/hw/android.hardware.gnss@2.1-impl.so
  ↓ OverlayFS 覆盖
系统: /vendor/lib64/hw/android.hardware.gnss@2.1-impl.so (原始文件被隐藏)
```

- dm-verity 不受影响（底层分区未修改）
- 系统 OTA 不受影响（OverlayFS 是运行时覆盖）
- 需要安装 `meta-overlayfs` metamodule (KernelSU)

---

### 4.2 NMEA 生成守护进程 (`gnss_spoof_daemon`)

独立的 C/C++ native 进程，由 `service.sh` 在开机时启动，以 root 权限运行。

#### 职责

1. **读取配置**：监听 `/data/local/tmp/locationspoofer_config.json` 变化 (inotify)
2. **坐标转换**：GCJ-02 (配置文件) → WGS-84 (NMEA 输出)
3. **轨迹计算**：支持静止/步行/跑步/骑行/驾车模式 + 路线跟随
4. **卫星模拟**：根据坐标和时间计算"可见"卫星星座
5. **NMEA 生成**：生成完整、校验和正确的 NMEA 语句集
6. **数据输出**：写入 `/dev/gnss_spoof` 字符设备 + 状态文件

#### NMEA 语句集 (每秒输出)

```
$GNGGA,081234.00,3954.2520,N,11624.4440,E,1,12,0.9,50.0,M,-3.0,M,,*7A
  ↑ UTC时间, 纬度, 经度, 定位质量(1=GPS), 卫星数, HDOP, 海拔

$GNRMC,081234.00,A,3954.2520,N,11624.4440,E,1.2,90.0,030526,,,A*5A
  ↑ UTC时间, 状态(A=有效), 坐标, 速度(kn), 航向, 日期

$GPGSV,3,1,12,01,45,120,38,03,62,245,42,06,30,060,35,09,55,310,40*79
$GPGSV,3,2,12,14,25,180,30,16,40,270,37,19,70,090,44,22,15,340,28*7D
$GPGSV,3,3,12,25,50,150,39,28,35,030,33,31,60,200,41,32,20,100,29*7F
  ↑ GPS 卫星信息: PRN, 仰角, 方位角, 信噪比 (每句最多4颗)

$GLGSV,2,1,6,65,40,180,36,66,55,270,39,70,30,090,33,71,65,330,41*6A
$GLGSV,2,2,6,75,45,150,37,80,25,240,30*5D
  ↑ GLONASS 卫星信息

$GBGSV,2,1,7,201,50,120,39,202,35,240,34,203,60,060,42,204,45,300,37*6B
$GBGSV,2,2,7,205,30,180,32,206,55,090,40,207,20,330,28*6A
  ↑ 北斗卫星信息

$GNGSA,A,3,01,03,06,09,14,16,19,22,25,28,31,32,1.5,0.9,1.2*3E
  ↑ DOP 值: PDOP, HDOP, VDOP
```

#### 卫星星座模拟算法

```c
// 根据目标坐标和当前时间，计算"合理"的卫星分布

typedef struct {
    int prn;              // 卫星编号
    ConstellationType type; // GPS=1, GLONASS=2, BDS=5
    float elevation;      // 仰角 (5-85度)
    float azimuth;        // 方位角 (0-360度)
    float snr;            // 信噪比 (25-50 dBHz)
    bool used_in_fix;     // 是否用于定位
} SatelliteInfo;

// 算法:
// 1. 以目标坐标为圆心，在天球上均匀分布 12-16 颗卫星
// 2. GPS: PRN 1-32, 选取 6-8 颗
// 3. GLONASS: PRN 65-96, 选取 2-4 颗
// 4. BDS: PRN 201-218, 选取 2-4 颗
// 5. 仰角分布: 大部分 20-60 度 (真实环境典型分布)
// 6. 方位角: 均匀覆盖 0-360 度 (避免几何构型过差)
// 7. SNR = 25 + (elevation / 90) * 20 + random(-3, +3)
//    仰角越高 SNR 越强 (真实规律)
// 8. 每 30 秒微调卫星位置 (模拟地球自转，每秒约 0.004 度)
// 9. HDOP = 0.8-1.5, VDOP = 1.0-2.0, PDOP = 1.2-2.5
//    根据卫星几何构型计算 (星分布越均匀 DOP 越低)

// 位置抖动 (与 LocationHooker 的 jitter 同步):
// amplitude = 0.000015 度 (~1.7m)
// period = 10-12 秒
// 使用 sin() 函数产生平滑漂移
```

#### 配置文件协议

输入: `/data/local/tmp/locationspoofer_config.json` (由 app 写入)

```json
{
    "active": true,
    "lat": 39.9042,
    "lng": 116.4074,
    "sim_mode": "WALKING",
    "sim_bearing": 90.0,
    "start_timestamp": 1714700000000,
    "is_route_mode": false,
    "route_json": [
        {"lat": 39.9042, "lng": 116.4074},
        {"lat": 39.9100, "lng": 116.4100}
    ],
    "wifi_json": "[...]",
    "cell_json": "[...]",
    "gnss_simulation": {
        "enabled": true,
        "constellations": ["GPS", "GLONASS", "BDS"],
        "altitude": 50.0
    }
}
```

输出 1: `/dev/gnss_spoof` (字符设备，NMEA 数据流，由自定义 HAL 读取)

输出 2: `/data/local/tmp/gnss_spoof_state.json` (供 LSPosed 模块读取)

```json
{
    "active": true,
    "lat_wgs84": 39.9038,
    "lng_wgs84": 116.4070,
    "satellites": [
        {"prn": 1, "constellation": 1, "elevation": 45, "azimuth": 120, "snr": 38.5, "used": true},
        {"prn": 3, "constellation": 1, "elevation": 62, "azimuth": 245, "snr": 42.1, "used": true},
        {"prn": 65, "constellation": 2, "elevation": 35, "azimuth": 180, "snr": 34.2, "used": true},
        {"prn": 201, "constellation": 5, "elevation": 50, "azimuth": 60, "snr": 39.0, "used": true}
    ],
    "satellites_count": 12,
    "satellites_used": 10,
    "hdop": 1.1,
    "vdop": 1.4,
    "pdop": 1.8,
    "fix_type": "3D",
    "timestamp": 1714700001000
}
```

---

### 4.3 service.sh (系统启动脚本)

```bash
#!/system/bin/sh

# 等待系统启动完成
sleep 15

MODDIR="${0%/*}"

# ===== 1. 启动 GNSS 模拟守护进程 =====
ARCH=$(getprop ro.product.cpu.abi)
if [[ "$ARCH" == *"arm64"* ]]; then
    DAEMON="${MODDIR}/bin/arm64/gnss_spoof_daemon"
else
    DAEMON="${MODDIR}/bin/arm/gnss_spoof_daemon"
fi
chmod 755 "$DAEMON"
nohup "$DAEMON" &

# ===== 2. 强制 GPS-only 模式 =====
# 关闭 Google Location Accuracy (网络辅助定位)
settings put secure location_mode 3   # 3 = DEVICE_ONLY (仅 GPS)

# ===== 3. 禁用 WiFi 扫描定位 =====
settings put global wifi_scan_always_enabled 0

# ===== 4. 禁用蓝牙扫描定位 =====
settings put global ble_scan_always_enabled 0

# ===== 5. 阻断 SUPL/A-GPS (已有逻辑) =====
iptables -A OUTPUT -p tcp -d supl.google.com -j DROP 2>/dev/null
iptables -A OUTPUT -p udp -d supl.google.com -j DROP 2>/dev/null
iptables -A OUTPUT -p tcp -d supl.qxwz.com -j DROP 2>/dev/null
iptables -A OUTPUT -p udp -d supl.qxwz.com -j DROP 2>/dev/null
iptables -A OUTPUT -p tcp --dport 7275 -j DROP 2>/dev/null
iptables -A OUTPUT -p udp --dport 7275 -j DROP 2>/dev/null

# ===== 6. 清除位置缓存 =====
# 延迟执行，等待 LocationManagerService 启动
sleep 5
cmd location providers send-extra-command gps android:clear_location_data 2>/dev/null
```

### 4.4 sepolicy.rule (SELinux 策略)

```
# 允许 gnss_spoof_daemon 作为 root 运行
allow su device:chr_file { read write open ioctl };

# 允许 HAL 进程读取虚拟字符设备
allow hal_gnss_default device:chr_file { read write open ioctl };

# 允许 daemon 读写 /data/local/tmp/
allow untrusted_data_file system_data_file:file { read write open create };

# 允许 service.sh 执行 settings 和 iptables
allow shell system_data_file:file { read write open };
```

---

### 4.5 LSPosed 模块增强 (LocationHooker)

现有 Hook 全部保留，新增 GNSS 信号级反检测：

```kotlin
// 新增: 从 daemon 的状态文件读取卫星数据
private fun readGnssState(): JSONObject? {
    val file = File("/data/local/tmp/gnss_spoof_state.json")
    if (!file.exists()) return null
    return try {
        JSONObject(file.readText())
    } catch (e: Exception) { null }
}

// 新增: Hook GnssStatus.Callback
private fun hookGnssStatus(classLoader: ClassLoader) {
    // Hook GnssStatus 的方法，返回模拟卫星数据
    // GnssStatus.getSatelliteCount() → 从 state.json 读取
    // GnssStatus.getConstellationType(i) → 从 state.json 读取
    // GnssStatus.getSvid(i) → 从 state.json 读取
    // GnssStatus.getElevationDegrees(i) → 从 state.json 读取
    // GnssStatus.getAzimuthDegrees(i) → 从 state.json 读取
    // GnssStatus.getCn0DbHz(i) → 从 state.json 读取
    // GnssStatus.usedInFix(i) → 从 state.json 读取
}

// 新增: Hook Location extras 中的卫星数量
private fun hookLocationExtras() {
    // Location.getExtras().getInt("satellites") → 返回 10-14
}

// 新增: Hook GnssMeasurement (Android 7+)
private fun hookGnssMeasurement(classLoader: ClassLoader) {
    // GnssMeasurement.getCn0DbHz() → 从 state.json 读取
    // GnssMeasurement.getSvid() → 从 state.json 读取
    // GnssMeasurement.getConstellationType() → 从 state.json 读取
}
```

---

### 4.6 App 侧修改

App 进程降级为**纯配置 UI**，不再负责定位注入。

#### LocationRepository 改造

```kotlin
// startSpoofing() 简化为: 只写配置文件
suspend fun startSpoofing(...) {
    // 1. 更新 SpooferProvider (兼容旧版 LSPosed Hook)
    SpooferProvider.isActive = true
    SpooferProvider.latitude = lat
    SpooferProvider.longitude = lng
    // ...

    // 2. 写入配置文件 (daemon 会 inotify 监听)
    configManager.saveConfig(context, lat, lng, true, ...)

    // 3. 不再启动 SpoofingService (daemon 接管)
    // 4. 不再调用 settings put global gps_enabled 0 (daemon 通过 HAL 接管)
    // 5. 不再调用 addTestProvider (daemon 通过 HAL 接管)
}

// stopSpoofing() 简化为: 只写 inactive 配置
suspend fun stopSpoofing(context: Context) {
    SpooferProvider.isActive = false
    configManager.saveConfig(context, 0.0, 0.0, false)
    // daemon 检测到 active=false 后停止注入，恢复真实 HAL 行为
}
```

#### KsuModuleInstaller 改造

需要安装更多文件：

```kotlin
// 安装内容:
// 1. module.prop
// 2. post-fs-data.sh
// 3. service.sh
// 4. sepolicy.rule
// 5. system/vendor/lib64/hw/android.hardware.gnss@2.1-impl.so (arm64)
// 6. system/vendor/lib/hw/android.hardware.gnss@2.1-impl.so (arm32)
// 7. system/vendor/etc/gps.conf
// 8. bin/arm64/gnss_spoof_daemon
// 9. bin/arm/gnss_spoof_daemon
```

---

## 五、飞行模式切换处理

### 场景分析

| 场景 | WiFi | GPS | 蜂窝 | 网络定位 | 定位来源 | 模拟状态 |
|------|------|-----|------|---------|---------|---------|
| 正常模式 | ON | ON | ON | WiFi+Cell | GPS+WiFi+Cell | 需要接管 |
| 飞行模式+WiFi | ON | OFF | OFF | WiFi | WiFi | 需要接管 |
| 纯飞行模式 | OFF | OFF | OFF | 无 | 无 | 需要接管 |
| 正常模式(无WiFi) | OFF | ON | ON | Cell | GPS+Cell | 需要接管 |

### 模块如何应对

**关键：模块通过替换 HAL 接管了 GPS 数据源。** 无论飞行模式如何切换：

1. **GNSS HAL 被替换**：system_server 调用 `IGnss::start()` 时，加载的是我们的 HAL，从 `/dev/gnss_spoof` 读取虚拟 NMEA，不访问真实硬件。
2. **GPS-only 模式**：`service.sh` 设置了 `location_mode=3`，系统只使用 GPS provider。WiFi/Cell 定位被禁用。
3. **飞行模式开启时**：
   - 真实 GPS 硬件断电，但我们的 HAL 不依赖真实硬件
   - WiFi 断开，但 GPS-only 模式下不影响定位
   - daemon 继续输出虚拟 NMEA → HAL 继续上报虚拟位置
4. **飞行模式关闭时**：
   - 真实 GPS 硬件上电，但 HAL 层已被替换，不读取真实硬件
   - WiFi/Cell 恢复，但 `location_mode=3` 下系统不使用它们定位
   - daemon 继续输出虚拟 NMEA → 一切如常

**飞行模式切换对模块完全透明。** 用户可以自由开关飞行模式，定位始终受控。

---

## 六、与现有代码的兼容性

### 保留的组件

| 组件 | 状态 | 原因 |
|------|------|------|
| `LocationHooker.kt` | **保留并增强** | Java 层反检测仍然需要 (isMock, AMap SDK, WiFi/Cell 伪造) |
| `SpooferProvider.kt` | **保留** | ContentProvider IPC 兼容旧版 LSPosed Hook 的 readConfig() |
| `ConfigManager.kt` | **保留** | 配置文件读写，daemon 也读同一文件 |
| `CoordinateUtils.kt` | **保留** | 坐标转换，app 侧 UI 需要 |
| `ksu_module/` 资产 | **扩展** | 新增 HAL .so、daemon、sepolicy 等 |
| `service.sh` | **扩展** | 新增 daemon 启动、GPS-only 设置 |
| `gps.conf` | **保留** | 关闭 A-GPS 辅助 |

### 不再需要的组件

| 组件 | 状态 | 原因 |
|------|------|------|
| `SpoofingService.kt` | **弃用** | daemon + 自定义 HAL 接管了位置注入 |
| `LocationInjector.kt` | **弃用** | `cmd location` 注入方式不再需要 |
| `settings put global gps_enabled 0` | **不再执行** | HAL 替换后真实硬件不被读取，无需禁用 |
| `addTestProvider()` | **不再调用** | 不存在 test provider，不存在 mock 标记 |

### Gradle 依赖变化

```kotlin
// build.gradle.kts (app)
// 新增: Unix Socket 客户端 (与 daemon 通信，可选)
// 可选: 移除 SpoofingService 相关代码
```

---

## 七、实施步骤

### Phase 1: 守护进程 + 字符设备 (独立可测试)

1. **编写 `gnss_spoof_daemon.c`**
   - 配置文件读取 (inotify + JSON 解析)
   - GCJ-02 → WGS-84 转换
   - 卫星星座模拟算法
   - NMEA 语句生成器 (GGA/RMC/GSV/GSA，含校验和)
   - 字符设备创建 (`/dev/gnss_spoof`，需要内核模块或 FUSE)
   - 输出 NMEA 数据流 (1Hz)
   - 输出状态文件 (`gnss_spoof_state.json`)

2. **NDK 编译 daemon**
   - 静态链接 (不依赖系统库)
   - arm64-v8a + armeabi-v7a 双架构
   - 二进制大小目标 < 500KB

3. **测试 daemon**
   - 手动启动，验证 NMEA 输出正确性
   - 用 `cat /dev/gnss_spoof` 查看数据流
   - 用 NMEA 解析工具验证语句格式

### Phase 2: 自定义 GNSS HAL

4. **编写自定义 `android.hardware.gnss@2.1-impl.so`**
   - 实现 `IGnss` 接口 (HIDL 版本，兼容 Android 8-12)
   - 实现 `IGnss` 接口 (AIDL 版本，兼容 Android 13+)
   - `start()`: 打开 `/dev/gnss_spoof`，启动读取线程
   - 读取线程: 解析 NMEA → 调用 `gnssLocationCb()` + `gnssSvStatusCb()` + `gnssNmeaCb()`
   - `stop()`: 关闭设备，停止线程
   - 其他 `getExtension*()` 方法: 返回 stub/空实现

5. **编译 HAL .so**
   - 需要 AOSP 源码树或预编译的 HIDL/AIDL 头文件
   - 导出符号必须与原始 .so 完全一致
   - arm64 + arm32 双架构

6. **OverlayFS 替换测试**
   - 在测试设备上安装 KernelSU
   - 安装 `meta-overlayfs` metamodule
   - 放置自定义 HAL .so 到模块目录
   - 重启，验证 `dumpsys location` 显示 GPS provider 有输出

### Phase 3: 模块集成

7. **编写 `service.sh`**
   - 启动 daemon
   - 设置 GPS-only 模式
   - 禁用 WiFi/BLE 扫描定位
   - 阻断 SUPL

8. **编写 `sepolicy.rule`**
   - 允许 daemon 和 HAL 访问字符设备和配置文件

9. **编写 `module.prop`**
   - 模块 ID、名称、版本、描述

10. **修改 `KsuModuleInstaller.kt`**
    - 安装所有新文件 (HAL .so, daemon, scripts, sepolicy)

11. **修改 `LocationRepository.kt`**
    - `startSpoofing()` 只写配置文件，不启动 Service
    - `stopSpoofing()` 只写 inactive 配置

### Phase 4: LSPosed 增强

12. **增强 `LocationHooker.kt`**
    - 新增 `hookGnssStatus()` — 从 `gnss_spoof_state.json` 读取卫星数据
    - 新增 `hookGnssMeasurement()` — 返回模拟测量值
    - 新增 `hookLocationExtras()` — 返回合理卫星数量

13. **同步 jitter**
    - daemon 的位置抖动参数必须与 LocationHooker 的 jitter 完全一致
    - 共享配置或硬编码相同参数

### Phase 5: 测试验证

14. **功能测试**
    - 关闭飞行模式 → 高德地图 → 显示虚拟坐标
    - 开关飞行模式 → 定位不受影响
    - app 被杀 → 定位继续工作
    - app 切后台 → 定位继续工作
    - 路线模拟 → 坐标按路线移动

15. **反检测测试**
    - `Location.isMock()` → false
    - `GnssStatus.getSatelliteCount()` → 10-14
    - `Location.getExtras().getInt("satellites")` → 合理值
    - RootBeer 检测 → 通过
    - 模拟定位检测 App → "未检测到"

16. **稳定性测试**
    - 24 小时连续运行
    - 反复开关飞行模式
    - 反复开关 WiFi
    - 低内存条件下 app 被杀

---

## 八、风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 不同芯片 GNSS HAL 路径/版本不同 | 部分设备替换失败 | 检测设备 HAL 版本，自动选择正确的 .so；提供多个 HAL 版本 |
| Android 版本差异 (HIDL vs AIDL) | 接口不兼容 | 同时提供 HIDL 和 AIDL 两个版本的 HAL .so |
| KernelSU 版本差异 | 模块格式不同 | 兼容 KernelSU 和 Magisk 的模块格式 |
| 字符设备 `/dev/gnss_spoof` 创建需要内核支持 | 无法创建 | 使用 FUSE 或 tmpfs 替代；或通过 Unix Socket 通信 |
| SELinux 阻止 HAL 访问自定义设备 | HAL 无法读取 NMEA | 通过 sepolicy.rule 添加必要权限 |
| 系统 OTA 更新覆盖 HAL 路径 | 替换失效 | KernelSU OverlayFS 不影响 OTA；OTA 后重新应用 overlay |
| daemon 被系统 OOM killer 杀死 | 虚拟定位中断 | root 进程 + 设置 `oom_score_adj=-1000` + `service.sh` 自动重启 |
| 某些 app 直接读取 `/dev/gnss` 设备 | 绕过 HAL 获取真实数据 | LSPosed Hook 文件访问 API，或在内核层拦截 |

---

## 九、关键文件路径参考

### AOSP 源码 (GNSS 子系统)

```
# GNSS HAL 接口定义
hardware/interfaces/gnss/aidl/android/hardware/gnss/IGnss.aidl
hardware/interfaces/gnss/aidl/android/hardware/gnss/IGnssCallback.aidl
hardware/interfaces/gnss/1.0/IGnss.hal
hardware/interfaces/gnss/2.1/IGnss.hal

# Framework 层
frameworks/base/services/core/java/com/android/server/location/gnss/GnssNative.java
frameworks/base/services/core/java/com/android/server/location/gnss/GnssLocationProvider.java
frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java

# JNI 桥接
frameworks/base/services/core/jni/com_android_server_location_gnss_GnssNative.cpp
```

### 设备端路径

```
# GNSS HAL 服务 (需替换)
/vendor/lib64/hw/android.hardware.gnss@2.1-impl.so
/vendor/bin/hw/android.hardware.gnss@2.1-service

# VINTF 清单 (HAL 声明)
/vendor/etc/vintf/manifest.xml

# GPS 配置 (已替换)
/vendor/etc/gps.conf

# 配置文件 (app 写入, daemon 读取)
/data/local/tmp/locationspoofer_config.json

# 状态文件 (daemon 写入, LSPosed 读取)
/data/local/tmp/gnss_spoof_state.json

# 字符设备 (daemon 创建, HAL 读取)
/dev/gnss_spoof

# 模块目录
/data/adb/modules/location_spoof_master/
```
