package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val rootManager: RootManager) {

    companion object {
        private const val CONFIG_FILE_NAME = "locationspoofer_config.json"
        private const val CONFIG_FILE_PATH = "/data/local/tmp/$CONFIG_FILE_NAME"
    }

    suspend fun saveConfig(
        context: android.content.Context,
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false,
        wifiJson: String = "[]",
        cellJson: String = "[]"
    ) = withContext(Dispatchers.IO) {
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }

        val json = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("active", active)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            put("wifi_json", wifiJson)
            put("cell_json", cellJson)
        }

        val jsonStr = json.toString()

        // 1. 写入 app 内部存储（Xposed 模块可通过 ContentProvider 读取）
        try {
            context.openFileOutput(CONFIG_FILE_NAME, android.content.Context.MODE_PRIVATE).use {
                it.write(jsonStr.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {}

        // 2. 通过 root 写入 /data/local/tmp/（system_server 和其他进程的回退路径）
        val base64 = android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        rootManager.executeCommand("echo '$base64' | base64 -d > $CONFIG_FILE_PATH && chmod 644 $CONFIG_FILE_PATH")
    }
}
