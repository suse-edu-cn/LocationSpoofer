            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader, "getLastKnownLocation", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        var loc = param.result as? android.location.Location
                        if (loc == null) {
                            loc = android.location.Location(android.location.LocationManager.GPS_PROVIDER)
                            param.result = loc
                        }
                        val wgsLat = config.optDouble("wgs84_lat", loc.latitude)
                        val wgsLng = config.optDouble("wgs84_lng", loc.longitude)
                        val jittered = getJitteredLocation(wgsLat, wgsLng)
                        loc.latitude = jittered.first
                        loc.longitude = jittered.second
                        loc.accuracy = 20f
                        loc.time = System.currentTimeMillis()
                        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    }
                }
            })
