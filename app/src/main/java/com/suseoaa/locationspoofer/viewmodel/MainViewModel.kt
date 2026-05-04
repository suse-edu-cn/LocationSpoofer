package com.suseoaa.locationspoofer.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.data.repository.CellRepository
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.service.SpoofingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val cellRepository: CellRepository,
    private val context: Context
) : ViewModel() {

    private val openCellIdToken = "pk.84cd3deda809a3898ea6a7eb7920b5ab"

    private val _uiState = MutableStateFlow(
        AppState(savedLocations = settingsRepository.getSavedLocations())
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private var locationSyncJob: Job? = null
    private var autoRouteJob: Job? = null

    init {
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = locationRepository.checkRootAccess()
            val lsposed = locationRepository.isModuleActive()

            if (SpoofingService.isRunning) {
                locationRepository.stopSpoofing(context)
            }

            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = root,
                    isLSPosedActive = lsposed,
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE
                )
            }
            fetchCurrentLocation(context)
        }
    }

    fun fetchCurrentLocation(ctx: Context) {
        val client = try {
            AMapLocationClient.updatePrivacyShow(ctx.applicationContext, true, true)
            AMapLocationClient.updatePrivacyAgree(ctx.applicationContext, true)
            AMapLocationClient(ctx.applicationContext)
        } catch (e: Exception) {
            return
        }
        AMapLocationClientOption.setLocationProtocol(AMapLocationClientOption.AMapLocationProtocol.HTTPS)
        client.setLocationOption(AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
        })
        client.setLocationListener { loc ->
            if (loc != null && loc.errorCode == 0) {
                if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            latitudeInput = String.format("%.6f", loc.latitude),
                            longitudeInput = String.format("%.6f", loc.longitude),
                            showCoordinateError = false
                        )
                    }
                }
            }
            client.stopLocation()
            client.onDestroy()
        }
        client.startLocation()
    }

    fun updateLongitude(value: String) {
        if (isValidCoord(value)) _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
    }

    fun updateLatitude(value: String) {
        if (isValidCoord(value)) _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
    }

    private fun isValidCoord(value: String): Boolean {
        if (value.isEmpty() || value == "-") return true
        return value.toDoubleOrNull() != null
    }

    fun startSpoofing() {
        val state = _uiState.value
        val lng = state.longitudeInput.toDoubleOrNull()
        val lat = state.latitudeInput.toDoubleOrNull()
        if (lng == null || lat == null || lng !in -180.0..180.0 || lat !in -90.0..90.0) {
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSpoofingActive = true
                )
            }

            val now = System.currentTimeMillis()
            // 立即开启模拟，消除等待网络请求的延迟
            locationRepository.startSpoofing(
                context, lat, lng,
                "STILL", 0f, now,
                emptyList(), false
            )
            Log.i("MainViewModel", ">>> POINT SPOOFING STARTED IMMEDIATELY.")

            // 后台异步获取基站数据并更新
            launch(Dispatchers.IO) {
                try {
                    Log.i("MainViewModel", ">>> Fetching Cell Data for Point Spoofing...")
                    val cellJson = cellRepository.fetchCellData(lat, lng, openCellIdToken)
                    Log.i("MainViewModel", ">>> Cell data complete. Result: $cellJson")
                    locationRepository.updateWifiJson("[]")
                    locationRepository.updateCellJson(cellJson)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Fetch Cell Data failed", e)
                }
            }
        }
    }

    fun stopSpoofing() {
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        viewModelScope.launch {
            locationRepository.stopSpoofing(context)
            _uiState.update {
                it.copy(
                    isSpoofingActive = false
                )
            }
        }
    }

    fun moveByJoystick(bearing: Double, intensity: Float, maxSpeedMs: Float) {
        val elapsedSec = 0.1
        val distance = maxSpeedMs * intensity * elapsedSec
        val R = 6378137.0
        val bearingRad = Math.toRadians(bearing)
        val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
        val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val newLatRad = Math.asin(
            kotlin.math.sin(latRad) * kotlin.math.cos(distance / R) +
            kotlin.math.cos(latRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(bearingRad)
        )
        val newLngRad = lngRad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(latRad),
            kotlin.math.cos(distance / R) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad)
        )
        val newLat = Math.toDegrees(newLatRad)
        val newLng = Math.toDegrees(newLngRad)
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", newLat),
                longitudeInput = String.format("%.6f", newLng),
                simBearing = bearing.toFloat(),
                showCoordinateError = false
            )
        }
        SpooferProvider.latitude = newLat
        SpooferProvider.longitude = newLng
        SpooferProvider.simBearing = bearing.toFloat()
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    fun enterRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.SELECTING,
                routePoints = emptyList()
            )
        }
    }

    fun addRoutePoint(lat: Double, lng: Double) {
        _uiState.update { it.copy(routePoints = it.routePoints + RoutePoint(lat, lng)) }
    }

    fun undoLastRoutePoint() {
        _uiState.update { state ->
            if (state.routePoints.isEmpty()) state
            else state.copy(routePoints = state.routePoints.dropLast(1))
        }
    }

    fun finishSelectingPoints() {
        if (_uiState.value.routePoints.size < 2) return
        _uiState.update { it.copy(routePlanStage = RoutePlanStage.READY) }
    }

    fun restartSelectingPoints() {
        _uiState.update {
            it.copy(
                routePoints = emptyList(),
                routePlanStage = RoutePlanStage.SELECTING
            )
        }
    }

    fun setRouteRunMode(mode: RouteRunMode) {
        _uiState.update { it.copy(routeRunMode = mode) }
    }

    fun setRouteSimMode(mode: SimMode) {
        _uiState.update { it.copy(routeSimMode = mode) }
    }

    fun setCustomSpeedMs(speed: Double) {
        _uiState.update { it.copy(customSpeedMs = speed.coerceIn(0.1, 100.0)) }
    }

    private fun getEffectiveSpeedMs(): Double {
        val state = _uiState.value
        return if (state.routeSimMode == SimMode.CUSTOM) state.customSpeedMs
        else state.routeSimMode.speedMs
    }

    fun confirmMapPoint(lat: Double, lng: Double) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", lat),
                longitudeInput = String.format("%.6f", lng),
                mapConfirmedPoint = Pair(lat, lng),
                showCoordinateError = false
            )
        }
    }

    fun clearMapPoint() {
        _uiState.update { it.copy(mapConfirmedPoint = null) }
    }

    fun startRoutePlanning() {
        val state = _uiState.value
        if (state.routePoints.size < 2) return
        val startPoint = state.routePoints.first()

        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", startPoint.lat),
                longitudeInput = String.format("%.6f", startPoint.lng),
                routePlanStage = RoutePlanStage.RUNNING
            )
        }

        val isLoop = state.routeRunMode == RouteRunMode.LOOP

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSpoofingActive = true
                )
            }
            
            val now = System.currentTimeMillis()
            // 立即开启模拟，消除等待网络请求的延迟
            locationRepository.startSpoofing(
                context, startPoint.lat, startPoint.lng,
                if (isLoop) state.routeSimMode.name else "STILL",
                0f, now, state.routePoints, isLoop
            )
            Log.i("MainViewModel", ">>> ROUTE MODE STARTED IMMEDIATELY.")

            // 后台异步获取基站数据并更新
            launch(Dispatchers.IO) {
                try {
                    Log.i("MainViewModel", ">>> Fetching Cell Data for Route Spoofing...")
                    val cellJson = cellRepository.fetchCellData(startPoint.lat, startPoint.lng, openCellIdToken)
                    Log.i("MainViewModel", ">>> Route Cell data complete.")
                    locationRepository.updateWifiJson("[]")
                    locationRepository.updateCellJson(cellJson)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Fetch Route Cell Data failed", e)
                }
            }
        }

        if (isLoop) {
            startAutoRouteLoop()
        }
    }

    fun stopRoutePlanning() {
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        viewModelScope.launch {
            locationRepository.stopSpoofing(context)
            _uiState.update {
                it.copy(
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE,
                    routePoints = emptyList(),
                    routeRunMode = RouteRunMode.MANUAL
                )
            }
        }
    }

    fun saveCurrentLocation(name: String) {
        val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
        val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
        settingsRepository.addSavedLocation(SavedLocation(name, lat, lng))
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun removeSavedLocation(loc: SavedLocation) {
        settingsRepository.removeSavedLocation(loc)
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun updateSearchKeyword(keyword: String) {
        _uiState.update { it.copy(searchKeyword = keyword) }
    }

    fun updateSearchResults(results: List<SavedLocation>) {
        _uiState.update { it.copy(searchResults = results) }
    }

    private fun startAutoRouteLoop() {
        autoRouteJob?.cancel()
        autoRouteJob = viewModelScope.launch(Dispatchers.Default) {
            val points = _uiState.value.routePoints
            if (points.size < 2) return@launch

            val speedMs = getEffectiveSpeedMs()
            if (speedMs <= 0.0) return@launch

            val tickMs = 100L
            val tickSec = tickMs / 1000.0
            var forward = true
            var segmentIndex = 0
            var progress = 0.0

            while (isActive) {
                val fromIdx = if (forward) segmentIndex else segmentIndex + 1
                val toIdx = if (forward) segmentIndex + 1 else segmentIndex
                val from = points[fromIdx]
                val to = points[toIdx]
                val segLen = haversineMeters(from, to)

                val stepDist = speedMs * tickSec
                progress += stepDist

                if (progress >= segLen) {
                    progress -= segLen
                    if (forward) {
                        segmentIndex++
                        if (segmentIndex >= points.lastIndex) {
                            forward = false
                            segmentIndex = points.lastIndex - 1
                            progress = 0.0
                        }
                    } else {
                        segmentIndex--
                        if (segmentIndex < 0) {
                            forward = true
                            segmentIndex = 0
                            progress = 0.0
                        }
                    }
                    val newFrom = if (forward) points[segmentIndex] else points[segmentIndex + 1]
                    updatePosition(newFrom.lat, newFrom.lng, 0f)
                } else {
                    val ratio = if (segLen > 0) progress / segLen else 0.0
                    val lat = from.lat + (to.lat - from.lat) * ratio
                    val lng = from.lng + (to.lng - from.lng) * ratio
                    val bearing = bearingBetween(from, to).toFloat()
                    updatePosition(lat, lng, bearing)
                }
                delay(tickMs)
            }
        }
    }

    private fun updatePosition(lat: Double, lng: Double, bearing: Float) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", lat),
                longitudeInput = String.format("%.6f", lng),
                simBearing = bearing,
                showCoordinateError = false
            )
        }
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.simBearing = bearing
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    private fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
        val R = 6378137.0
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * R * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
    }

    private fun bearingBetween(from: RoutePoint, to: RoutePoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val x = kotlin.math.sin(dLng) * kotlin.math.cos(lat2)
        val y = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
    }
}
