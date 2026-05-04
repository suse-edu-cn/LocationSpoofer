package com.suseoaa.locationspoofer.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.io.File
import android.os.SystemClock
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationHooker : IXposedHookLoadPackage {

    companion object {
        const val MY_PACKAGE_NAME = "com.suseoaa.locationspoofer"
        const val TAG = "LocationHooker"
        
        val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.chaoxing.mobile",
            "cn.chaoxing.lemon",
            "com.alibaba.android.rimet",
            "com.sankuai.meituan",
            "com.baidu.BaiduMap",
            "com.autonavi.minimap",
            "com.tencent.map",
            "com.android.systemui",
            "com.google.android.gms",
        )

        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone", "com.android.providers.settings")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName

        if (pkg == MY_PACKAGE_NAME) {
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
            } catch (e: Throwable) {}
            return
        }

        if (SYSTEM_PACKAGES.contains(pkg)) {
            hookLocationAPIs(lpparam.classLoader)
            return
        }

        val isTarget = TARGET_PACKAGES.any { target ->
            pkg == target || pkg.startsWith("$target:")
        }

        if (!isTarget) return

        XposedBridge.log("[$TAG] Deep stealth hooking: $pkg")

        hideSelfFromPackageManager(lpparam.classLoader)
        hookAppOps(lpparam.classLoader)
        hookLocationAPIs(lpparam.classLoader)
        hookNetworkAndCellAPIs(lpparam.classLoader)
        hookBluetoothLE(lpparam.classLoader)
        hookAMapSDKResiliently(lpparam.classLoader)
        hookBaiduAndTencentSDKs(lpparam.classLoader)
    }

    private fun hideSelfFromPackageManager(classLoader: ClassLoader) {
        try {
            val pmClass = "android.app.ApplicationPackageManager"
            
            // 过滤已安装应用列表。这是最安全且有效的隐藏方式，大多数检测 App 都会遍历此列表。
            // 我们保留 getPackageInfo/getApplicationInfo 不进行 Hook，以确保系统和 Target App 
            // 能够正常解析我们的 ContentProvider（解决 Failed to find provider info 问题）。
            
            XposedHelpers.findAndHookMethod(pmClass, classLoader, "getInstalledPackages", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? MutableList<PackageInfo> ?: return
                    param.result = list.filterNot { it.packageName == MY_PACKAGE_NAME }
                }
            })
            XposedHelpers.findAndHookMethod(pmClass, classLoader, "getInstalledApplications", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? MutableList<ApplicationInfo> ?: return
                    param.result = list.filterNot { it.packageName == MY_PACKAGE_NAME }
                }
            })
            
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] hideSelfFromPackageManager error: $e")
        }
    }

    private fun hookAppOps(classLoader: ClassLoader) {
        try {
            val appOpsClass = "android.app.AppOpsManager"
            val mockLocationOp = 58 
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val op = param.args[0] as? Int ?: return
                    if (op == mockLocationOp) param.result = android.app.AppOpsManager.MODE_ALLOWED
                }
            }
            XposedHelpers.findAndHookMethod(appOpsClass, classLoader, "checkOp", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, hook)
            XposedHelpers.findAndHookMethod(appOpsClass, classLoader, "noteOp", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, hook)
            XposedHelpers.findAndHookMethod(appOpsClass, classLoader, "startOp", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, String::class.java, hook)
        } catch (e: Throwable) {}
    }

    private var startTimestamp = System.currentTimeMillis()

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
            val getLatHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val wgsLat = config.optDouble("wgs84_lat", param.result as Double)
                        param.result = getJitteredLocation(wgsLat, 0.0).first
                    }
                }
            }

            val getLngHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val wgsLng = config.optDouble("wgs84_lng", param.result as Double)
                        param.result = getJitteredLocation(0.0, wgsLng).second
                    }
                }
            }

            val getAccHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = getJitteredAccuracy()
                }
            }

            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getLatitude", getLatHook)
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getLongitude", getLngHook)
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getAccuracy", getAccHook)

            // Hook Location constructor to clean mock flags at creation time
            try {
                XposedHelpers.findAndHookConstructor("android.location.Location", classLoader,
                    android.location.Location::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val loc = param.thisObject ?: return
                                try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                                try {
                                    val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
                                    XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
                                } catch (_: Throwable) {}
                            }
                        }
                    })
            } catch (_: Throwable) {}
            try {
                XposedHelpers.findAndHookConstructor("android.location.Location", classLoader,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val loc = param.thisObject ?: return
                                try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                                try {
                                    val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
                                    XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
                                } catch (_: Throwable) {}
                            }
                        }
                    })
            } catch (_: Throwable) {}

            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getTime", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = System.currentTimeMillis()
                }
            })
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getElapsedRealtimeNanos", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = SystemClock.elapsedRealtimeNanos()
                }
            })

            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = false
                }
            }
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "isFromMockProvider", antiMockHook)
            try { XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "isMock", antiMockHook) } catch (e: Throwable) {}

            // 清理 Location extras Bundle 中的 mock 标记
            try {
                XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getExtras", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val extras = param.result as? android.os.Bundle ?: return
                            val keysToRemove = mutableListOf<String>()
                            for (key in extras.keySet()) {
                                val lowerKey = key.lowercase()
                                if (lowerKey.contains("mock") || lowerKey.contains("strategy") || lowerKey.contains("probability")) {
                                    keysToRemove.add(key)
                                }
                            }
                            for (key in keysToRemove) {
                                extras.remove(key)
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setIsFromMockProvider", Boolean::class.javaPrimitiveType!!, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.args[0] = false
                    }
                })
            } catch (e: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "set",
                    android.location.Location::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val loc = param.thisObject ?: return
                                try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
                                try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                                try {
                                    val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
                                    XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
                                } catch (_: Throwable) {}
                            }
                        }
                    })
            } catch (_: Throwable) {}

            val fieldCleanHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val loc = param.thisObject ?: return
                        try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
                        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                        try {
                            val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
                            XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
                        } catch (_: Throwable) {}
                        try {
                            val extras = XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
                            extras?.remove("mockLocation")
                            extras?.remove("isMock")
                        } catch (_: Throwable) {}
                    }
                }
            }
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getLatitude", fieldCleanHook)
            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getLongitude", fieldCleanHook)

            try {
                XposedHelpers.findAndHookMethod(
                    "android.provider.Settings\$Secure", classLoader, "getInt",
                    android.content.ContentResolver::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val key = param.args[1] as? String ?: return
                                if (key == "mock_location" || key == "allow_mock_location") param.result = 0
                            }
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    "android.provider.Settings\$Secure", classLoader, "getString",
                    android.content.ContentResolver::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val key = param.args[1] as? String ?: return
                                if (key == "mock_location_app") param.result = null
                            }
                        }
                    }
                )
            } catch (e: Throwable) {}

            XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "getProvider", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val provider = param.result as? String ?: return
                        if (provider.contains("mock", true) || provider.contains("test", true) || provider.contains("fake", true)) {
                            param.result = android.location.LocationManager.GPS_PROVIDER
                        }
                    }
                }
            })

            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot { it.contains("mock", true) || it.contains("test", true) || it.contains("fake", true) }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER)) cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "getProviders", Boolean::class.javaPrimitiveType!!, providerListHook)
                XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "getAllProviders", providerListHook)
            } catch (e: Throwable) {}

            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "getLastKnownLocation", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        var loc = param.result as? android.location.Location
                        if (loc == null) {
                            val provider = param.args[0] as? String ?: android.location.LocationManager.GPS_PROVIDER
                            loc = android.location.Location(provider)
                            param.result = loc
                        }
                        try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
                        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
                        try {
                            val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
                            XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
                        } catch (_: Throwable) {}
                        val wgsLat = config.optDouble("wgs84_lat", loc.latitude)
                        val wgsLng = config.optDouble("wgs84_lng", loc.longitude)
                        val jittered = getJitteredLocation(wgsLat, wgsLng)
                        loc.latitude = jittered.first
                        loc.longitude = jittered.second
                        loc.accuracy = getJitteredAccuracy()
                        loc.time = System.currentTimeMillis()
                        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                }
            })

            hookNmeaData(classLoader)
            hookGnssStatus(classLoader)
            hookLocationRequests(classLoader)

        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] hookLocationAPIs error: $e")
        }
    }

    private fun generateFakeNmea(lat: Double, lng: Double): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val hms = String.format("%02d%02d%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
        val dms = String.format("%02d%06.3f", kotlin.math.abs(lat).toInt(), (kotlin.math.abs(lat) % 1) * 60)
        val latNs = if (lat >= 0) "N" else "S"
        val dme = String.format("%03d%06.3f", kotlin.math.abs(lng).toInt(), (kotlin.math.abs(lng) % 1) * 60)
        val lngEw = if (lng >= 0) "E" else "W"
        val gga = "\$GPGGA,$hms,$dms,$latNs,$dme,$lngEw,1,08,1.0,50.0,M,0.0,M,,"
        val rmc = "\$GPRMC,$hms,A,$dms,$latNs,$dme,$lngEw,0.1,0.0,${String.format("%02d%02d%02d", cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.YEAR) % 100)},,A"
        val cs1 = gga.substring(1).fold(0) { acc, c -> acc xor c.code }.let { String.format("%02X", it) }
        val cs2 = rmc.substring(1).fold(0) { acc, c -> acc xor c.code }.let { String.format("%02X", it) }
        return "$gga*$cs1\r\n$rmc*$cs2\r\n"
    }

    private fun deliverFakeNmea() {
        try {
            val config = readConfig() ?: return
            if (!config.optBoolean("active", false)) return
            val lat = config.optDouble("wgs84_lat", 0.0)
            val lng = config.optDouble("wgs84_lng", 0.0)
            val jittered = getJitteredLocation(lat, lng)
            val nmea = generateFakeNmea(jittered.first, jittered.second)
            val ts = System.currentTimeMillis()
            val listener = nmeaListener ?: return
            val executor = nmeaExecutor ?: return
            executor.execute {
                try {
                    XposedHelpers.callMethod(listener, "onNmeaMessage", nmea, ts)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun scheduleFakeNmeaDelivery() {
        nmeaScheduleFuture?.cancel(false)
        if (nmeaListener != null) {
            nmeaScheduleFuture = nmeaScheduler.scheduleAtFixedRate({ deliverFakeNmea() }, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun hookNmeaData(classLoader: ClassLoader) {
        // 新版 API: Executor + OnNmeaMessageListener
        try {
            val onNmeaMessageListenerClass = XposedHelpers.findClass("android.location.OnNmeaMessageListener", classLoader)
            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "addNmeaListener",
                java.util.concurrent.Executor::class.java, onNmeaMessageListenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            nmeaExecutor = param.args[0] as? java.util.concurrent.Executor
                            nmeaListener = param.args[1]
                            scheduleFakeNmeaDelivery()
                            param.result = null
                        }
                    }
                })
        } catch (_: Throwable) {}
        // 旧版 API: GpsStatus.NmeaListener
        try {
            val nmeaListenerClass = XposedHelpers.findClass("android.location.GpsStatus\$NmeaListener", classLoader)
            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "addNmeaListener",
                nmeaListenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            nmeaListener = param.args[0]
                            nmeaExecutor = java.util.concurrent.Executor { it.run() }
                            scheduleFakeNmeaDelivery()
                            param.result = null
                        }
                    }
                })
        } catch (_: Throwable) {}
        // Hook removeNmeaListener 以清理引用
        try {
            val onNmeaMessageListenerClass = XposedHelpers.findClass("android.location.OnNmeaMessageListener", classLoader)
            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "removeNmeaListener",
                onNmeaMessageListenerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == nmeaListener) {
                            nmeaScheduleFuture?.cancel(false)
                            nmeaListener = null
                            nmeaExecutor = null
                        }
                    }
                })
        } catch (_: Throwable) {}
    }

    private fun hookGnssStatus(classLoader: ClassLoader) {
        try {
            val gnssStatusClass = "android.location.GnssStatus"
            XposedHelpers.findAndHookMethod(gnssStatusClass, classLoader, "getSatelliteCount", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val t = (System.currentTimeMillis() / 1000) % 60
                        param.result = 10 + (t % 7).toInt()
                    }
                }
            })
            XposedHelpers.findAndHookMethod(gnssStatusClass, classLoader, "getUsedInFix", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val idx = param.args[0] as Int
                        param.result = idx < 8
                    }
                }
            })
            XposedHelpers.findAndHookMethod(gnssStatusClass, classLoader, "getCn0DbHz", Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = 25f + (0..25).random().toFloat()
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    private fun cleanMockFlags(loc: android.location.Location) {
        try { XposedHelpers.setBooleanField(loc, "mMock", false) } catch (_: Throwable) {}
        try { XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false) } catch (_: Throwable) {}
        try {
            val mask = XposedHelpers.getIntField(loc, "mFieldsContainsMock")
            XposedHelpers.setIntField(loc, "mFieldsContainsMock", mask and 0xFFFFFFFE.toInt())
        } catch (_: Throwable) {}
    }

    private fun hookLocationRequests(classLoader: ClassLoader) {
        val locationManagerClass = XposedHelpers.findClass("android.location.LocationManager", classLoader)

        // hookAllMethods 拦截 requestLocationUpdates 的所有重载
        try {
            XposedBridge.hookAllMethods(locationManagerClass, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config == null || !config.optBoolean("active", false)) return
                    val wgsLat = config.optDouble("wgs84_lat", 0.0)
                    val wgsLng = config.optDouble("wgs84_lng", 0.0)
                    // 动态查找 LocationListener 参数
                    var listenerIdx = -1
                    for (i in param.args.indices) {
                        if (param.args[i] is android.location.LocationListener) { listenerIdx = i; break }
                    }
                    if (listenerIdx < 0) return
                    val origListener = param.args[listenerIdx] as android.location.LocationListener
                    val wrapped = object : android.location.LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            cleanMockFlags(loc)
                            val jittered = getJitteredLocation(wgsLat, wgsLng)
                            loc.latitude = jittered.first
                            loc.longitude = jittered.second
                            loc.accuracy = getJitteredAccuracy()
                            loc.time = System.currentTimeMillis()
                            loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            origListener.onLocationChanged(loc)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                            origListener.onStatusChanged(provider, status, extras)
                        }
                        override fun onProviderEnabled(provider: String) {
                            origListener.onProviderEnabled(provider)
                        }
                        override fun onProviderDisabled(provider: String) {
                            origListener.onProviderDisabled(provider)
                        }
                    }
                    param.args[listenerIdx] = wrapped
                }
            })
        } catch (_: Throwable) {}

        // hookAllMethods 拦截 requestSingleUpdate 的所有重载
        try {
            XposedBridge.hookAllMethods(locationManagerClass, "requestSingleUpdate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config == null || !config.optBoolean("active", false)) return
                    val wgsLat = config.optDouble("wgs84_lat", 0.0)
                    val wgsLng = config.optDouble("wgs84_lng", 0.0)
                    var listenerIdx = -1
                    for (i in param.args.indices) {
                        if (param.args[i] is android.location.LocationListener) { listenerIdx = i; break }
                    }
                    if (listenerIdx < 0) return
                    val origListener = param.args[listenerIdx] as android.location.LocationListener
                    val wrapped = object : android.location.LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            cleanMockFlags(loc)
                            val jittered = getJitteredLocation(wgsLat, wgsLng)
                            loc.latitude = jittered.first
                            loc.longitude = jittered.second
                            loc.accuracy = getJitteredAccuracy()
                            loc.time = System.currentTimeMillis()
                            loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            origListener.onLocationChanged(loc)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                            origListener.onStatusChanged(provider, status, extras)
                        }
                        override fun onProviderEnabled(provider: String) {
                            origListener.onProviderEnabled(provider)
                        }
                        override fun onProviderDisabled(provider: String) {
                            origListener.onProviderDisabled(provider)
                        }
                    }
                    param.args[listenerIdx] = wrapped
                }
            })
        } catch (_: Throwable) {}

        // getCurrentLocation (Android 11+)
        try {
            val consumerClass = XposedHelpers.findClass("java.util.function.Consumer", classLoader)
            XposedHelpers.findAndHookMethod(locationManagerClass, "getCurrentLocation",
                String::class.java, java.util.concurrent.Executor::class.java, consumerClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val wgsLat = config.optDouble("wgs84_lat", 0.0)
                            val wgsLng = config.optDouble("wgs84_lng", 0.0)
                            val jittered = getJitteredLocation(wgsLat, wgsLng)
                            val loc = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
                                latitude = jittered.first
                                longitude = jittered.second
                                accuracy = getJitteredAccuracy()
                                time = System.currentTimeMillis()
                                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            }
                            val consumer = param.args[2]
                            val executor = param.args[1] as? java.util.concurrent.Executor
                            (executor ?: java.util.concurrent.Executor { it.run() }).execute {
                                try { XposedHelpers.callMethod(consumer, "accept", loc) } catch (_: Throwable) {}
                            }
                            param.result = null
                        }
                    }
                })
        } catch (_: Throwable) {}
    }

    private fun hookAMapSDKResiliently(classLoader: ClassLoader) {
        hookAMapClasses(classLoader)
        try {
            XposedHelpers.findAndHookConstructor(
                "com.amap.api.location.AMapLocationClient", classLoader, android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hookAMapClasses(param.thisObject.javaClass.classLoader)
                    }
                }
            )
        } catch (e: Throwable) {}
    }

    private val hookedClassLoaders = mutableSetOf<ClassLoader>()

    private var nmeaListener: Any? = null
    private var nmeaExecutor: java.util.concurrent.Executor? = null
    private var nmeaScheduleFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val nmeaScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val locationHandlerThread = android.os.HandlerThread("SpoofLocHandler").apply { start() }
    private val locationHandler = android.os.Handler(locationHandlerThread.looper)

    private fun hookAMapClasses(classLoader: ClassLoader?) {
        if (classLoader == null || hookedClassLoaders.contains(classLoader)) return
        val amapLocClass = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocation", classLoader)
        if (amapLocClass == null) return
        hookedClassLoaders.add(classLoader)

        val amapNullHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) param.result = null
            }
        }
        val amapFalseHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) param.result = false
            }
        }
        val amapZeroHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) param.result = 0
            }
        }

        try {
            XposedHelpers.findAndHookMethod(amapLocClass, "getLatitude", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val baseLat = config.optDouble("lat", 0.0)
                        val baseLng = config.optDouble("lng", 0.0)
                        param.result = getJitteredLocation(baseLat, baseLng).first
                    }
                }
            })
            XposedHelpers.findAndHookMethod(amapLocClass, "getLongitude", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val baseLat = config.optDouble("lat", 0.0)
                        val baseLng = config.optDouble("lng", 0.0)
                        param.result = getJitteredLocation(baseLat, baseLng).second
                    }
                }
            })
            try {
                val toStrHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val result = param.result as? String ?: return
                            if (result.contains("mock", true) || result.contains("strategy", true)) {
                                var cleanJson = result.replace(Regex("\"mockData\"\\s*:\\s*\\{[^}]*\\},?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockType\"\\s*:\\s*\\d+,?"), "")
                                cleanJson = cleanJson.replace(Regex("\"isMock\"\\s*:\\s*(true|false),?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockGpsStrategy\"\\s*:\\s*\\d+,?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockGpsProbability\"\\s*:\\s*\\d+(?:\\.\\d+)?,?"), "")
                                cleanJson = cleanJson.replace(Regex(",\\s*\\}"), "}")
                                param.result = cleanJson
                            }
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(amapLocClass, "toStr", toStrHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "toStr", Int::class.javaPrimitiveType!!, toStrHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "toJson", Int::class.javaPrimitiveType!!, toStrHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "toString", toStrHook)
            } catch (e: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(amapLocClass, "getCoordType", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = "GCJ02"
                    }
                })
            } catch (e: Throwable) {}

            val amapAddressHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = null
                }
            }
            try {
                XposedHelpers.findAndHookMethod(amapLocClass, "getAdCode", amapAddressHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "getCity", amapAddressHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "getDistrict", amapAddressHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "getCityCode", amapAddressHook)
                XposedHelpers.findAndHookMethod(amapLocClass, "getAddress", amapAddressHook)
            } catch (e: Throwable) {}

            XposedHelpers.findAndHookMethod(amapLocClass, "getMockData", amapNullHook)
            try { XposedHelpers.findAndHookMethod(amapLocClass, "getMockFlag", amapZeroHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(amapLocClass, "getMockType", amapZeroHook) } catch (e: Throwable) {}
            try { XposedHelpers.findAndHookMethod(amapLocClass, "isMocked", amapFalseHook) } catch (e: Throwable) {}
            XposedHelpers.findAndHookMethod(amapLocClass, "getErrorCode", amapZeroHook)
            XposedHelpers.findAndHookMethod(amapLocClass, "getLocationType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = 1
                }
            })
            XposedHelpers.findAndHookMethod(amapLocClass, "getProvider", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = "gps"
                }
            })

            val setFieldHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val obj = param.thisObject ?: return
                        try { XposedHelpers.setObjectField(obj, "mockData", null) } catch (e: Throwable) {}
                        try { XposedHelpers.setIntField(obj, "mockFlag", 0) } catch (e: Throwable) {}
                        try { XposedHelpers.setIntField(obj, "mockType", 0) } catch (e: Throwable) {}
                        try { XposedHelpers.setBooleanField(obj, "isMocked", false) } catch (e: Throwable) {}
                        try { XposedHelpers.setBooleanField(obj, "mMock", false) } catch (e: Throwable) {}
                        try { XposedHelpers.setIntField(obj, "errorCode", 0) } catch (e: Throwable) {}
                    }
                }
            }
            XposedHelpers.findAndHookMethod(amapLocClass, "getLatitude", setFieldHook)
        } catch (e: Throwable) {}

        try {
            val qualityClass = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocationQualityReport", classLoader)
            if (qualityClass != null) {
                try { XposedHelpers.findAndHookMethod(qualityClass, "getMockInfo", amapNullHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(qualityClass, "isMockLocation", amapFalseHook) } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "com.amap.api.location.AMapLocationClient", classLoader, "setMockEnable",
                Boolean::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.args[0] = true
                    }
                }
            )
        } catch (e: Throwable) {}
    }

    private fun hookNetworkAndCellAPIs(classLoader: ClassLoader) {
        val cellHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val cellArray = config.optJSONArray("cell_json")
                    val firstCell = if (cellArray != null && cellArray.length() > 0) cellArray.getJSONObject(0) else null
                    when (param.method.name) {
                        "getCellLocation" -> {
                            try {
                                val gsmCellLocationClass = XposedHelpers.findClass("android.telephony.gsm.GsmCellLocation", classLoader)
                                val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                                val lac = firstCell?.optInt("lac") ?: 1234
                                val cid = firstCell?.optInt("cid") ?: 5678
                                XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
                                param.result = fakeLocation
                            } catch (e: Throwable) { param.result = null }
                        }
                        "getAllCellInfo" -> {
                            val fakeList = java.util.ArrayList<Any>()
                            if (cellArray != null) {
                                for (i in 0 until cellArray.length()) {
                                    val cell = cellArray.getJSONObject(i)
                                    val fakeCellInfo = createFakeCellInfo(classLoader, cell)
                                    if (fakeCellInfo != null) fakeList.add(fakeCellInfo)
                                }
                            }
                            param.result = fakeList
                        }
                        "getNeighboringCellInfo" -> param.result = java.util.ArrayList<Any>()
                    }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getAllCellInfo", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getCellLocation", cellHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNeighboringCellInfo", cellHook)
            
            val operatorHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val cellArray = config.optJSONArray("cell_json")
                        val firstCell = if (cellArray != null && cellArray.length() > 0) cellArray.getJSONObject(0) else null
                        if (firstCell != null) {
                            val mcc = firstCell.optInt("mcc")
                            val mnc = firstCell.optInt("mnc")
                            val op = String.format("%03d%02d", mcc, mnc)
                            when (param.method.name) {
                                "getNetworkOperator", "getSimOperator" -> param.result = op
                                "getNetworkOperatorName", "getSimOperatorName" -> param.result = "Carrier"
                                "getNetworkCountryIso", "getSimCountryIso" -> param.result = "cn"
                            }
                        }
                    }
                }
            }
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNetworkOperator", operatorHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getNetworkOperatorName", operatorHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getSimOperator", operatorHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getSimOperatorName", operatorHook)
            
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "getServiceState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val state = param.result ?: return
                        try { XposedHelpers.setIntField(state, "mVoiceRegState", 0) } catch (e: Throwable) {}
                        try { XposedHelpers.setIntField(state, "mDataRegState", 0) } catch (e: Throwable) {}
                    }
                }
            })

            try {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "requestCellInfoUpdate", 
                    java.util.concurrent.Executor::class.java, XposedHelpers.findClass("android.telephony.TelephonyManager.CellInfoCallback", classLoader),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val cellArray = config.optJSONArray("cell_json")
                                val fakeList = java.util.ArrayList<Any>()
                                if (cellArray != null) {
                                    for (i in 0 until cellArray.length()) {
                                        val cell = cellArray.getJSONObject(i)
                                        val fakeCellInfo = createFakeCellInfo(classLoader, cell)
                                        if (fakeCellInfo != null) fakeList.add(fakeCellInfo)
                                    }
                                }
                                val callback = param.args[1]
                                XposedHelpers.callMethod(callback, "onCellInfo", fakeList)
                                param.result = null
                            }
                        }
                    })
            } catch (e: Throwable) {}

            val telephonyHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = null
                }
            }
            try {
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "listen", 
                    XposedHelpers.findClass("android.telephony.PhoneStateListener", classLoader), Int::class.javaPrimitiveType!!, telephonyHook)
                XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", classLoader, "registerTelephonyCallback", 
                    java.util.concurrent.Executor::class.java, XposedHelpers.findClass("android.telephony.TelephonyCallback", classLoader), telephonyHook)
            } catch (e: Throwable) {}

            // ★ 屏蔽 Wi-Fi 扫描结果，从系统底层切断网络定位依据
            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", classLoader, "getScanResults", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = java.util.ArrayList<Any>()
                }
            })

            // 确保 getConnectionInfo 永远不返回 null（当模拟开启时），防止应用崩溃。
            // 如果系统返回 null，我们构造一个空的 WifiInfo 对象。
            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", classLoader, "getConnectionInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        if (param.result == null) {
                            try {
                                val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                                param.result = XposedHelpers.newInstance(wifiInfoClass)
                            } catch (e: Throwable) {}
                        }
                    }
                }
            })

            // 我们继续 Hook WifiInfo 内部的方法来隐藏真实信息。
            try {
                val wifiInfoClass = XposedHelpers.findClass("android.net.wifi.WifiInfo", classLoader)
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getBSSID", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = "00:00:00:00:00:00"
                    }
                })
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getSSID", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = "<unknown ssid>"
                    }
                })
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getIpAddress", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = 0
                    }
                })
                XposedHelpers.findAndHookMethod(wifiInfoClass, "getMacAddress", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = "02:00:00:00:00:00"
                    }
                })
            } catch (e: Throwable) {}

        } catch (e: Throwable) {}
    }

    private fun createFakeCellInfo(classLoader: ClassLoader, cell: JSONObject): Any? {
        val radio = cell.optString("radio").uppercase()
        val mcc = cell.optInt("mcc")
        val mnc = cell.optInt("mnc")
        val lac = cell.optInt("lac")
        val cid = cell.optInt("cid")
        return try {
            if (radio.contains("LTE")) {
                val cellInfo = XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader))
                val cellIdentity = XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader))
                try { XposedHelpers.setIntField(cellIdentity, "mMcc", mcc) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mMnc", mnc) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mCi", cid) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mTac", lac) } catch(e: Throwable) {}
                XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity)
                try { XposedHelpers.setLongField(cellInfo, "mTimeStamp", SystemClock.elapsedRealtimeNanos()) } catch(e: Throwable) {}
                cellInfo
            } else {
                val cellInfo = XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader))
                val cellIdentity = XposedHelpers.newInstance(XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader))
                try { XposedHelpers.setIntField(cellIdentity, "mMcc", mcc) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mMnc", mnc) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mLac", lac) } catch(e: Throwable) {}
                try { XposedHelpers.setIntField(cellIdentity, "mCid", cid) } catch(e: Throwable) {}
                XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity)
                try { XposedHelpers.setLongField(cellInfo, "mTimeStamp", SystemClock.elapsedRealtimeNanos()) } catch(e: Throwable) {}
                cellInfo
            }
        } catch (e: Throwable) { null }
    }

    private fun hookBluetoothLE(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.bluetooth.le.BluetoothLeScanner", classLoader, "startScan",
                java.util.List::class.java, android.bluetooth.le.ScanSettings::class.java, android.bluetooth.le.ScanCallback::class.java,
                object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = null
                }})
        } catch (e: Throwable) {}
    }

    private var lastConfig: JSONObject? = null
    private var lastReadTime: Long = 0

    private fun getAppContext(): android.content.Context? {
        try {
            val app = android.app.AndroidAppHelper.currentApplication()
            if (app != null) return app
        } catch (_: Throwable) {}
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = XposedHelpers.callStaticMethod(atClass, "currentApplication") as? android.content.Context
            if (app != null) return app
        } catch (_: Throwable) {}
        return null
    }

    private fun readConfig(): JSONObject? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReadTime < 2000 && lastConfig != null) return lastConfig

        // 1. 尝试通过 ContentProvider 读取（最快、最实时）
        val app = getAppContext()
        if (app != null) {
            try {
                val uri = android.net.Uri.parse("content://com.suseoaa.locationspoofer.provider/config")
                val cursor = app.contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        val active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
                        val lat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"))
                        val lng = cursor.getDouble(cursor.getColumnIndexOrThrow("lng"))
                        val wifiJson = cursor.getString(cursor.getColumnIndexOrThrow("wifi_json"))
                        val cellJson = try { cursor.getString(cursor.getColumnIndexOrThrow("cell_json")) } catch(e: Throwable) { "[]" }
                        val simMode = try { cursor.getString(cursor.getColumnIndexOrThrow("sim_mode")) } catch(e: Throwable) { "STILL" }
                        val simBearing = try { cursor.getFloat(cursor.getColumnIndexOrThrow("sim_bearing")) } catch(e: Throwable) { 0f }
                        val startTs = try { cursor.getLong(cursor.getColumnIndexOrThrow("start_timestamp")) } catch(e: Throwable) { System.currentTimeMillis() }
                        cursor.close()
                        
                        val config = JSONObject().apply {
                            put("active", active)
                            put("lat", lat)
                            put("lng", lng)
                            val wgs84 = gcj02ToWgs84(lat, lng)
                            put("wgs84_lat", wgs84.first)
                            put("wgs84_lng", wgs84.second)
                            put("wifi_json", org.json.JSONArray(wifiJson))
                            put("cell_json", org.json.JSONArray(cellJson))
                            put("sim_mode", simMode)
                            put("sim_bearing", simBearing.toDouble())
                            put("start_timestamp", startTs)
                        }
                        lastConfig = config
                        lastReadTime = currentTime
                        return config
                    }
                    cursor.close()
                }
            } catch (e: Throwable) {
                // XposedBridge.log("[$TAG] Provider read error: ${e.message}")
            }
        }

        // 2. 尝试从 Settings.System 读取（全局可读，解决 Android 11+ 权限问题）
        if (app != null) {
            try {
                val content = android.provider.Settings.System.getString(app.contentResolver, "locationspoofer_config")
                if (!content.isNullOrBlank()) {
                    val config = JSONObject(content)
                    if (config.has("active")) {
                        if (!(config.optBoolean("active") && config.optDouble("lat") == 0.0)) {
                            val lat = config.optDouble("lat", 0.0)
                            val lng = config.optDouble("lng", 0.0)
                            val wgs84 = gcj02ToWgs84(lat, lng)
                            config.put("wgs84_lat", wgs84.first)
                            config.put("wgs84_lng", wgs84.second)
                            if (!config.has("start_timestamp")) {
                                config.put("start_timestamp", System.currentTimeMillis())
                            }
                            lastConfig = config
                            lastReadTime = currentTime
                            return config
                        }
                    }
                }
            } catch (e: Throwable) {
                // Ignore
            }
        }

        // 2.5. Shell 回退：当 Context 为 null 时通过 shell 命令读取 Settings.System
        if (app == null) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings get system locationspoofer_config"))
                val content = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (content.isNotBlank() && content != "null") {
                    val config = JSONObject(content)
                    if (config.has("active")) {
                        if (!(config.optBoolean("active") && config.optDouble("lat") == 0.0)) {
                            val lat = config.optDouble("lat", 0.0)
                            val lng = config.optDouble("lng", 0.0)
                            val wgs84 = gcj02ToWgs84(lat, lng)
                            config.put("wgs84_lat", wgs84.first)
                            config.put("wgs84_lng", wgs84.second)
                            if (!config.has("start_timestamp")) {
                                config.put("start_timestamp", System.currentTimeMillis())
                            }
                            lastConfig = config
                            lastReadTime = currentTime
                            return config
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. 兜底方案：尝试直接读取 /data/local/tmp 下的配置文件
        return try {
            val file = File("/data/local/tmp/locationspoofer_config.json")
            if (file.exists() && file.canRead()) {
                val content = file.readText()
                if (content.isBlank()) return null
                val config = JSONObject(content)
                if (!config.has("active")) return null
                
                // 确保经纬度存在且不为0（如果是模拟状态）
                if (config.optBoolean("active") && config.optDouble("lat") == 0.0) {
                    return null // 数据可能还不完整
                }

                // 补全 WGS84 坐标，防止回弹到默认值
                val lat = config.optDouble("lat", 0.0)
                val lng = config.optDouble("lng", 0.0)
                val wgs84 = gcj02ToWgs84(lat, lng)
                config.put("wgs84_lat", wgs84.first)
                config.put("wgs84_lng", wgs84.second)
                
                // 确保有 start_timestamp，用于抖动算法
                if (!config.has("start_timestamp")) {
                    config.put("start_timestamp", System.currentTimeMillis())
                }

                lastConfig = config
                lastReadTime = currentTime
                config
            } else {
                null
            }
        } catch (e: Exception) { 
            null 
        }
    }

    private val X_PI = Math.PI * 3000.0 / 180.0
    private fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val z = kotlin.math.sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * kotlin.math.sin(gcjLat * X_PI)
        val theta = kotlin.math.atan2(gcjLat, gcjLng) + 0.000003 * kotlin.math.cos(gcjLng * X_PI)
        val bdLng = z * kotlin.math.cos(theta) + 0.0065
        val bdLat = z * kotlin.math.sin(theta) + 0.006
        return Pair(bdLat, bdLng)
    }

    private fun hookBaiduAndTencentSDKs(classLoader: ClassLoader) {
        // Baidu Hook
        val bdLocationClass = XposedHelpers.findClassIfExists("com.baidu.location.BDLocation", classLoader)
        if (bdLocationClass != null) {
            val bdNullHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = null
                }
            }
            val bdFalseHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) param.result = false
                }
            }
            
            try {
                val toStrHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val result = param.result as? String ?: return
                            if (result.contains("mock", true) || result.contains("strategy", true)) {
                                var cleanJson = result.replace(Regex("\"mockData\"\\s*:\\s*\\{[^}]*\\},?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockType\"\\s*:\\s*\\d+,?"), "")
                                cleanJson = cleanJson.replace(Regex("\"isMock\"\\s*:\\s*(true|false),?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockGpsStrategy\"\\s*:\\s*\\d+,?"), "")
                                cleanJson = cleanJson.replace(Regex("\"mockGpsProbability\"\\s*:\\s*\\d+(?:\\.\\d+)?,?"), "")
                                cleanJson = cleanJson.replace(Regex(",\\s*\\}"), "}")
                                param.result = cleanJson
                            }
                        }
                    }
                }
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getLocationDescribe", toStrHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "toString", toStrHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "toJsonString", toStrHook) } catch (e: Throwable) {}
            } catch (e: Throwable) {}
            
            try {
                XposedHelpers.findAndHookMethod(bdLocationClass, "getLatitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            val coordType = try { XposedHelpers.callMethod(param.thisObject, "getCoorType") as? String } catch (e: Throwable) { null }
                            val jittered = getJitteredLocation(baseLat, baseLng)
                            if (coordType == "bd09ll" || coordType == "bd09") {
                                val bd09 = gcj02ToBd09(jittered.first, jittered.second)
                                param.result = bd09.first
                            } else {
                                param.result = jittered.first
                            }
                        }
                    }
                })
                XposedHelpers.findAndHookMethod(bdLocationClass, "getLongitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            val coordType = try { XposedHelpers.callMethod(param.thisObject, "getCoorType") as? String } catch (e: Throwable) { null }
                            val jittered = getJitteredLocation(baseLat, baseLng)
                            if (coordType == "bd09ll" || coordType == "bd09") {
                                val bd09 = gcj02ToBd09(jittered.first, jittered.second)
                                param.result = bd09.second
                            } else {
                                param.result = jittered.second
                            }
                        }
                    }
                })
                
                val bdAddressHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result = null
                    }
                }
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getAddrStr", bdAddressHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getCityCode", bdAddressHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getCity", bdAddressHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getDistrict", bdAddressHook) } catch (e: Throwable) {}
                
                try {
                    XposedHelpers.findAndHookMethod(bdLocationClass, "getLocType", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) param.result = 61 // GPS
                        }
                    })
                } catch (e: Throwable) {}
                
                val mockCleanHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val returnType = param.method.let { if (it is java.lang.reflect.Method) it.returnType else null }
                            if (returnType == Int::class.javaPrimitiveType || returnType == Int::class.javaObjectType) {
                                param.result = 0
                            } else if (returnType == Float::class.javaPrimitiveType || returnType == Float::class.javaObjectType) {
                                param.result = 0f
                            } else if (returnType == Double::class.javaPrimitiveType || returnType == Double::class.javaObjectType) {
                                param.result = 0.0
                            } else if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.javaObjectType) {
                                param.result = false
                            } else if (returnType == String::class.java) {
                                param.result = null
                            }
                        }
                    }
                }
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getMockGpsStrategy", mockCleanHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getMockGpsProbability", mockCleanHook) } catch (e: Throwable) {}
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "isMockGps", bdFalseHook) } catch (e: Throwable) {}
            } catch (e: Throwable) {}
        }
        
        // Tencent Hook
        val txLocationClass = XposedHelpers.findClassIfExists("com.tencent.map.geolocation.TencentLocation", classLoader)
        if (txLocationClass != null) {
            try {
                XposedHelpers.findAndHookMethod(txLocationClass, "getLatitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            param.result = getJitteredLocation(baseLat, baseLng).first
                        }
                    }
                })
                XposedHelpers.findAndHookMethod(txLocationClass, "getLongitude", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val baseLat = config.optDouble("lat", 0.0)
                            val baseLng = config.optDouble("lng", 0.0)
                            param.result = getJitteredLocation(baseLat, baseLng).second
                        }
                    }
                })
                try {
                    XposedHelpers.findAndHookMethod(txLocationClass, "getProvider", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) param.result = "gps"
                        }
                    })
                } catch (e: Throwable) {}
            } catch (e: Throwable) {}
        }
    }
}
