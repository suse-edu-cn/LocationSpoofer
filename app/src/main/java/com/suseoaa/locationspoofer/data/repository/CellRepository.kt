package com.suseoaa.locationspoofer.data.repository

import com.suseoaa.locationspoofer.utils.OpenCellIdClient

class CellRepository(
    private val openCellIdClient: OpenCellIdClient
) {
    suspend fun fetchCellData(lat: Double, lng: Double, token: String): String =
        openCellIdClient.fetchCellData(lat, lng, token)
}
