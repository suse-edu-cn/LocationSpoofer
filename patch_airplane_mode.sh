#!/bin/zsh
cat << 'PATCH' > airplane.patch
--- app/src/main/java/com/suseoaa/locationspoofer/xposed/LocationHooker.kt
+++ app/src/main/java/com/suseoaa/locationspoofer/xposed/LocationHooker.kt
@@ -959,6 +959,38 @@
     }
 
     private fun hookNetworkAndCellAPIs(classLoader: ClassLoader) {
+        // 0. 核心级伪装：选择性全局飞行模式欺骗
+        // 针对高德、系统定位服务和谷歌服务，强制返回 1（飞行模式开启）
+        // 对系统底层通讯(com.android.phone)保持真实状态，保证不断网
+        try {
+            val hook = object : XC_MethodHook() {
+                override fun beforeHookedMethod(param: MethodHookParam) {
+                    val config = readConfig()
+                    if (config != null && config.optBoolean("active", false)) {
+                        val key = param.args[1] as? String
+                        if (key == "airplane_mode_on") {
+                            val callerPkg = android.app.AndroidAppHelper.currentPackageName() ?: ""
+                            // 避开系统核心通讯以防止真实断网
+                            if (callerPkg != "com.android.phone" && callerPkg != "com.android.systemui") {
+                                param.result = 1
+                            }
+                        }
+                    }
+                }
+            }
+            XposedHelpers.findAndHookMethod(
+                "android.provider.Settings\$Global", classLoader, "getInt",
+                android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, hook
+            )
+            XposedHelpers.findAndHookMethod(
+                "android.provider.Settings\$Global", classLoader, "getInt",
+                android.content.ContentResolver::class.java, String::class.java, hook
+            )
+        } catch (e: Throwable) {
+            XposedBridge.log(e)
+        }
+
         // 1. 伪造 WifiInfo Getter（getBSSID / getSSID / getMacAddress）
         val wifiInfoHook = object : XC_MethodHook() {
             override fun afterHookedMethod(param: MethodHookParam) {
PATCH
patch -p0 < airplane.patch
