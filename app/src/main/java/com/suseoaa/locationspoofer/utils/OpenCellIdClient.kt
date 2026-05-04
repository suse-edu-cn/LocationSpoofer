package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Log

class OpenCellIdClient(private val client: OkHttpClient) {

    suspend fun fetchCellData(lat: Double, lng: Double, token: String): String =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                Log.e("OpenCellIdClient", "OpenCellID Token is blank!")
                return@withContext "[]"
            }

            // OpenCellID 必须使用 WGS-84 坐标
            val wgs = CoordinateUtils.gcj02ToWgs84(lat, lng)
            val wgsLat = wgs.lat
            val wgsLng = wgs.lng

            // 搜索范围周边 1km
            val latMin = wgsLat - 0.01
            val latMax = wgsLat + 0.01
            val lonMin = wgsLng - 0.01
            val lonMax = wgsLng + 0.01

            val url = "https://opencellid.org/cell/getInArea?key=$token&BBOX=$latMin,$lonMin,$latMax,$lonMax&format=json"
            Log.i("OpenCellIdClient", ">>> REQUESTING OPENCELLID: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext "[]"
                    Log.i("OpenCellIdClient", ">>> API RESPONSE RECEIVED")
                    val jsonObject = JSONObject(body)
                    val cells = jsonObject.optJSONArray("cells")
                    if (cells == null || cells.length() == 0) {
                        Log.w("OpenCellIdClient", "No cells found for BBOX near $wgsLat, $wgsLng")
                        return@withContext "[]"
                    }

                    val cellList = mutableListOf<JSONObject>()
                    val count = minOf(cells.length(), 10)

                    for (i in 0 until count) {
                        val item = cells.getJSONObject(i)
                        val cell = JSONObject()
                        cell.put("mcc", item.optInt("mcc"))
                        cell.put("mnc", item.optInt("mnc"))
                        cell.put("lac", item.optInt("lac"))
                        cell.put("cid", item.optInt("cellid"))
                        cell.put("lat", item.optDouble("lat"))
                        cell.put("lon", item.optDouble("lon"))
                        cell.put("radio", item.optString("radio"))
                        cellList.add(cell)
                    }
                    val result = cellList.toString()
                    Log.i("OpenCellIdClient", ">>> FETCHED CELLS: $result")
                    return@withContext result
                } else {
                    Log.e("OpenCellIdClient", "API Error: ${response.code} ${response.message}")
                    return@withContext "[]"
                }
            } catch (e: Exception) {
                Log.e("OpenCellIdClient", "Exception during fetchCellData", e)
                return@withContext "[]"
            }
        }
}
