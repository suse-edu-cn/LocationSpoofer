package com.suseoaa.locationspoofer.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WigleClient {

    companion object {
        private const val TAG = "WigleClient"
        private const val TIMEOUT_SECONDS = 5L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 验证 token 是否有效 */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "validateToken: token=${token.take(8)}...")
        if (token.isBlank()) {
            Log.w(TAG, "validateToken: token is blank")
            return@withContext false
        }
        return@withContext try {
            val request = Request.Builder()
                .url("https://api.wigle.net/api/v2/profile/user")
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "validateToken RESPONSE: code=${response.code} elapsed=${elapsed}ms")
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val success = JSONObject(body).optString("success") == "true"
                Log.d(TAG, "validateToken: success=$success")
                success
            } else {
                Log.w(TAG, "validateToken: HTTP ${response.code}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateToken EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    suspend fun fetchWifiData(lat: Double, lng: Double, token: String): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "fetchWifiData START: lat=$lat lng=$lng")

            if (token.isBlank()) {
                Log.w(TAG, "fetchWifiData: token is blank, using fallback immediately")
                return@withContext generateFallbackWifi()
            }

            val latrange1 = lat - 0.002
            val latrange2 = lat + 0.002
            val longrange1 = lng - 0.002
            val longrange2 = lng + 0.002

            val url =
                "https://api.wigle.net/api/v2/network/search?latrange1=$latrange1&latrange2=$latrange2&longrange1=$longrange1&longrange2=$longrange2"

            Log.d(TAG, "fetchWifiData REQUEST: $url (timeout=${TIMEOUT_SECONDS}s)")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Basic $token")
                .addHeader("Accept", "application/json")
                .build()

            try {
                val startTime = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "fetchWifiData RESPONSE: code=${response.code} elapsed=${elapsed}ms")

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "fetchWifiData BODY: len=${body.length}")
                    val jsonObject = JSONObject(body)
                    val results = jsonObject.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        Log.w(TAG, "fetchWifiData: results empty, using fallback")
                        return@withContext generateFallbackWifi()
                    }

                    val wifiList = mutableListOf<JSONObject>()
                    val count = minOf(results.length(), 10)

                    for (i in 0 until count) {
                        val item = results.getJSONObject(i)
                        val wifi = JSONObject()
                        wifi.put("ssid", item.optString("ssid", "WLAN_${(1000..9999).random()}"))
                        wifi.put("bssid", item.optString("netid", generateRandomBssid()))
                        wifiList.add(wifi)
                    }
                    Log.d(TAG, "fetchWifiData OK: ${wifiList.size} APs parsed")
                    return@withContext wifiList.toString()
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "fetchWifiData HTTP ERROR: code=${response.code} body=${errBody.take(300)}")
                    return@withContext generateFallbackWifi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchWifiData EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                return@withContext generateFallbackWifi()
            }
        }

    private fun generateRandomBssid(): String {
        val ouis = listOf("00:14:22", "cc:2d:e0", "44:a8:42", "00:25:9c")
        return "${ouis.random()}:${
            String.format(
                "%02x:%02x:%02x",
                (0..255).random(),
                (0..255).random(),
                (0..255).random()
            )
        }"
    }

    private fun generateFallbackWifi(): String {
        Log.d(TAG, "generateFallbackWifi: generating 10 synthetic APs")
        val list = mutableListOf<JSONObject>()
        for (i in 0..9) {
            val wifi = JSONObject()
            wifi.put("ssid", "WLAN_${(1000..9999).random()}")
            wifi.put("bssid", generateRandomBssid())
            list.add(wifi)
        }
        return list.toString()
    }
}
