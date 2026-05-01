package com.suseoaa.locationspoofer.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CellRepository(private val client: OkHttpClient) {

    private val token = "pk.84cd3deda809a3898ea6a7eb7920b5ab"

    suspend fun fetchCellData(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        val offset = 0.008
        val latMin = lat - offset
        val lonMin = lng - offset
        val latMax = lat + offset
        val lonMax = lng + offset

        val url = String.format(
            Locale.US,
            "https://opencellid.org/cell/getInArea?key=%s&BBOX=%.6f,%.6f,%.6f,%.6f&format=json",
            token, latMin, lonMin, latMax, lonMax
        )

        val request = Request.Builder().url(url).get().build()
        val resultArray = JSONArray()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("CellRepository", "OpenCelliD请求失败，状态码: ${response.code}")
                    return@withContext generateFallbackCell()
                }
                
                val bodyStr = response.body?.string() ?: return@withContext generateFallbackCell()
                val rootObj = JSONObject(bodyStr)
                
                if (rootObj.has("cells")) {
                    val cellsArray = rootObj.getJSONArray("cells")
                    val limit = minOf(cellsArray.length(), 5)
                    for (i in 0 until limit) {
                        val cellObj = cellsArray.getJSONObject(i)
                        
                        val radio = cellObj.optString("radio", "LTE").uppercase(Locale.US)
                        val mcc = cellObj.optInt("mcc", 460)
                        val mnc = cellObj.optInt("mnc", 0)
                        val lac = cellObj.optInt("lac", cellObj.optInt("area", 1))
                        val cid = cellObj.optInt("cellid", cellObj.optInt("cid", 1))
                        val signal = cellObj.optInt("averageSignalStrength", -85)

                        val hookCellObj = JSONObject().apply {
                            put("radio", radio)
                            put("mcc", mcc)
                            put("mnc", mnc)
                            put("lac", lac)
                            put("cid", cid)
                            put("signal", signal)
                        }
                        resultArray.put(hookCellObj)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CellRepository", "OpenCelliD解析异常", e)
            return@withContext generateFallbackCell()
        }

        if (resultArray.length() == 0) {
            return@withContext generateFallbackCell()
        }

        return@withContext resultArray.toString()
    }

    private fun generateFallbackCell(): String {
        val resultArray = JSONArray()
        for (i in 0 until 5) {
            val hookCellObj = JSONObject().apply {
                put("radio", "LTE")
                put("mcc", 460)
                put("mnc", listOf(0, 1, 2, 3, 11).random())
                put("lac", (1000..65000).random())
                put("cid", (1000..268435455).random())
                put("signal", (-110..-60).random())
            }
            resultArray.put(hookCellObj)
        }
        return resultArray.toString()
    }
}
