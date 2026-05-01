package com.suseoaa.locationspoofer.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.data.model.WifiLoadStatus
import com.suseoaa.locationspoofer.data.repository.CellRepository
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.data.repository.WifiRepository
import com.suseoaa.locationspoofer.BuildConfig
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.service.SpoofingService
import com.suseoaa.locationspoofer.utils.CoordinateConverter
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
    private val wifiRepository: WifiRepository,
    private val cellRepository: CellRepository,
    private val context: Context
) : ViewModel() {

    private val wigleToken = BuildConfig.WIGLE_TOKEN

    private val _uiState = MutableStateFlow(
        AppState(savedLocations = settingsRepository.getSavedLocations())
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private var locationSyncJob: Job? = null
    private var autoRouteJob: Job? = null

    init {
        initialize()
    }

    // 初始化

    private fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = locationRepository.checkRootAccess()
            val lsposed = locationRepository.isModuleActive()
            wifiRepository.validateToken(wigleToken)

            if (SpoofingService.isRunning) {
                locationRepository.stopSpoofing(context)
            }

            // 恢复持久化的全局模式状态
            val globalMode = settingsRepository.isGlobalModeEnabled()
            val fakeAirplane = settingsRepository.isFakeAirplaneModeEnabled()
            SpooferProvider.isGlobalMode = globalMode

            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = root,
                    isLSPosedActive = lsposed,
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE,
                    isGlobalModeEnabled = globalMode,
                    isFakeAirplaneModeEnabled = fakeAirplane
                )
            }
            fetchCurrentLocation(context)
        }
    }

    fun refreshRootAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isInitializing = true) }
            val root = locationRepository.checkRootAccess()
            val lsposed = locationRepository.isModuleActive()
            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = root,
                    isLSPosedActive = lsposed
                )
            }
        }
    }

    // 当前位置获取

    fun fetchCurrentLocation(ctx: Context) {
        requestSystemLocation(ctx, onlyIfEmpty = true, onResult = null, onError = null)
    }

    fun requestCurrentLocation(
        ctx: Context,
        onLocated: (Double, Double) -> Unit,
        onError: (String) -> Unit
    ) {
        requestSystemLocation(ctx, onlyIfEmpty = false, onResult = onLocated, onError = onError)
    }

    private fun requestSystemLocation(
        ctx: Context,
        onlyIfEmpty: Boolean,
        onResult: ((Double, Double) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (!hasLocationPermission(ctx)) {
            onError?.invoke("未授予定位权限")
            return
        }

        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError?.invoke("定位服务不可用")
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        val lastKnown = providers.mapNotNull { provider ->
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }

        if (lastKnown != null) {
            handleLocationUpdate(lastKnown, onlyIfEmpty, onResult)
            return
        }

        val provider = providers.firstOrNull { p ->
            try {
                locationManager.isProviderEnabled(p)
            } catch (e: Exception) {
                false
            }
        }
        if (provider == null) {
            onError?.invoke("定位服务未开启")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = ContextCompat.getMainExecutor(ctx)
            locationManager.getCurrentLocation(provider, CancellationSignal(), executor) { loc ->
                if (loc != null) {
                    handleLocationUpdate(loc, onlyIfEmpty, onResult)
                } else {
                    onError?.invoke("无法获取当前位置")
                }
            }
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                locationManager.removeUpdates(this)
                handleLocationUpdate(loc, onlyIfEmpty, onResult)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        try {
            @Suppress("DEPRECATION")
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            onError?.invoke("无定位权限")
        } catch (e: Exception) {
            onError?.invoke("无法获取当前位置")
        }
    }

    private fun handleLocationUpdate(
        location: Location,
        onlyIfEmpty: Boolean,
        onResult: ((Double, Double) -> Unit)?
    ) {
        val provider = location.provider ?: ""
        val (displayLat, displayLng) = if (provider == LocationManager.GPS_PROVIDER) {
            CoordinateConverter.wgs84ToGcj02(location.latitude, location.longitude)
        } else {
            Pair(location.latitude, location.longitude)
        }

        if (!onlyIfEmpty || _uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty()) {
            _uiState.update {
                it.copy(
                    latitudeInput = String.format("%.6f", displayLat),
                    longitudeInput = String.format("%.6f", displayLng),
                    showCoordinateError = false
                )
            }
        }

        onResult?.invoke(displayLat, displayLng)
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    // 坐标输入

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

    // 定点模拟

    fun startSpoofing() {
        val state = _uiState.value
        val gcjLng = state.longitudeInput.toDoubleOrNull()
        val gcjLat = state.latitudeInput.toDoubleOrNull()
        if (gcjLng == null || gcjLat == null || gcjLng !in -180.0..180.0 || gcjLat !in -90.0..90.0) {
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }
        // UI 存储的是 GCJ-02，写入 SpooferProvider 前转换为 WGS-84
        val (wgsLat, wgsLng) = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLng)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            locationRepository.startSpoofing(
                context, wgsLat, wgsLng,
                "STILL", 0f, now,
                emptyList(), false
            )
            _uiState.update {
                it.copy(
                    isSpoofingActive = true,
                    wifiLoadStatus = WifiLoadStatus.LOADING,
                    wifiApCount = 0
                )
            }
            val wifiJson = wifiRepository.fetchWifiData(gcjLat, gcjLng, wigleToken)
            val apCount = try { org.json.JSONArray(wifiJson).length() } catch (e: Exception) { 0 }
            locationRepository.updateWifiJson(wifiJson)
            
            // fetch cell data
            val cellJson = cellRepository.fetchCellData(wgsLat, wgsLng)
            locationRepository.updateCellJson(cellJson)
            
            _uiState.update { it.copy(wifiLoadStatus = WifiLoadStatus.DONE, wifiApCount = apCount) }
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
                    isSpoofingActive = false,
                    wifiLoadStatus = WifiLoadStatus.IDLE,
                    wifiApCount = 0
                )
            }
        }
    }

    // 摇杆控制

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
        // 摇杆计算结果仍是 GCJ-02 偏移，写入 Provider 前转换
        val (wgsLat, wgsLng) = CoordinateConverter.gcj02ToWgs84(newLat, newLng)
        SpooferProvider.latitude = wgsLat
        SpooferProvider.longitude = wgsLng
        SpooferProvider.simBearing = bearing.toFloat()
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    // 路线规划状态机

    /** 进入全屏地图，进入选点阶段 */
    fun enterRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.SELECTING,
                routePoints = emptyList()
            )
        }
    }

    /** 取消路线规划，回到 IDLE（不停止正在运行的 spoofing 服务） */
    fun cancelRoutePlanning() {
        autoRouteJob?.cancel()
        autoRouteJob = null
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList()
            )
        }
    }

    /** 地图中心确认添加路点 */
    fun addRoutePoint(lat: Double, lng: Double) {
        _uiState.update { it.copy(routePoints = it.routePoints + RoutePoint(lat, lng)) }
    }

    /** 撤销最后一个路点 */
    fun undoLastRoutePoint() {
        _uiState.update { state ->
            if (state.routePoints.isEmpty()) state
            else state.copy(routePoints = state.routePoints.dropLast(1))
        }
    }

    /** 结束选点 → READY */
    fun finishSelectingPoints() {
        if (_uiState.value.routePoints.size < 2) return
        _uiState.update { it.copy(routePlanStage = RoutePlanStage.READY) }
    }

    /** 重新选点：清空路点，回到 SELECTING */
    fun restartSelectingPoints() {
        _uiState.update {
            it.copy(
                routePoints = emptyList(),
                routePlanStage = RoutePlanStage.SELECTING
            )
        }
    }

    /** 设置路线运行模式 */
    fun setRouteRunMode(mode: RouteRunMode) {
        _uiState.update { it.copy(routeRunMode = mode) }
    }

    /** 设置循环模式速度 */
    fun setRouteSimMode(mode: SimMode) {
        _uiState.update { it.copy(routeSimMode = mode) }
    }

    /** 设置自定义速度 (m/s) */
    fun setCustomSpeedMs(speed: Double) {
        _uiState.update { it.copy(customSpeedMs = speed.coerceIn(0.1, 100.0)) }
    }

    /** 获取实际生效的速度 (m/s) */
    private fun getEffectiveSpeedMs(): Double {
        val state = _uiState.value
        return if (state.routeSimMode == SimMode.CUSTOM) state.customSpeedMs
        else state.routeSimMode.speedMs
    }

    /** 首页地图确认选点（GCJ-02 坐标，仅存入 UI State 用于显示） */
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

    /** 清除地图选点状态 */
    fun clearMapPoint() {
        _uiState.update { it.copy(mapConfirmedPoint = null) }
    }

    /**
     * 开始路线模拟。
     * - 手动模式：启动 spoofing（STILL），由摇杆驱动 moveByJoystick 实时更新坐标。
     * - 循环模式：启动 spoofing，自动沿路线点按速度移动，到终点后反向循环。
     */
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
        // 路线点 GCJ-02 → WGS-84（供 SpoofingService 的 routeJson 使用）
        val wgsRoutePoints = state.routePoints.map { p ->
            val (wLat, wLng) = CoordinateConverter.gcj02ToWgs84(p.lat, p.lng)
            RoutePoint(wLat, wLng)
        }
        val (wgsStartLat, wgsStartLng) = CoordinateConverter.gcj02ToWgs84(startPoint.lat, startPoint.lng)

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            locationRepository.startSpoofing(
                context, wgsStartLat, wgsStartLng,
                if (isLoop) state.routeSimMode.name else "STILL",
                0f, now, wgsRoutePoints, isLoop
            )
            _uiState.update {
                it.copy(
                    isSpoofingActive = true,
                    wifiLoadStatus = WifiLoadStatus.LOADING,
                    wifiApCount = 0
                )
            }
            val wifiJson = wifiRepository.fetchWifiData(startPoint.lat, startPoint.lng, wigleToken)
            val apCount = try { org.json.JSONArray(wifiJson).length() } catch (e: Exception) { 0 }
            locationRepository.updateWifiJson(wifiJson)
            _uiState.update { it.copy(wifiLoadStatus = WifiLoadStatus.DONE, wifiApCount = apCount) }
        }

        if (isLoop) {
            startAutoRouteLoop()
        }
    }

    /** 停止路线模拟，重置所有状态 */
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
                    wifiLoadStatus = WifiLoadStatus.IDLE,
                    wifiApCount = 0,
                    routePlanStage = RoutePlanStage.IDLE,
                    routePoints = emptyList(),
                    routeRunMode = RouteRunMode.MANUAL
                )
            }
        }
    }

    // 保存位置

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

    // 搜索

    fun updateSearchKeyword(keyword: String) {
        _uiState.update { it.copy(searchKeyword = keyword) }
    }

    fun updateSearchResults(results: List<SavedLocation>) {
        _uiState.update { it.copy(searchResults = results) }
    }

    // 内部工具

    /**
     * 循环模式自动移动。
     * 按路点顺序移动，到终点后反向，不断循环。
     * 同时实时同步坐标到 SpooferProvider（GCJ-02 → WGS-84）。
     */
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

    /** 更新当前模拟位置到 UI（GCJ-02）和 SpooferProvider（WGS-84） */
    private fun updatePosition(gcjLat: Double, gcjLng: Double, bearing: Float) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", gcjLat),
                longitudeInput = String.format("%.6f", gcjLng),
                simBearing = bearing,
                showCoordinateError = false
            )
        }
        val (wgsLat, wgsLng) = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLng)
        SpooferProvider.latitude = wgsLat
        SpooferProvider.longitude = wgsLng
        SpooferProvider.simBearing = bearing
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    // 全局定位模式

    fun setGlobalMode(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                locationRepository.enableGlobalMode()
            } else {
                locationRepository.disableGlobalMode()
            }
            settingsRepository.setGlobalModeEnabled(enabled)
            SpooferProvider.isGlobalMode = enabled
            _uiState.update { it.copy(isGlobalModeEnabled = enabled) }
        }
    }

    fun setFakeAirplaneMode(enabled: Boolean) {
        viewModelScope.launch {
            locationRepository.setFakeAirplaneMode(enabled)
            settingsRepository.setFakeAirplaneModeEnabled(enabled)
            _uiState.update { it.copy(isFakeAirplaneModeEnabled = enabled) }
        }
    }

    fun killAllUserApps() {
        viewModelScope.launch(Dispatchers.IO) {
            locationRepository.killAllUserApps()
        }
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
