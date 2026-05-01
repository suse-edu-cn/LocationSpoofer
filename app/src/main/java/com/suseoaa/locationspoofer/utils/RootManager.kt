package com.suseoaa.locationspoofer.utils

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootManager {

    /**
     * 初始化 root shell 并检查授权。
     * Shell.getShell() 是 libsu 的正确入口：
     * - 首次调用时触发 Magisk 授权弹窗，阻塞直到用户响应（最长 60 秒，在 LocationApp 中配置）
     * - 授权后 shell 单例被缓存，后续所有 Shell.cmd() 复用，无需重复授权
     */
    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell()
            Shell.isAppGrantedRoot() == true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("appops set com.suseoaa.locationspoofer android:mock_location allow")
            .exec().isSuccess
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("appops set com.suseoaa.locationspoofer android:mock_location deny")
            .exec().isSuccess
    }

    suspend fun enableGlobalMode(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "appops set com.suseoaa.locationspoofer android:mock_location allow",
            "settings put secure location_mode 3",
            "settings put global development_settings_enabled 1",
            "settings put global assisted_gps_enabled 0",
            "settings put global location_accuracy_enabled 0 2>/dev/null || true",
            "settings put global enable_gnss_raw_meas_full_tracking 0 2>/dev/null || true",
            "settings put secure enhanced_location_accuracy 0 2>/dev/null || true",
            "setprop debug.location.gnss.mock 1 2>/dev/null || true",
            "setprop ro.com.google.locationfeatures 0 2>/dev/null || true",
            // ★ 新增强制拦截：关闭底层真实的 NLP 和 GNSS provider，仅保留 Mock
            "settings put secure location_providers_allowed -gps,-network 2>/dev/null || true"
        ).exec().isSuccess
    }

    suspend fun disableGlobalMode(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "settings put secure location_mode 3",
            "settings put global assisted_gps_enabled 1",
            "settings put global location_accuracy_enabled 1 2>/dev/null || true",
            "settings put secure enhanced_location_accuracy 1 2>/dev/null || true",
            "settings put secure location_providers_allowed +gps,+network 2>/dev/null || true"
        ).exec().isSuccess
    }

    suspend fun enableFakeAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "settings put global airplane_mode_radios \"bluetooth,nfc,wimax\"",
            "settings put global airplane_mode_toggleable_radios \"bluetooth,wifi,nfc\""
        ).exec().isSuccess
    }

    /**
     * 禁用系统网络定位提供者(NLP)，防止基站定位覆盖模拟GPS位置。
     * 在模拟定位启动时调用。
     */
    suspend fun disableNlp(): Boolean = withContext(Dispatchers.IO) {
        val nlpPackages = listOf(
            "com.google.android.gms.location.nlp",
            "com.google.android.location",
            "com.amap.android.location",
            "com.amap.android.ams",
            "com.baidu.map.location",
            "com.tencent.android.location",
            "com.huawei.lbs",
            "com.huawei.location",
            "com.xiaomi.metoknlp",
            "com.coloros.pcmcs",
            "com.coloros.location",
            "com.oplus.location",
            "com.vivo.lbs"
        )
        val disableCmds = nlpPackages.map { "pm disable-user --user 0 $it 2>/dev/null || pm disable $it 2>/dev/null || true" }
        Shell.cmd(
            "settings put secure location_mode 1",
            "settings put secure location_providers_allowed gps",
            "settings put global assisted_gps_enabled 0",
            "settings put global location_accuracy_enabled 0 2>/dev/null || true",
            "settings put secure enhanced_location_accuracy 0 2>/dev/null || true",
            *disableCmds.toTypedArray()
        ).exec().isSuccess
    }

    /**
     * 恢复系统网络定位提供者(NLP)。
     * 在模拟定位停止时调用。
     */
    suspend fun restoreNlp(): Boolean = withContext(Dispatchers.IO) {
        val nlpPackages = listOf(
            "com.google.android.gms.location.nlp",
            "com.google.android.location",
            "com.amap.android.location",
            "com.amap.android.ams",
            "com.baidu.map.location",
            "com.tencent.android.location",
            "com.huawei.lbs",
            "com.huawei.location",
            "com.xiaomi.metoknlp",
            "com.coloros.pcmcs",
            "com.coloros.location",
            "com.oplus.location",
            "com.vivo.lbs"
        )
        val enableCmds = nlpPackages.map { "pm enable $it 2>/dev/null || true" }
        Shell.cmd(
            "settings put secure location_mode 3",
            "settings put secure location_providers_allowed +network",
            "settings put global assisted_gps_enabled 1",
            "settings put global location_accuracy_enabled 1 2>/dev/null || true",
            "settings put secure enhanced_location_accuracy 1 2>/dev/null || true",
            *enableCmds.toTypedArray()
        ).exec().isSuccess
    }

    suspend fun disableFakeAirplaneMode(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd(
            "settings put global airplane_mode_radios \"cell,bluetooth,wifi,nfc,wimax\"",
            "settings put global airplane_mode_toggleable_radios \"bluetooth,wifi,nfc\""
        ).exec().isSuccess
    }

    suspend fun killAllUserApps(): Boolean = withContext(Dispatchers.IO) {
        val listResult = Shell.cmd("pm list packages -3").exec()
        if (!listResult.isSuccess) return@withContext false
        listResult.out
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && it != "com.suseoaa.locationspoofer" }
            .forEach { pkg -> Shell.cmd("am force-stop $pkg").exec() }
        true
    }

    /**
     * 通用 root 命令执行（供 ConfigManager 使用）。
     * 多行脚本按行分割后逐条在同一 shell 会话中执行。
     */
    fun executeCommand(command: String, timeoutMs: Long = 8_000L): String {
        val lines = command.trim().lines().filter { it.isNotBlank() }
        val result = Shell.cmd(*lines.toTypedArray()).exec()
        return if (result.isSuccess) result.out.joinToString("\n").ifEmpty { "SUCCESS" }
        else "ERROR"
    }
}
