package com.suseoaa.locationspoofer.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationHooker : IXposedHookLoadPackage {

    companion object {
        // 需要注入的目标应用包名（含前缀匹配，覆盖所有子进程如 :appbrand0, :tools 等）
        val TARGET_PACKAGES = setOf(
            "com.tencent.mm",           // 微信（含所有 :appbrand 小程序子进程）
            "com.chaoxing.mobile",      // 超星学习通
            "cn.chaoxing.lemon",        // 学习通备用包名
            "com.alibaba.android.rimet",// 钉钉
            "com.sankuai.meituan",      // 美团
            "com.baidu.BaiduMap",       // 百度地图
            "com.autonavi.minimap",     // 高德地图
            "com.tencent.map",          // 腾讯地图
            "com.android.systemui",     // 系统UI（覆盖系统级定位弹窗）
            "com.google.android.gms",   // Google Play 服务（覆盖 Fused Location Provider）
        )

        // 系统进程同样需要覆盖（android进程持有LocationManagerService）
        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName

        // 宿主App自报平安
        if (pkg == "com.suseoaa.locationspoofer") {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.suseoaa.locationspoofer.utils.LSPosedManager",
                    lpparam.classLoader,
                    "isModuleActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
            return // 宿主App不需要注入定位Hook
        }

        // 系统进程：Hook Location API + 系统级定位拦截
        if (SYSTEM_PACKAGES.contains(pkg)) {
            hookLocationAPIs(lpparam.classLoader)
            // android 进程 = system_server，需要 hook reportLocation() 从源头拦截真实GPS数据
            if (pkg == "android") {
                hookSystemLocationServices(lpparam.classLoader)
            }
            return
        }

        // 精确包名匹配 + 子进程前缀匹配（如 com.tencent.mm:appbrand0）
        val isTarget = TARGET_PACKAGES.any { target ->
            pkg == target || pkg.startsWith("$target:")
        }

        if (!isTarget) return

        XposedBridge.log("[LocationSpoofer] Hooking package: $pkg")

        hookLocationAPIs(lpparam.classLoader)
        hookNetworkAndCellAPIs(lpparam.classLoader)
        hookBluetoothLE(lpparam.classLoader)
        hookGnssStatus(lpparam.classLoader)
        hookLocationExtras(lpparam.classLoader)
        hookGnssMeasurement(lpparam.classLoader)
    }

    private var startTimestamp = System.currentTimeMillis()

    // ── GCJ-02 → WGS-84 转换（Xposed模块运行在目标App进程，必须自带转换代码）──
    private val GCJ_A = 6378245.0
    private val GCJ_EE = 0.00669342162296594

    private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (gcjLng < 72.004 || gcjLng > 137.8347 || gcjLat < 0.8293 || gcjLat > 55.8271)
            return Pair(gcjLat, gcjLng)
        val dLat = gcjTransformLat(gcjLng - 105.0, gcjLat - 35.0)
        val dLng = gcjTransformLng(gcjLng - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val mLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        val mLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(gcjLat - mLat, gcjLng - mLng)
    }

    private fun gcjTransformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun gcjTransformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun getJitteredLocation(baseLat: Double, baseLng: Double): Pair<Double, Double> {
        val elapsed = System.currentTimeMillis() - startTimestamp
        val driftLat = sin(elapsed / 10000.0) * 0.000015
        val driftLng = cos(elapsed / 12000.0) * 0.000015
        return Pair(baseLat + driftLat, baseLng + driftLng)
    }

    private fun getJitteredAccuracy(): Float {
        val elapsed = System.currentTimeMillis() - startTimestamp
        return (20.0 + 10.0 * kotlin.math.sin(elapsed / 5000.0)).toFloat()
    }

    private fun hookLocationAPIs(classLoader: ClassLoader) {
        try {
            // android.location.Location 标准接口：返回 WGS-84（GPS坐标系）
            // readConfig() 已预计算 wgs84_lat/wgs84_lng，直接读取即可
            val getLatHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val wgsLat = config.optDouble("wgs84_lat", param.result as Double)
                        val wgsLng = config.optDouble("wgs84_lng", 0.0)
                        param.result = getJitteredLocation(wgsLat, wgsLng).first
                    }
                }
            }

            val getLngHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val wgsLat = config.optDouble("wgs84_lat", 0.0)
                        val wgsLng = config.optDouble("wgs84_lng", param.result as Double)
                        param.result = getJitteredLocation(wgsLat, wgsLng).second
                    }
                }
            }

            val getAccHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = getJitteredAccuracy()
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                getLatHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                getLngHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getAccuracy",
                getAccHook
            )

            // ★ 核心反检测：抹除 isFromMockProvider 标志位（strategy:100 的根本来源）
            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = false
                    }
                }
            }
            // Android 6~11: isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                antiMockHook
            )
            // Android 12+: isMock()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location",
                    classLoader,
                    "isMock",
                    antiMockHook
                )
            } catch (e: Throwable) { /* API < 31 没有此方法 */
            }

            // ★ Android 13 专项：直接对 Location 对象的 mMock / mIsFromMockProvider 字段写 false
            // (Android 12+ 字段名改为 mMock，Android 6-11 为 mIsFromMockProvider)
            val fieldCleanHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val loc = param.thisObject ?: return
                        try {
                            XposedHelpers.setBooleanField(loc, "mMock", false)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false)
                        } catch (e: Throwable) {
                        }
                        // 清理 extras bundle 中可能残留的 mock 标记
                        try {
                            val extras =
                                XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
                            extras?.remove("mockLocation")
                            extras?.remove("isMock")
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
            // 在 getLatitude/getLongitude/getAccuracy 时同步清字段，确保在实际读值前已抹除
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                fieldCleanHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                fieldCleanHook
            )

            // ★ 拦截 Settings.Secure.getInt("mock_location") — 部分ROM通过这个判断是否开了开发者模式模拟位置
            try {
                XposedHelpers.findAndHookMethod(
                    "android.provider.Settings\$Secure",
                    classLoader,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val key = param.args[1] as? String ?: return
                                if (key == "mock_location" || key == "allow_mock_location") {
                                    param.result = 0 // 0 = 关闭模拟位置（欺骗系统认为我们没开）
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val provider = param.result as? String ?: return
                            if (provider.contains("mock", ignoreCase = true) ||
                                provider.contains("test", ignoreCase = true) ||
                                provider.contains("fake", ignoreCase = true)
                            ) {
                                param.result = android.location.LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                })

            // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者
            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot {
                            it.contains("mock", ignoreCase = true) ||
                                    it.contains("test", ignoreCase = true) ||
                                    it.contains("fake", ignoreCase = true)
                        }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                            cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getProviders",
                    Boolean::class.javaPrimitiveType!!, providerListHook
                )
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getAllProviders",
                    providerListHook
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // AMap SDK 专属 Hook —— 先检测目标App是否包含AMap SDK
            val amapLocClass = "com.amap.api.location.AMapLocation"
            val hasAMap = try {
                XposedHelpers.findClass(amapLocClass, classLoader)
                true
            } catch (e: Throwable) {
                false
            }

            if (hasAMap) {
                // AMap SDK 专属 Hook（含抖动，与原生Location保持同步）
                val amapHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            val jittered = getJitteredLocation(baseLat, baseLng)
                            when (param.method.name) {
                                "getLatitude" -> param.result = jittered.first
                                "getLongitude" -> param.result = jittered.second
                            }
                        }
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLatitude", amapHook
                    )
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLongitude", amapHook
                    )
                } catch (e: Throwable) {
                }

                // ★★★ 高德SDK深度反检测（strategy:500 的来源）
                val amapNullHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = null
                        }
                    }
                }
                val amapFalseHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = false
                        }
                    }
                }
                val amapZeroHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = 0
                        }
                    }
                }

                try {
                    // 1. getMockData() → null（直接砍掉 mockData 字段的数据来源）
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getMockData", amapNullHook
                    )
                    // 2. getMockFlag() / getMockType() → 0
                    try {
                        XposedHelpers.findAndHookMethod(
                            amapLocClass, classLoader, "getMockFlag", amapZeroHook
                        )
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.findAndHookMethod(
                            amapLocClass, classLoader, "getMockType", amapZeroHook
                        )
                    } catch (e: Throwable) {
                    }
                    // 3. isMocked() → false（AMap SDK 12.0+ 新接口）
                    try {
                        XposedHelpers.findAndHookMethod(
                            amapLocClass, classLoader, "isMocked", amapFalseHook
                        )
                    } catch (e: Throwable) {
                    }
                    // 4. getErrorCode() → 0（非0表示定位失败）
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getErrorCode", amapZeroHook
                    )
                    // 5. getLocationType() → 1（GPS类型，最可信）
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLocationType",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) param.result = 1
                            }
                        })
                    // 6. getProvider() → "gps"
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getProvider",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) param.result = "gps"
                            }
                        })
                    // 7. 直接写底层 mock 相关字段（防反射读字段绕过 getter）
                    val setFieldHook = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val obj = param.thisObject ?: return
                                try { XposedHelpers.setObjectField(obj, "mockData", null) } catch (_: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockFlag", 0) } catch (_: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "mockType", 0) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "isMocked", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(obj, "mMock", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setIntField(obj, "errorCode", 0) } catch (_: Throwable) {}
                            }
                        }
                    }
                    XposedHelpers.findAndHookMethod(
                        amapLocClass, classLoader, "getLatitude", setFieldHook
                    )
                } catch (e: Throwable) {
                    XposedBridge.log(e)
                }

                // 8. AMapLocationQualityReport 质量报告也要清零
                try {
                    val qualityClass = "com.amap.api.location.AMapLocationQualityReport"
                    try {
                        XposedHelpers.findAndHookMethod(
                            qualityClass, classLoader, "getMockInfo", amapNullHook
                        )
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.findAndHookMethod(
                            qualityClass, classLoader, "isMockLocation", amapFalseHook
                        )
                    } catch (e: Throwable) {
                    }
                } catch (e: Throwable) {
                }

                // 9. setMockEnable(true) 让高德SDK禁用自身的 mock 校验流程
                try {
                    XposedHelpers.findAndHookMethod(
                        "com.amap.api.location.AMapLocationClient", classLoader, "setMockEnable",
                        Boolean::class.javaPrimitiveType!!,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) {
                                    param.args[0] = true
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                }
            }

        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    /**
     * 在 system_server (android进程) 中 hook 定位数据上报链路，从源头拦截真实 GPS/网络定位数据。
     *
     * Android 12+ 架构：LocationManagerService 不再直接处理定位数据，
     * 而是由 LocationProviderManager.onReportLocation(LocationResult) 接收各 Provider 的定位结果。
     * 拦截此处后，系统分发给所有客户端的 Location 都是模拟后的坐标。
     */
    private fun hookSystemLocationServices(classLoader: ClassLoader) {
        try {
            // ── Android 12+：hook LocationProviderManager.onReportLocation(LocationResult) ──
            val lpmClass = try {
                XposedHelpers.findClass("com.android.server.location.provider.LocationProviderManager", classLoader)
            } catch (_: Throwable) { null }

            if (lpmClass != null) {
                val locationResultClass = XposedHelpers.findClass("android.location.LocationResult", classLoader)

                XposedHelpers.findAndHookMethod(
                    lpmClass,
                    "onReportLocation",
                    locationResultClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig() ?: return
                            if (!config.optBoolean("active", false)) return

                            val wgsLat = config.optDouble("wgs84_lat", 0.0)
                            val wgsLng = config.optDouble("wgs84_lng", 0.0)
                            if (wgsLat == 0.0 && wgsLng == 0.0) return

                            try {
                                val locationResult = param.args[0]
                                val location = XposedHelpers.callMethod(locationResult, "getLastLocation") as? android.location.Location ?: return

                                // 就地修改坐标（不替换 LocationResult，保持 provider/timing 不变）
                                location.latitude = wgsLat
                                location.longitude = wgsLng
                                location.accuracy = getJitteredAccuracy()
                                // 更新时间戳确保单调递增
                                location.time = System.currentTimeMillis()
                                try { location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() } catch (_: Throwable) {}
                                // 抹除 mock 标记
                                try { XposedHelpers.setBooleanField(location, "mMock", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                            } catch (e: Throwable) {
                                XposedBridge.log("[LocationSpoofer] onReportLocation hook error: $e")
                            }
                        }
                    }
                )
                XposedBridge.log("[LocationSpoofer] Hooked LocationProviderManager.onReportLocation() (in-place spoof)")

                // hook getLastLocation — 防止通过缓存获取到真实位置
                try {
                    XposedHelpers.findAndHookMethod(
                        lpmClass,
                        "getLastLocation",
                        android.location.LocationRequest::class.java,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val config = readConfig() ?: return
                                if (!config.optBoolean("active", false)) return

                                val location = param.result as? android.location.Location ?: return
                                val wgsLat = config.optDouble("wgs84_lat", 0.0)
                                val wgsLng = config.optDouble("wgs84_lng", 0.0)
                                if (wgsLat == 0.0 && wgsLng == 0.0) return

                                try {
                                    location.latitude = wgsLat
                                    location.longitude = wgsLng
                                    location.time = System.currentTimeMillis()
                                    try { location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() } catch (_: Throwable) {}
                                    try { XposedHelpers.setBooleanField(location, "mMock", false) } catch (_: Throwable) {}
                                } catch (_: Throwable) {}
                            }
                        }
                    )
                } catch (_: Throwable) {
                    // getLastLocation 签名因 Android 版本而异
                }

                return
            }

            // ── Android 11 及更早：hook LocationManagerService.reportLocation(Location) ──
            val lmsClass = try {
                XposedHelpers.findClass("com.android.server.location.LocationManagerService", classLoader)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findClass("com.android.server.LocationManagerService", classLoader)
                } catch (_: Throwable) {
                    XposedBridge.log("[LocationSpoofer] No location service class found, skip system hook")
                    return
                }
            }

            XposedHelpers.findAndHookMethod(
                lmsClass,
                "reportLocation",
                android.location.Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return

                        val location = param.args[0] as? android.location.Location ?: return
                        val wgsLat = config.optDouble("wgs84_lat", 0.0)
                        val wgsLng = config.optDouble("wgs84_lng", 0.0)
                        if (wgsLat == 0.0 && wgsLng == 0.0) return

                        try {
                            XposedHelpers.setDoubleField(location, "mLatitude", wgsLat)
                            XposedHelpers.setDoubleField(location, "mLongitude", wgsLng)
                            XposedHelpers.setFloatField(location, "mAccuracy", getJitteredAccuracy())
                            XposedHelpers.setObjectField(location, "mProvider", "gps")
                            // 时间戳必须单调递增
                            location.time = System.currentTimeMillis()
                            try { location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() } catch (_: Throwable) {}
                            try { XposedHelpers.setBooleanField(location, "mMock", false) } catch (_: Throwable) {}
                            try { XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] reportLocation patch error: $e")
                        }
                    }
                }
            )
            XposedBridge.log("[LocationSpoofer] Hooked LocationManagerService.reportLocation() in system_server")

        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] hookSystemLocationServices failed: $e")
        }
    }

    private fun hookNetworkAndCellAPIs(classLoader: ClassLoader) {
        // 1. 伪造 WifiInfo Getter（getBSSID / getSSID / getMacAddress）
        val wifiInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val wifiArray = config.optJSONArray("wifi_json")
                    val firstWifi =
                        if (wifiArray != null && wifiArray.length() > 0) wifiArray.getJSONObject(0) else null
                    when (param.method.name) {
                        "getBSSID", "getMacAddress" -> param.result =
                            firstWifi?.optString("bssid") ?: "ac:22:0b:f4:11:33"

                        "getSSID" -> param.result =
                            "\"${firstWifi?.optString("ssid") ?: "HOME_WIFI"}\""

                        "getNetworkId" -> param.result = 1
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getBSSID",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getMacAddress",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getSSID",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getNetworkId",
                wifiInfoHook
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 2. 伪造 Wi-Fi 扫描列表（getScanResults）
        val wifiScanHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val fakeList = java.util.ArrayList<Any>()
                    val wifiArray = config.optJSONArray("wifi_json")
                    if (wifiArray != null && wifiArray.length() > 0) {
                        try {
                            val scanResultClass =
                                XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                            for (i in 0 until wifiArray.length()) {
                                val wifi = wifiArray.getJSONObject(i)
                                val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "SSID",
                                    wifi.optString("ssid")
                                )
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "BSSID",
                                    wifi.optString("bssid")
                                )
                                XposedHelpers.setIntField(
                                    fakeScanResult,
                                    "level",
                                    (-80..-40).random()
                                )
                                XposedHelpers.setIntField(
                                    fakeScanResult,
                                    "frequency",
                                    listOf(2412, 2437, 2462, 5180, 5240).random()
                                )
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "capabilities",
                                    "[WPA2-PSK-CCMP][ESS]"
                                )
                                fakeList.add(fakeScanResult)
                            }
                        } catch (e: Throwable) { // 忽略
                        }
                    }
                    param.result = fakeList
                }
            }
        }

        // 3. 完整的 WifiManager Hook 组合
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager",
                classLoader,
                "getScanResults",
                wifiScanHook
            )

            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result =
                            3 // WIFI_STATE_ENABLED
                    }
                })

            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result =
                            true
                    }
                })

            // 4. 拦截 getConnectionInfo：返回伪造的 WifiInfo 对象（包含当地真实 BSSID）
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val wifiArray = config.optJSONArray("wifi_json")
                            if (wifiArray != null && wifiArray.length() > 0) {
                                val firstWifi = wifiArray.getJSONObject(0)
                                try {
                                    val wifiInfoClass = XposedHelpers.findClass(
                                        "android.net.wifi.WifiInfo",
                                        classLoader
                                    )
                                    val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                    try {
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mBSSID",
                                            firstWifi.optString("bssid")
                                        )
                                    } catch (e: Throwable) {
                                    }
                                    try {
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mMacAddress",
                                            firstWifi.optString("bssid")
                                        )
                                    } catch (e: Throwable) {
                                    }
                                    try {
                                        val wifiSsidClass = XposedHelpers.findClass(
                                            "android.net.wifi.WifiSsid",
                                            classLoader
                                        )
                                        val createMethod = XposedHelpers.findMethodExact(
                                            wifiSsidClass,
                                            "createFromAsciiEncoded",
                                            String::class.java
                                        )
                                        val wifiSsid =
                                            createMethod.invoke(null, firstWifi.optString("ssid"))
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mWifiSsid",
                                            wifiSsid
                                        )
                                    } catch (e: Throwable) {
                                        try {
                                            XposedHelpers.setObjectField(
                                                fakeWifiInfo,
                                                "mSSID",
                                                "\"${firstWifi.optString("ssid")}\""
                                            )
                                        } catch (e2: Throwable) {
                                        }
                                    }
                                    try {
                                        XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", 1)
                                    } catch (e: Throwable) {
                                    }
                                    param.result = fakeWifiInfo
                                } catch (e: Throwable) { // 忽略
                                }
                            }
                        }
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 5. 基站信息伪造（CellLocation / AllCellInfo / NeighbucingCellInfo）
        val cellHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) return

                val cellArray = config.optJSONArray("cell_json")

                when (param.method.name) {
                    "getCellLocation" -> {
                        // 如果没有伪造数据，返回一个空的 GsmCellLocation 阻止真实数据泄露
                        if (cellArray == null || cellArray.length() == 0) {
                            try {
                                val gsmCellLocationClass = XposedHelpers.findClass(
                                    "android.telephony.gsm.GsmCellLocation",
                                    classLoader
                                )
                                param.result = XposedHelpers.newInstance(gsmCellLocationClass)
                            } catch (_: Throwable) {}
                            return
                        }
                        try {
                            val first = cellArray.getJSONObject(0)
                            val lac = first.optInt("lac", 0)
                            val cid = first.optInt("cellid", 0)
                            val gsmCellLocationClass = XposedHelpers.findClass(
                                "android.telephony.gsm.GsmCellLocation",
                                classLoader
                            )
                            val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                            XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
                            param.result = fakeLocation
                        } catch (e: Throwable) {
                        }
                    }

                    "getAllCellInfo", "getNeighboringCellInfo" -> {
                        // 没有伪造数据时返回空列表，阻止真实基站数据泄露
                        if (cellArray == null || cellArray.length() == 0) {
                            param.result = java.util.ArrayList<Any>()
                            return
                        }
                        try {
                            param.result = buildCellInfoList(cellArray, classLoader)
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getAllCellInfo",
                cellHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getCellLocation",
                cellHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getNeighboringCellInfo",
                cellHook
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 6. 拦截 PhoneStateListener.listen() — 阻止系统向 App 推送真实基站变化
        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "listen",
                "android.telephony.PhoneStateListener",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return

                        // 按位清除 LISTEN_CELL_INFO(0x400) 和 LISTEN_CELL_LOCATION(0x10)
                        val events = param.args[1] as Int
                        val mask = (0x400 or 0x10).inv()
                        param.args[1] = events and mask
                    }
                }
            )
            XposedBridge.log("[LocationSpoofer] Hooked TelephonyManager.listen() (PhoneStateListener)")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] PhoneStateListener hook failed: $e")
        }

        // 7. 拦截 TelephonyCallback（Android 12+）— 阻止新 API 推送基站变化
        try {
            val callbackClass = XposedHelpers.findClass("android.telephony.TelephonyCallback", classLoader)
            val cellInfoCallbackClass = XposedHelpers.findClass("android.telephony.TelephonyCallback\$CellInfoListener", classLoader)
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "registerTelephonyCallback",
                "java.util.concurrent.Executor",
                callbackClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return

                        // 如果 callback 是 CellInfoListener，替换为空实现
                        val callback = param.args[1]
                        if (cellInfoCallbackClass.isInstance(callback)) {
                            try {
                                val emptyCallback = XposedHelpers.newInstance(cellInfoCallbackClass)
                                param.args[1] = emptyCallback
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
            XposedBridge.log("[LocationSpoofer] Hooked TelephonyCallback registration")
        } catch (e: Throwable) {
            // Android 11 及更早没有 TelephonyCallback
        }

        // 8. 拦截 WifiManager.startScan() — 阻止真实 Wi-Fi 扫描
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager",
                classLoader,
                "startScan",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig() ?: return
                        if (!config.optBoolean("active", false)) return
                        // 不阻止扫描本身（会抛异常），但 getScanResults hook 已经返回假数据
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * 从 cell_json 数组构建 CellInfo 对象列表。
     * 通过反射构造 CellIdentityGsm/Lte + CellSignalStrengthGsm/Lte，
     * 再组装为 CellInfoGsm/CellInfoLte 实例。
     */
    private fun buildCellInfoList(cellArray: org.json.JSONArray, classLoader: ClassLoader): java.util.ArrayList<Any> {
        val list = java.util.ArrayList<Any>()
        val count = minOf(cellArray.length(), 10)
        for (i in 0 until count) {
            try {
                val cell = cellArray.getJSONObject(i)
                val radio = cell.optString("radio", "LTE")
                val mcc = cell.optInt("mcc", 460)
                val mnc = cell.optInt("mnc", 0)
                val lac = cell.optInt("lac", 0)
                val cid = cell.optInt("cellid", 0)
                val signal = cell.optInt("signal", -90)
                val tac = cell.optInt("tac", 0)
                val pci = cell.optInt("pci", 0)

                if (radio == "LTE" || radio == "NR") {
                    // 构造 CellInfoLte
                    val cellIdentityLteClass = XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
                    val cellSignalLteClass = XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
                    val cellInfoLteClass = XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)

                    val identity = XposedHelpers.newInstance(cellIdentityLteClass, mcc, mnc, cid, pci, tac)
                    val signalStrength = XposedHelpers.newInstance(cellSignalLteClass)
                    XposedHelpers.setIntField(signalStrength, "mRsrp", signal)
                    XposedHelpers.setIntField(signalStrength, "mRsrq", -10)
                    XposedHelpers.setIntField(signalStrength, "mRssnr", 200)

                    val cellInfo = XposedHelpers.newInstance(cellInfoLteClass)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", identity)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", signalStrength)
                    XposedHelpers.setIntField(cellInfo, "mRegistered", 1) // REGISTERED
                    list.add(cellInfo)
                } else {
                    // 构造 CellInfoGsm
                    val cellIdentityGsmClass = XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
                    val cellSignalGsmClass = XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", classLoader)
                    val cellInfoGsmClass = XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader)

                    val identity = XposedHelpers.newInstance(cellIdentityGsmClass, mcc, mnc, lac, cid)
                    val signalStrength = XposedHelpers.newInstance(cellSignalGsmClass)
                    XposedHelpers.setIntField(signalStrength, "mRssi", signal)

                    val cellInfo = XposedHelpers.newInstance(cellInfoGsmClass)
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentityGsm", identity)
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthGsm", signalStrength)
                    XposedHelpers.setIntField(cellInfo, "mRegistered", 1)
                    list.add(cellInfo)
                }
            } catch (_: Throwable) {
            }
        }
        return list
    }

    /**
     * 拦截蓝牙 BLE 扫描结果，防止通过附近 BLE 信标定位。
     * 当模拟激活时，返回空列表，屏蔽所有 iBeacon / Eddystone 信标探测。
     */
    private fun hookBluetoothLE(classLoader: ClassLoader) {
        val bleEmptyResultHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    param.result = java.util.ArrayList<Any>()
                }
            }
        }

        try {
            // Android 5.0+ BLE Scanner
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.le.BluetoothLeScanner",
                classLoader,
                "startScan",
                java.util.List::class.java,
                android.bluetooth.le.ScanSettings::class.java,
                android.bluetooth.le.ScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            // 替换 callback 为无操作版本，阻止真实扫描结果传递
                            param.result = null // startScan 返回 void，直接短路执行
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 同时Hook老接口（Android 4.x BluetoothAdapter.startLeScan）
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter",
                classLoader,
                "startLeScan",
                android.bluetooth.BluetoothAdapter.LeScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = false // 假装开启失败，不返回任何扫描结果
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    private var lastConfig: JSONObject? = null
    private var lastReadTime: Long = 0

    /**
     * 读取模拟配置。
     *
     * 关键设计：配置在内存中持久缓存。一旦 active=true 的配置被读取成功，
     * 即使宿主 App 被系统杀死（ContentProvider 不可用），hook 仍继续使用
     * 缓存的配置替换真实定位数据，直到明确收到 active=false 信号。
     *
     * 这就是 FakeLocation 等成熟方案"不需要开飞行模式"的核心原理：
     * Xposed 模块在 system_server 中持久运行，不依赖宿主 App 存活。
     */
    private fun readConfig(): JSONObject? {
        val currentTime = System.currentTimeMillis()

        // 高频调用节流
        val cached = lastConfig
        if (cached != null) {
            if (cached.optBoolean("active", false)) {
                // 模拟激活中，每 2 秒重新读取一次（支持路线模拟坐标更新）
                if (currentTime - lastReadTime < 2000) return cached
            } else {
                // 已停止模拟，800ms 节流
                if (currentTime - lastReadTime < 800) return cached
            }
        }

        // 尝试通过 ContentProvider 读取配置
        // 优先使用当前 Application 的 ContentResolver，否则回退到系统 ContentResolver
        val app = android.app.AndroidAppHelper.currentApplication()
        val contentResolver = app?.contentResolver ?: try {
            // system_server 中 AndroidAppHelper.currentApplication() 返回 null，
            // 使用 ActivityThread.currentActivityThread().getSystemContext() 获取系统上下文
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            val systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext") as? android.content.Context
            systemContext?.contentResolver
        } catch (_: Throwable) { null }

        if (contentResolver != null) {
            try {
                val uri =
                    android.net.Uri.parse("content://com.suseoaa.locationspoofer.provider/config")
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
                    val lat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"))
                    val lng = cursor.getDouble(cursor.getColumnIndexOrThrow("lng"))
                    val wifiJson = cursor.getString(cursor.getColumnIndexOrThrow("wifi_json"))

                    val cellJsonIdx = cursor.getColumnIndex("cell_json")
                    val cellJson = if (cellJsonIdx != -1) cursor.getString(cellJsonIdx) else "[]"

                    val simModeIdx = cursor.getColumnIndex("sim_mode")
                    val simMode = if (simModeIdx != -1) cursor.getString(simModeIdx) else "STILL"

                    val simBearingIdx = cursor.getColumnIndex("sim_bearing")
                    val simBearing = if (simBearingIdx != -1) cursor.getFloat(simBearingIdx) else 0f

                    val startTimestampIdx = cursor.getColumnIndex("start_timestamp")
                    val startTimestamp = if (startTimestampIdx != -1) cursor.getLong(startTimestampIdx) else System.currentTimeMillis()

                    cursor.close()

                    val config = JSONObject()
                    config.put("active", active)
                    config.put("lat", lat)           // GCJ-02
                    config.put("lng", lng)           // GCJ-02
                    // 预计算 WGS-84，避免每次 hook 调用都重复转换
                    val wgs84 = gcj02ToWgs84(lat, lng)
                    config.put("wgs84_lat", wgs84.first)
                    config.put("wgs84_lng", wgs84.second)
                    config.put("wifi_json", org.json.JSONArray(wifiJson))
                    config.put("cell_json", org.json.JSONArray(cellJson))
                    config.put("sim_mode", simMode)
                    config.put("sim_bearing", simBearing.toDouble())
                    config.put("start_timestamp", startTimestamp)

                    lastConfig = config
                    lastReadTime = currentTime
                    return config
                }
            } catch (e: Throwable) { // 忽略查询错误
            }
        }

        // 回退到本地文件（优先 /data/local/tmp/，其次 app 内部存储）
        val fallbackPaths = listOf(
            "/data/local/tmp/locationspoofer_config.json",
            "/data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json"
        )
        for (path in fallbackPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val content = file.readText()
                    val config = JSONObject(content)
                    if (!config.has("wifi_json")) config.put("wifi_json", org.json.JSONArray())
                    if (!config.has("cell_json")) config.put("cell_json", org.json.JSONArray())
                    val lat = config.optDouble("lat", 0.0)
                    val lng = config.optDouble("lng", 0.0)
                    val wgs84 = gcj02ToWgs84(lat, lng)
                    config.put("wgs84_lat", wgs84.first)
                    config.put("wgs84_lng", wgs84.second)
                    lastConfig = config
                    lastReadTime = currentTime
                    return config
                }
            } catch (_: Exception) {}
        }

        // 所有数据源都不可用（宿主 App 已死、无 root 写文件）
        // 如果缓存的配置是 active=true，继续使用缓存（核心：不因宿主 App 死亡而停止模拟）
        val lastActiveConfig = lastConfig
        if (lastActiveConfig != null && lastActiveConfig.optBoolean("active", false)) {
            return lastActiveConfig
        }
        return null
    }

    // --- GNSS HAL 接管增强：从 native daemon 读取真实感卫星状态 ---
    private var lastGnssState: JSONObject? = null
    private var lastGnssReadTime = 0L

    private fun readGnssState(): JSONObject? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGnssReadTime < 1000 && lastGnssState != null) {
            return lastGnssState
        }
        try {
            val file = File("/data/local/tmp/gnss_spoof_state.json")
            if (file.exists() && file.canRead()) {
                val state = JSONObject(file.readText())
                lastGnssState = state
                lastGnssReadTime = currentTime
                return state
            }
        } catch (_: Exception) {}
        return null
    }

    private fun hookGnssStatus(classLoader: ClassLoader) {
        val gnssStatusClass = try {
            XposedHelpers.findClass("android.location.GnssStatus", classLoader)
        } catch (e: Throwable) { return }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val state = readGnssState()
                if (state != null && state.optBoolean("active", false)) {
                    val sats = state.optJSONArray("satellites") ?: return
                    val index = param.args.firstOrNull() as? Int ?: return
                    if (index >= sats.length()) return
                    val sat = sats.getJSONObject(index)

                    when (param.method.name) {
                        "getSatelliteCount" -> {
                            param.result = state.optInt("satellites_count", 0)
                        }
                        "getConstellationType" -> param.result = sat.optInt("constellation", 1)
                        "getSvid" -> param.result = sat.optInt("prn", 1)
                        "getElevationDegrees" -> param.result = sat.optDouble("elevation", 0.0).toFloat()
                        "getAzimuthDegrees" -> param.result = sat.optDouble("azimuth", 0.0).toFloat()
                        "getCn0DbHz" -> param.result = sat.optDouble("snr", 0.0).toFloat()
                        "hasEphemerisData" -> param.result = true
                        "hasAlmanacData" -> param.result = true
                        "usedInFix" -> param.result = sat.optBoolean("used", false)
                        "hasCarrierFrequencyHz" -> param.result = true
                        "getCarrierFrequencyHz" -> {
                            // Mock standard L1 frequency (1575.42 MHz)
                            param.result = 1.57542e9.toFloat()
                        }
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getSatelliteCount", hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getConstellationType", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getSvid", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getElevationDegrees", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getAzimuthDegrees", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getCn0DbHz", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasEphemerisData", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasAlmanacData", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "usedInFix", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "hasCarrierFrequencyHz", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(gnssStatusClass, "getCarrierFrequencyHz", Int::class.javaPrimitiveType!!, hook)
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    private fun hookLocationExtras(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val state = readGnssState()
                if (state != null && state.optBoolean("active", false)) {
                    val extras = param.result as? android.os.Bundle ?: android.os.Bundle()
                    extras.putInt("satellites", state.optInt("satellites_used", 10))
                    param.result = extras
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getExtras", hook)
        } catch (e: Throwable) {}
    }

    private fun hookGnssMeasurement(classLoader: ClassLoader) {
        val measurementClass = try {
            XposedHelpers.findClass("android.location.GnssMeasurement", classLoader)
        } catch (e: Throwable) { return }

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val state = readGnssState()
                if (state != null && state.optBoolean("active", false)) {
                    val sats = state.optJSONArray("satellites") ?: return
                    if (sats.length() > 0) {
                        // Just mock it based on the first sat for a stable return (real implementation would map it properly)
                        val sat = sats.getJSONObject(0)
                        when (param.method.name) {
                            "getSvid" -> param.result = sat.optInt("prn", 1)
                            "getConstellationType" -> param.result = sat.optInt("constellation", 1)
                            "getCn0DbHz" -> param.result = sat.optDouble("snr", 0.0)
                        }
                    }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(measurementClass, "getSvid", hook)
            XposedHelpers.findAndHookMethod(measurementClass, "getConstellationType", hook)
            XposedHelpers.findAndHookMethod(measurementClass, "getCn0DbHz", hook)
        } catch (e: Throwable) {}
    }
}
