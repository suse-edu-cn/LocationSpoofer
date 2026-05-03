package com.suseoaa.locationspoofer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.data.model.WifiLoadStatus
import com.suseoaa.locationspoofer.data.repository.CellTowerRepository
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.data.repository.WifiRepository
import com.suseoaa.locationspoofer.provider.SpooferProvider
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    private val cellTowerRepository: CellTowerRepository,
    private val systemInitializer: com.suseoaa.locationspoofer.utils.SystemInitializer,
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val wigleToken =
        "QUlEODRhYjYwNzVjYjI4MTY5ZDU4Yjk2NzQxM2ZiYTFiMDA6YmY2NWE5M2RiYWQ1YzYwNmYwNzdkOTQ2NjE2NmI4MzM="

    private val openCellIdKey = "pk.84cd3deda809a3898ea6a7eb7920b5ab"

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
        Log.d(TAG, "========== APP INITIALIZE START ==========")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "[Init 1] Running systemInitializer.initialize()...")
            val initResult = systemInitializer.initialize(context)
            Log.d(TAG, "[Init 1] Result: root=${initResult.hasRoot} lsposed=${initResult.isLSPosedActive} config=${initResult.configWritten}")

            Log.d(TAG, "[Init 2] Validating Wigle token...")
            val wigleValid = wifiRepository.validateToken(wigleToken)
            Log.d(TAG, "[Init 2] Wigle token valid: $wigleValid")

            if (SpooferProvider.isActive) {
                Log.d(TAG, "[Init 3] SpooferProvider was active, stopping...")
                locationRepository.stopSpoofing(context)
            }

            // Toast: 初始化成功或失败
            val toastMsg = if (initResult.hasRoot && initResult.isLSPosedActive) {
                "初始化完成: Root=${initResult.hasRoot}, LSPosed=${initResult.isLSPosedActive}"
            } else {
                "初始化异常:\n${initResult.message}"
            }
            Log.d(TAG, "[Init 4] Showing toast: $toastMsg")
            systemInitializer.showToast(context, toastMsg)

            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = initResult.hasRoot,
                    isLSPosedActive = initResult.isLSPosedActive,
                    isSpoofingActive = false,
                    routePlanStage = RoutePlanStage.IDLE,
                    initStatus = initResult.message,
                    needReboot = initResult.needReboot
                )
            }
            Log.d(TAG, "[Init 5] UI state updated, fetching current location...")
            fetchCurrentLocation(context)
            Log.d(TAG, "========== APP INITIALIZE END ==========")
        }
    }

    /**
     * 重启系统（需要 root）
     */
    fun rebootSystem() {
        viewModelScope.launch {
            val success = systemInitializer.rebootSystem()
            if (!success) {
                systemInitializer.showToast(context, "重启失败，请手动重启设备")
            }
        }
    }

    // 当前位置获取（使用 Android 原生 LocationManager，避免 AMap 鉴权问题）

    fun fetchCurrentLocation(ctx: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            try {
                // 先尝试用 getLastKnownLocation 快速获取
                val providers = listOf(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.PASSIVE_PROVIDER
                )
                for (p in providers) {
                    try {
                        val last = locationManager.getLastKnownLocation(p)
                        if (last != null && last.latitude != 0.0 && last.longitude != 0.0) {
                            Log.d(TAG, "fetchCurrentLocation: got last known from $p: (${last.latitude}, ${last.longitude})")
                            _uiState.update {
                                it.copy(
                                    latitudeInput = String.format("%.6f", last.latitude),
                                    longitudeInput = String.format("%.6f", last.longitude),
                                    showCoordinateError = false
                                )
                            }
                            return@post
                        }
                    } catch (_: SecurityException) {}
                }

                // 没有缓存位置，请求更新（优先 network，速度快）
                val provider = when {
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                        android.location.LocationManager.NETWORK_PROVIDER
                    locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                        android.location.LocationManager.GPS_PROVIDER
                    else -> return@post
                }
                Log.d(TAG, "fetchCurrentLocation: requesting updates from $provider")
                locationManager.requestLocationUpdates(provider, 0L, 0f, object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        Log.d(TAG, "fetchCurrentLocation: onLocationChanged from $provider: (${location.latitude}, ${location.longitude})")
                        if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    latitudeInput = String.format("%.6f", location.latitude),
                                    longitudeInput = String.format("%.6f", location.longitude),
                                    showCoordinateError = false
                                )
                            }
                        }
                        locationManager.removeUpdates(this)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "fetchCurrentLocation: SecurityException: ${e.message}")
            }
        }
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
        val lng = state.longitudeInput.toDoubleOrNull()
        val lat = state.latitudeInput.toDoubleOrNull()
        Log.d(TAG, "========== START SPOOFING ==========")
        Log.d(TAG, "startSpoofing: lat=$lat lng=$lng (raw input: '${state.latitudeInput}', '${state.longitudeInput}')")
        if (lng == null || lat == null || lng !in -180.0..180.0 || lat !in -90.0..90.0) {
            Log.e(TAG, "startSpoofing: INVALID coordinates, aborting")
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            // 立即生成合成 WiFi + 基站数据（同步，不依赖网络）
            // 这样 Xposed 模块在 readConfig() 时就能拿到完整的假数据
            val syntheticWifi = generateSyntheticWifi()
            val syntheticCell = generateSyntheticCell(lat, lng)
            Log.d(TAG, "[0] synthetic WiFi=${syntheticWifi.take(100)}, Cell=${syntheticCell.take(100)}")

            Log.d(TAG, "[1] startSpoofing: calling locationRepository.startSpoofing()...")
            locationRepository.startSpoofing(
                context, lat, lng,
                "STILL", 0f, now,
                emptyList(), false,
                wifiJson = syntheticWifi,
                cellJson = syntheticCell
            )
            Log.d(TAG, "[1] startSpoofing: done, synthetic data written to SpooferProvider + config file")

            _uiState.update {
                it.copy(
                    isSpoofingActive = true,
                    wifiLoadStatus = WifiLoadStatus.LOADING,
                    wifiApCount = 10
                )
            }

            // 后台尝试从 API 获取更真实的数据（可选，不影响核心功能）
            launch(Dispatchers.IO) {
                try {
                    val fetchStart = System.currentTimeMillis()
                    val wifiJson = wifiRepository.fetchWifiData(lat, lng, wigleToken)
                    val cellJson = cellTowerRepository.fetchCellData(lat, lng, openCellIdKey)
                    val fetchElapsed = System.currentTimeMillis() - fetchStart
                    Log.d(TAG, "[2] API fetch complete in ${fetchElapsed}ms")

                    val apCount = try { org.json.JSONArray(wifiJson).length() } catch (e: Exception) { 0 }
                    Log.d(TAG, "[2] WiFi APs=$apCount, Cell len=${cellJson.length}")

                    var updated = false
                    // 仅当 API 返回了有效数据时才替换合成数据
                    if (apCount > 0) {
                        locationRepository.updateWifiJson(wifiJson)
                        updated = true
                        Log.d(TAG, "[2] replaced synthetic WiFi with API data")
                    }
                    if (cellJson != "[]" && cellJson.length > 5) {
                        locationRepository.updateCellJson(cellJson)
                        updated = true
                        Log.d(TAG, "[2] replaced synthetic Cell with API data")
                    }
                    // 更新配置文件，让 Xposed 模块也能读到最新数据
                    if (updated) {
                        locationRepository.saveCurrentConfigToFile()
                    }
                    _uiState.update { it.copy(wifiLoadStatus = WifiLoadStatus.DONE, wifiApCount = apCount.coerceAtLeast(10)) }
                } catch (e: Exception) {
                    Log.e(TAG, "[2] API fetch failed (using synthetic data): ${e.javaClass.simpleName}: ${e.message}")
                    _uiState.update { it.copy(wifiLoadStatus = WifiLoadStatus.DONE) }
                }
            }
            Log.d(TAG, "========== START SPOOFING DONE ==========")
        }
    }

    /** 生成合成 WiFi AP 列表（基于确定性随机，同坐标同结果） */
    private fun generateSyntheticWifi(): String {
        val list = org.json.JSONArray()
        val ouis = listOf("00:14:22", "cc:2d:e0", "44:a8:42", "00:25:9c", "d8:07:b6", "f8:1a:67")
        for (i in 0..9) {
            val wifi = org.json.JSONObject()
            wifi.put("ssid", "WLAN_${1000 + i * 111}")
            wifi.put("bssid", "${ouis[i % ouis.size]}:${String.format("%02x:%02x:%02x", i * 17, i * 31, i * 47)}")
            list.put(wifi)
        }
        return list.toString()
    }

    /** 生成合成基站数据（基于坐标确定性生成 LAC/CID） */
    private fun generateSyntheticCell(lat: Double, lng: Double): String {
        val result = org.json.JSONArray()
        val operators = listOf(
            Triple(460, 0, "LTE"),   // 中国移动
            Triple(460, 0, "GSM"),
            Triple(460, 1, "LTE"),   // 中国联通
            Triple(460, 1, "GSM"),
            Triple(460, 11, "LTE"),  // 中国电信
        )
        val baseLac = ((lat * 1000).toInt() % 60000 + 10000)
        val baseCid = ((lng * 10000).toInt() % 200000000 + 10000000)
        for (i in operators.indices) {
            val (mcc, mnc, radio) = operators[i]
            val item = org.json.JSONObject()
            item.put("radio", radio)
            item.put("mcc", mcc)
            item.put("mnc", mnc)
            item.put("lac", baseLac + i)
            item.put("cellid", baseCid + i * 1000)
            item.put("signal", -90 + i * 5)
            item.put("tac", if (radio == "LTE") baseLac + i else 0)
            item.put("pci", if (radio == "LTE") i * 3 else 0)
            result.put(item)
        }
        return result.toString()
    }

    fun stopSpoofing() {
        Log.d(TAG, "========== STOP SPOOFING ==========")
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
            Log.d(TAG, "========== STOP SPOOFING DONE ==========")
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
        // 实时同步给 SpooferProvider
        SpooferProvider.latitude = newLat
        SpooferProvider.longitude = newLng
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

    /** 首页地图确认选点 */
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
        Log.d(TAG, "========== START ROUTE PLANNING ==========")
        Log.d(TAG, "startRoutePlanning: ${state.routePoints.size} points, mode=${state.routeRunMode}")
        if (state.routePoints.size < 2) {
            Log.w(TAG, "startRoutePlanning: not enough points, aborting")
            return
        }
        val startPoint = state.routePoints.first()
        Log.d(TAG, "startRoutePlanning: start=(${startPoint.lat}, ${startPoint.lng})")

        _uiState.update {
            it.copy(
                latitudeInput = String.format("%.6f", startPoint.lat),
                longitudeInput = String.format("%.6f", startPoint.lng),
                routePlanStage = RoutePlanStage.RUNNING
            )
        }

        val isLoop = state.routeRunMode == RouteRunMode.LOOP

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val routePoints = state.routePoints

            // 立即生成合成数据，确保 Xposed 模块的 readConfig() 能拿到完整假数据
            val syntheticWifi = generateSyntheticWifi()
            val syntheticCell = generateSyntheticCell(startPoint.lat, startPoint.lng)
            Log.d(TAG, "startRoutePlanning: synthetic WiFi=${syntheticWifi.take(80)}, Cell=${syntheticCell.take(80)}")

            Log.d(TAG, "startRoutePlanning: calling locationRepository.startSpoofing()...")
            locationRepository.startSpoofing(
                context, startPoint.lat, startPoint.lng,
                if (isLoop) state.routeSimMode.name else "STILL",
                0f, now, routePoints, isLoop,
                wifiJson = syntheticWifi,
                cellJson = syntheticCell
            )
            Log.d(TAG, "startRoutePlanning: startSpoofing() done")
            _uiState.update {
                it.copy(
                    isSpoofingActive = true,
                    wifiLoadStatus = WifiLoadStatus.DONE,
                    wifiApCount = 10
                )
            }
            Log.d(TAG, "startRoutePlanning: spoofing active, fetching WiFi+Cell in background...")

            // 后台获取更真实的数据（可选）
            launch(Dispatchers.IO) {
                try {
                    val wifiJson = wifiRepository.fetchWifiData(startPoint.lat, startPoint.lng, wigleToken)
                    val cellJson = cellTowerRepository.fetchCellData(startPoint.lat, startPoint.lng, openCellIdKey)
                    val apCount = try { org.json.JSONArray(wifiJson).length() } catch (e: Exception) { 0 }
                    Log.d(TAG, "startRoutePlanning bg: WiFi APs=$apCount, Cell len=${cellJson.length}")
                    var updated = false
                    if (apCount > 0) {
                        locationRepository.updateWifiJson(wifiJson)
                        updated = true
                        Log.d(TAG, "startRoutePlanning bg: replaced synthetic WiFi")
                    }
                    if (cellJson != "[]" && cellJson.length > 5) {
                        locationRepository.updateCellJson(cellJson)
                        updated = true
                        Log.d(TAG, "startRoutePlanning bg: replaced synthetic Cell")
                    }
                    if (updated) {
                        locationRepository.saveCurrentConfigToFile()
                    }
                    _uiState.update { it.copy(wifiApCount = apCount.coerceAtLeast(10)) }
                } catch (e: Exception) {
                    Log.e(TAG, "startRoutePlanning bg EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Log.d(TAG, "========== START ROUTE PLANNING DONE (API fetch in bg) ==========")
        }

        if (isLoop) {
            Log.d(TAG, "startRoutePlanning: starting auto route loop")
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
     * 同时实时同步坐标到 SpooferProvider。
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
            var progress = 0.0 // 当前段上已走过的距离（米）

            while (isActive) {
                val fromIdx = if (forward) segmentIndex else segmentIndex + 1
                val toIdx = if (forward) segmentIndex + 1 else segmentIndex
                val from = points[fromIdx]
                val to = points[toIdx]
                val segLen = haversineMeters(from, to)

                val stepDist = speedMs * tickSec
                progress += stepDist

                if (progress >= segLen) {
                    // 到达当前段终点
                    progress -= segLen
                    if (forward) {
                        segmentIndex++
                        if (segmentIndex >= points.lastIndex) {
                            // 到达终点，反向
                            forward = false
                            segmentIndex = points.lastIndex - 1
                            progress = 0.0
                        }
                    } else {
                        segmentIndex--
                        if (segmentIndex < 0) {
                            // 回到起点，正向
                            forward = true
                            segmentIndex = 0
                            progress = 0.0
                        }
                    }
                    // 重新获取段信息并继续
                    val newFrom = if (forward) points[segmentIndex] else points[segmentIndex + 1]
                    updatePosition(newFrom.lat, newFrom.lng, 0f)
                } else {
                    // 在段中间插值
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

    /** 更新当前模拟位置到 UI 和 SpooferProvider */
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
