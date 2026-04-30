package com.suseoaa.locationspoofer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

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

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
