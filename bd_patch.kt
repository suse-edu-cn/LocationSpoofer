    private val X_PI = Math.PI * 3000.0 / 180.0
    private fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val z = sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * sin(gcjLat * X_PI)
        val theta = Math.atan2(gcjLat, gcjLng) + 0.000003 * cos(gcjLng * X_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
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
                
                try { XposedHelpers.findAndHookMethod(bdLocationClass, "getMockGpsStrategy", bdNullHook) } catch (e: Throwable) {}
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
