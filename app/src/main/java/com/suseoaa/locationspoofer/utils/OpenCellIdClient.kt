package com.suseoaa.locationspoofer.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenCellIdClient {

    companion object {
        private const val TAG = "OpenCellIdClient"
        private const val TIMEOUT_SECONDS = 5L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 从 OpenCellID 获取目标位置附近的基站数据。
     */
    suspend fun fetchCellData(lat: Double, lng: Double, apiKey: String): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "fetchCellData START: lat=$lat lng=$lng apiKey=${apiKey.take(8)}...")

            if (apiKey.isBlank()) {
                Log.w(TAG, "fetchCellData: apiKey is blank, using fallback")
                val fallback = generateFallbackCells(lat, lng)
                Log.d(TAG, "fetchCellData FALLBACK (no key): ${fallback.take(200)}")
                return@withContext fallback
            }

            val delta = 0.005
            val bbox = "${lat - delta},${lng - delta},${lat + delta},${lng + delta}"
            val url = "https://opencellid.org/cell/getInArea?" +
                    "key=$apiKey&" +
                    "BBOX=$bbox&" +
                    "mcc=460&" +
                    "limit=15&" +
                    "format=json"

            Log.d(TAG, "fetchCellData REQUEST: $url (timeout=${TIMEOUT_SECONDS}s)")

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build()

            try {
                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "fetchCellData RESPONSE: code=${response.code} elapsed=${elapsed}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "fetchCellData BODY: len=${body.length} preview=${body.take(300)}")
                    val json = JSONObject(body)
                    val cells = json.optJSONArray("cells")
                    if (cells == null || cells.length() == 0) {
                        Log.w(TAG, "fetchCellData: cells empty, using fallback")
                        val fallback = generateFallbackCells(lat, lng)
                        Log.d(TAG, "fetchCellData FALLBACK (empty): ${fallback.take(200)}")
                        return@withContext fallback
                    }
                    val result = parseCells(cells)
                    Log.d(TAG, "fetchCellData OK: ${cells.length()} cells parsed, result=${result.take(300)}")
                    return@withContext result
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "fetchCellData HTTP ERROR: code=${response.code} body=${errBody.take(300)}")
                    val fallback = generateFallbackCells(lat, lng)
                    Log.d(TAG, "fetchCellData FALLBACK (http error): ${fallback.take(200)}")
                    return@withContext fallback
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchCellData EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                val fallback = generateFallbackCells(lat, lng)
                Log.d(TAG, "fetchCellData FALLBACK (exception): ${fallback.take(200)}")
                return@withContext fallback
            }
        }

    private fun parseCells(cells: JSONArray): String {
        val result = JSONArray()
        val count = minOf(cells.length(), 15)
        for (i in 0 until count) {
            val cell = cells.getJSONObject(i)
            val item = JSONObject()
            item.put("radio", cell.optString("radio", "LTE"))
            item.put("mcc", cell.optInt("mcc", 460))
            item.put("mnc", cell.optInt("mnc", 0))
            item.put("lac", cell.optInt("lac", 0))
            item.put("cellid", cell.optInt("cellid", 0))
            item.put("signal", cell.optInt("averageSignalStrength", -90))
            item.put("tac", cell.optInt("tac", 0))
            item.put("pci", cell.optInt("pci", 0))
            result.put(item)
        }
        return result.toString()
    }

    private fun generateFallbackCells(lat: Double, lng: Double): String {
        Log.d(TAG, "generateFallbackCells: lat=$lat lng=$lng")
        val result = JSONArray()
        val operators = listOf(
            Triple(460, 0, "GSM"),
            Triple(460, 0, "LTE"),
            Triple(460, 1, "GSM"),
            Triple(460, 1, "LTE"),
            Triple(460, 11, "LTE"),
        )
        val baseLac = ((lat * 1000).toInt() % 60000 + 10000)
        val baseCid = ((lng * 10000).toInt() % 200000000 + 10000000)

        for (i in operators.indices) {
            val (mcc, mnc, radio) = operators[i]
            val item = JSONObject()
            item.put("radio", radio)
            item.put("mcc", mcc)
            item.put("mnc", mnc)
            item.put("lac", baseLac + i)
            item.put("cellid", baseCid + i * 1000)
            item.put("signal", (-110..-70).random())
            item.put("tac", if (radio == "LTE") baseLac + i else 0)
            item.put("pci", if (radio == "LTE") (0..503).random() else 0)
            result.put(item)
        }
        Log.d(TAG, "generateFallbackCells: generated ${result.length()} cells")
        return result.toString()
    }
}
