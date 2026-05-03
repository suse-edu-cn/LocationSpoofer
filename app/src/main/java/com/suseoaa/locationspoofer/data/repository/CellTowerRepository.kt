package com.suseoaa.locationspoofer.data.repository

import com.suseoaa.locationspoofer.utils.OpenCellIdClient

class CellTowerRepository(private val openCellIdClient: OpenCellIdClient) {

    suspend fun fetchCellData(lat: Double, lng: Double, apiKey: String): String =
        openCellIdClient.fetchCellData(lat, lng, apiKey)
}
