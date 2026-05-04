package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val rootManager: RootManager) {

    suspend fun saveConfig(
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false
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
            try {
                put("wifi_json", JSONArray(com.suseoaa.locationspoofer.provider.SpooferProvider.wifiJson))
                put("cell_json", JSONArray(com.suseoaa.locationspoofer.provider.SpooferProvider.cellJson))
            } catch (e: Exception) {
                put("wifi_json", JSONArray())
                put("cell_json", JSONArray())
            }
        }
        val jsonStr = json.toString()
        // 使用 sh -c 来执行多条命令，并确保使用临时文件+mv实现原子写入，防止读取时文件半截
        // 同时将配置写入 Settings.System，解决 Android 11+ 目标应用无法读取的问题
        val command = """
            TEMP_FILE="/data/local/tmp/config_temp.json"
            TARGET_FILE="/data/local/tmp/locationspoofer_config.json"
            echo '$jsonStr' > ${"$"}TEMP_FILE
            chmod 777 ${"$"}TEMP_FILE
            chcon u:object_r:shell_data_file:s0 ${"$"}TEMP_FILE
            mv ${"$"}TEMP_FILE ${"$"}TARGET_FILE
            settings put system locationspoofer_config '$jsonStr'
        """.trimIndent()

        rootManager.executeCommand("sh -c \"$command\"")
    }
}
