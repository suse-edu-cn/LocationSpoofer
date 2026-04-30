package com.suseoaa.locationspoofer.data.repository

import android.content.Context
import android.content.Intent
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.service.SpoofingService
import com.suseoaa.locationspoofer.utils.ConfigManager
import com.suseoaa.locationspoofer.utils.LSPosedManager
import com.suseoaa.locationspoofer.utils.RootManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager
) {
    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess()

    fun isModuleActive(): Boolean = lsposedManager.isModuleActive()

    suspend fun startSpoofing(
        context: Context,
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean
    ) {
        SpooferProvider.isActive = true
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode

        configManager.saveConfig(lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode)
        rootManager.grantMockLocation()

        context.startForegroundService(
            Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_START
                putExtra(SpoofingService.EXTRA_LAT, lat)
                putExtra(SpoofingService.EXTRA_LNG, lng)
            }
        )
    }

    suspend fun stopSpoofing(context: Context) {
        SpooferProvider.isActive = false
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        configManager.saveConfig(0.0, 0.0, false)
        context.startService(Intent(context, SpoofingService::class.java).apply {
            action = SpoofingService.ACTION_STOP
        })
        rootManager.revokeMockLocation()
    }

    suspend fun updateConfig(
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean
    ) {
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        configManager.saveConfig(lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode)
    }

    fun updateWifiJson(wifiJson: String) {
        SpooferProvider.wifiJson = wifiJson
    }

    /**
     * 启用全局定位接管：
     * 1. 置位 SpooferProvider.isGlobalMode（供 SpoofingService 读取）
     * 2. 将 is_global_mode=true 写入 config 文件（供 Xposed 钩子在应用加载时读取）
     * 3. 执行一系列 Root 系统参数优化
     */
    suspend fun enableGlobalMode(): Boolean {
        SpooferProvider.isGlobalMode = true
        // 写入配置文件使 Xposed 感知（configManager 会自动从 SpooferProvider 读取 isGlobalMode）
        configManager.saveConfig(
            SpooferProvider.latitude, SpooferProvider.longitude,
            SpooferProvider.isActive, SpooferProvider.simMode, SpooferProvider.simBearing,
            SpooferProvider.startTimestamp, emptyList(), SpooferProvider.isRouteMode
        )
        return rootManager.enableGlobalMode()
    }

    /**
     * 关闭全局定位接管，还原系统设置。
     */
    suspend fun disableGlobalMode(): Boolean {
        SpooferProvider.isGlobalMode = false
        configManager.saveConfig(
            SpooferProvider.latitude, SpooferProvider.longitude,
            SpooferProvider.isActive, SpooferProvider.simMode, SpooferProvider.simBearing,
            SpooferProvider.startTimestamp, emptyList(), SpooferProvider.isRouteMode
        )
        return rootManager.disableGlobalMode()
    }

    /** 强制重启所有第三方应用，使全局 Xposed 钩子立即生效 */
    suspend fun killAllUserApps(): Boolean = rootManager.killAllUserApps()

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        return arr.toString()
    }
}
