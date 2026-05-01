package com.suseoaa.locationspoofer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class SpooferProvider : ContentProvider() {

    companion object {
        @Volatile var isActive = false
        @Volatile var latitude = 0.0
        @Volatile var longitude = 0.0
        @Volatile var wifiJson = "[]"
        @Volatile var simMode = "STILL"
        @Volatile var simBearing = 0f
        @Volatile var startTimestamp = 0L
        @Volatile var routeJson = "[]"
        @Volatile var isRouteMode = false
        /** 全局定位接管开关（true 时 Xposed 钩子覆盖所有应用） */
        @Volatile var isGlobalMode = false
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val path = uri.lastPathSegment ?: "config"
        return if (path == "telephony") {
            buildTelephonyCursor()
        } else {
            syncConfigFromFileIfNeeded()
            buildConfigCursor()
        }
    }

    private var lastFileSyncTime: Long = 0

    private fun syncConfigFromFileIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastFileSyncTime < 1000) return
        lastFileSyncTime = now

        val file = File("/data/local/tmp/locationspoofer_config.json")
        if (!file.exists() || !file.canRead()) return

        try {
            val json = JSONObject(file.readText())
            isActive = json.optBoolean("active", isActive)
            latitude = json.optDouble("lat", latitude)
            longitude = json.optDouble("lng", longitude)
            simMode = json.optString("sim_mode", simMode)
            simBearing = json.optDouble("sim_bearing", simBearing.toDouble()).toFloat()
            startTimestamp = json.optLong("start_timestamp", startTimestamp)
            isRouteMode = json.optBoolean("is_route_mode", isRouteMode)
            isGlobalMode = json.optBoolean("is_global_mode", isGlobalMode)

            val routePoints = json.optJSONArray("route_points")
            if (routePoints != null) {
                routeJson = routePoints.toString()
            }

            val wifiValue = json.opt("wifi_json")
            wifiJson = when (wifiValue) {
                is JSONArray -> wifiValue.toString()
                is String -> wifiValue
                else -> wifiJson
            }
        } catch (e: Throwable) {
        }
    }

    private fun buildConfigCursor(): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "active", "lat", "lng", "wifi_json",
                "sim_mode", "sim_bearing", "start_timestamp",
                "route_json", "is_route_mode"
            )
        )
        cursor.addRow(
            arrayOf(
                if (isActive) 1 else 0,
                latitude,
                longitude,
                wifiJson,
                simMode,
                simBearing,
                startTimestamp,
                routeJson,
                if (isRouteMode) 1 else 0
            )
        )
        return cursor
    }

    private fun buildTelephonyCursor(): Cursor {
        val cursor = MatrixCursor(arrayOf("network_type", "mcc", "mnc"))
        val ctx = context

        var networkType = TelephonyManager.NETWORK_TYPE_LTE
        var mcc = 460
        var mnc = 0

        if (ctx != null) {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            try {
                networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tm?.dataNetworkType ?: networkType
                } else {
                    @Suppress("DEPRECATION")
                    tm?.networkType ?: networkType
                }
            } catch (e: Throwable) {
            }

            try {
                val operator = tm?.networkOperator?.takeIf { it.length >= 5 }
                    ?: tm?.simOperator?.takeIf { it.length >= 5 }
                if (operator != null) {
                    val parsedMcc = operator.substring(0, 3).toIntOrNull()
                    val parsedMnc = operator.substring(3).toIntOrNull()
                    if (parsedMcc != null && parsedMnc != null) {
                        mcc = parsedMcc
                        mnc = parsedMnc
                    }
                }
            } catch (e: Throwable) {
            }
        }

        cursor.addRow(arrayOf(networkType, mcc, mnc))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
