package com.suseoaa.locationspoofer.utils

import kotlin.math.*

object CoordinateConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** GCJ-02（高德/腾讯）→ WGS-84（GPS标准）。中国境外坐标原样返回。 */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (isOutOfChina(gcjLat, gcjLng)) return Pair(gcjLat, gcjLng)
        var dLat = transformLat(gcjLng - 105.0, gcjLat - 35.0)
        var dLng = transformLng(gcjLng - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return Pair(gcjLat - dLat, gcjLng - dLng)
    }

    private fun isOutOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
