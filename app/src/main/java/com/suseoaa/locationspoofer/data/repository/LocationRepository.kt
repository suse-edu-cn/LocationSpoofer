package com.suseoaa.locationspoofer.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.utils.ConfigManager
import com.suseoaa.locationspoofer.utils.LSPosedManager
import com.suseoaa.locationspoofer.utils.RootManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "LocationRepository"
    }

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
        isRouteMode: Boolean,
        wifiJson: String = "[]",
        cellJson: String = "[]"
    ) {
        Log.d(TAG, "startSpoofing: lat=$lat lng=$lng simMode=$simMode isRouteMode=$isRouteMode points=${routePoints.size}")
        SpooferProvider.isActive = true
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.wifiJson = wifiJson
        SpooferProvider.cellJson = cellJson
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode

        Log.d(TAG, "startSpoofing: saving config...")
        configManager.saveConfig(context, lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode, wifiJson, cellJson)
        
        // No longer starting SpoofingService or disabling GPS hardware.
        // The gnss_spoof_daemon handles NMEA generation and the custom GNSS HAL intercepts it.
        Log.d(TAG, "startSpoofing: DONE")
    }

    suspend fun stopSpoofing(context: Context) {
        Log.d(TAG, "stopSpoofing: resetting all state...")
        SpooferProvider.isActive = false
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.cellJson = "[]"
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        configManager.saveConfig(context, 0.0, 0.0, false)
        
        // No longer interacting with SpoofingService.
        Log.d(TAG, "stopSpoofing: DONE")
    }

    suspend fun updateConfig(
        lat: Double, lng: Double, simMode: String, simBearing: Float,
        startTime: Long, routePoints: List<RoutePoint>, isRouteMode: Boolean
    ) {
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        configManager.saveConfig(context, lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode, SpooferProvider.wifiJson, SpooferProvider.cellJson)
    }

    fun updateWifiJson(wifiJson: String) { SpooferProvider.wifiJson = wifiJson }
    fun updateCellJson(cellJson: String) { SpooferProvider.cellJson = cellJson }

    suspend fun saveCurrentConfigToFile() {
        if (!SpooferProvider.isActive) return
        try {
            configManager.saveConfig(
                context, SpooferProvider.latitude, SpooferProvider.longitude, true,
                SpooferProvider.simMode, SpooferProvider.simBearing, SpooferProvider.startTimestamp,
                emptyList(), SpooferProvider.isRouteMode, SpooferProvider.wifiJson, SpooferProvider.cellJson
            )
        } catch (e: Exception) { Log.e(TAG, "saveCurrentConfigToFile FAILED: ${e.message}") }
    }

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p -> arr.put(JSONObject().apply { put("lat", p.lat); put("lng", p.lng) }) }
        return arr.toString()
    }
}
