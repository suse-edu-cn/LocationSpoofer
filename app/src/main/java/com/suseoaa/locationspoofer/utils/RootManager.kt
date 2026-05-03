package com.suseoaa.locationspoofer.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

class RootManager {

    companion object {
        private const val TAG = "RootManager"
    }

    private var savedProviders: String = "gps,network"

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val result = su("id")
        val hasRoot = result.output.contains("uid=0")
        Log.d(TAG, "checkRootAccess: output='${result.output.take(100)}' exit=${result.exitCode} hasRoot=$hasRoot")
        hasRoot
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result = su("appops set com.suseoaa.locationspoofer android:mock_location allow")
        Log.d(TAG, "grantMockLocation: exit=${result.exitCode} output='${result.output}'")
        result.exitCode == 0
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result = su("appops set com.suseoaa.locationspoofer android:mock_location deny")
        Log.d(TAG, "revokeMockLocation: exit=${result.exitCode} output='${result.output}'")
        result.exitCode == 0
    }

    /**
     * 通过 root 授予指定应用的定位权限（appops + pm grant）
     */
    suspend fun grantLocationPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "grantLocationPermissions: $packageName")
        // appops 方式
        val r1 = su("appops set $packageName android:coarse_location allow")
        Log.d(TAG, "  appops coarse_location: exit=${r1.exitCode}")
        val r2 = su("appops set $packageName android:fine_location allow")
        Log.d(TAG, "  appops fine_location: exit=${r2.exitCode}")
        // pm grant 方式（双重保障）
        val r3 = su("pm grant $packageName android.permission.ACCESS_FINE_LOCATION")
        Log.d(TAG, "  pm grant FINE: exit=${r3.exitCode}")
        val r4 = su("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION")
        Log.d(TAG, "  pm grant COARSE: exit=${r4.exitCode}")
        val ok = r2.exitCode == 0 || r3.exitCode == 0
        Log.d(TAG, "grantLocationPermissions $packageName: $ok")
        ok
    }

    /**
     * 批量授予关键应用的定位权限
     */
    suspend fun grantLocationPermissionsToAll() = withContext(Dispatchers.IO) {
        val packages = listOf(
            "com.autonavi.minimap",
            "com.suseoaa.locationspoofer",
            "com.baidu.BaiduMap",
            "com.tencent.map"
        )
        for (pkg in packages) {
            grantLocationPermissions(pkg)
        }
    }

    suspend fun disableRealProviders() = withContext(Dispatchers.IO) {
        Log.d(TAG, "===== disableRealProviders START =====")

        // Step 1: 保存当前值
        val current = su("settings get secure location_providers_allowed")
        Log.d(TAG, "[1] get providers: exit=${current.exitCode} output='${current.output}'")
        if (current.exitCode == 0 && current.output.isNotEmpty() &&
            current.output != "null" && current.output != "ERROR") {
            savedProviders = current.output
            Log.d(TAG, "[1] saved providers='$savedProviders'")
        }

        // Step 2: 关闭定位模式 (LOCATION_MODE_OFF = 0)
        val r0 = su("settings put secure location_mode 0")
        Log.d(TAG, "[2] location_mode=0: exit=${r0.exitCode} output='${r0.output}'")

        // Step 3: 清空 providers 列表
        val r1 = su("settings put secure location_providers_allowed ''")
        Log.d(TAG, "[3] providers='': exit=${r1.exitCode} output='${r1.output}'")

        // Step 4: cmd location providers disable（可能不支持）
        val r2 = su("cmd location providers disable gps")
        Log.d(TAG, "[4] cmd disable gps: exit=${r2.exitCode} output='${r2.output}'")
        val r3 = su("cmd location providers disable network")
        Log.d(TAG, "[5] cmd disable network: exit=${r3.exitCode} output='${r3.output}'")

        // Step 5: 清除定位缓存
        val r4 = su("cmd location providers send-extra-command gps android:clear_location_data")
        Log.d(TAG, "[6] clear cache: exit=${r4.exitCode} output='${r4.output}'")

        // Step 6: 关闭 GPS 硬件
        val r5 = su("settings put global gps_enabled 0")
        Log.d(TAG, "[7] gps_enabled=0: exit=${r5.exitCode} output='${r5.output}'")

        // Step 7: 如果 cmd disable 失败，从 providers 列表中精确移除
        if (r2.output.contains("Unknown command") || r2.exitCode == 255) {
            Log.w(TAG, "[8] 'cmd location providers disable' not supported, filtering providers list...")
            val cur = su("settings get secure location_providers_allowed")
            Log.d(TAG, "[8] current providers: '${cur.output}'")
            if (cur.exitCode == 0 && cur.output.isNotEmpty() && cur.output != "null") {
                val filtered = cur.output
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "gps" && it != "network" }
                    .joinToString(",")
                val r6 = su("settings put secure location_providers_allowed '$filtered'")
                Log.d(TAG, "[8] filtered providers='$filtered': exit=${r6.exitCode} output='${r6.output}'")
            }
        }

        // Step 8: 验证 — 读取当前 location_mode 确认关闭
        val verify = su("settings get secure location_mode")
        Log.d(TAG, "[9] VERIFY location_mode: exit=${verify.exitCode} output='${verify.output}'")

        Log.d(TAG, "===== disableRealProviders END =====")
    }

    suspend fun enableRealProviders() = withContext(Dispatchers.IO) {
        Log.d(TAG, "===== enableRealProviders START: saved='$savedProviders' =====")
        // 恢复定位模式 (LOCATION_MODE_HIGH_ACCURACY = 3)
        val r0 = su("settings put secure location_mode 3")
        Log.d(TAG, "[0] location_mode=3: exit=${r0.exitCode} output='${r0.output}'")

        if (savedProviders.isNotEmpty()) {
            val r1 = su("settings put secure location_providers_allowed '$savedProviders'")
            Log.d(TAG, "[1] restore providers: exit=${r1.exitCode} output='${r1.output}'")
        }
        val r2 = su("settings put global gps_enabled 1")
        Log.d(TAG, "[2] gps_enabled=1: exit=${r2.exitCode} output='${r2.output}'")
        val r3 = su("cmd location providers enable gps")
        Log.d(TAG, "[3] enable gps: exit=${r3.exitCode} output='${r3.output}'")
        val r4 = su("cmd location providers enable network")
        Log.d(TAG, "[4] enable network: exit=${r4.exitCode} output='${r4.output}'")
        Log.d(TAG, "===== enableRealProviders END =====")
    }

    /**
     * 通过 su 以 root 身份执行命令。
     * 每次单独启动 su 进程，通过 stdin 写入单条命令。
     */
    fun su(command: String): SuResult {
        fun readStream(stream: java.io.InputStream): String {
            return try {
                stream.bufferedReader().readText().trim()
            } catch (_: Exception) { "" }
        }

        // 方式1: 直接 exec("su")，通过 stdin 管道传入单条命令
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val errThread = Thread { readStream(process.errorStream) }
            errThread.start()
            val output = readStream(process.inputStream)
            errThread.join(3000)
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.isNotEmpty()) {
                Log.d(TAG, "su stdin OK: exit=$exitCode len=${output.length} cmd='${command.take(60)}'")
                return SuResult(exitCode, output)
            }
            Log.w(TAG, "su stdin fail: exit=$exitCode output='$output' cmd='${command.take(60)}'")
        } catch (e: Exception) {
            Log.w(TAG, "su stdin exception: ${e.message} cmd='${command.take(60)}'")
        }

        // 方式2: ProcessBuilder("su", "-c", command)
        try {
            val pb = ProcessBuilder("su", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = readStream(process.inputStream)
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.isNotEmpty()) {
                Log.d(TAG, "su -c OK: exit=$exitCode len=${output.length} cmd='${command.take(60)}'")
                return SuResult(exitCode, output)
            }
            Log.w(TAG, "su -c fail: exit=$exitCode output='$output' cmd='${command.take(60)}'")
        } catch (e: Exception) {
            Log.w(TAG, "su -c exception: ${e.message} cmd='${command.take(60)}'")
        }

        // 方式3: 通过 sh 启动 su
        try {
            val pb = ProcessBuilder("sh", "-c", "su")
            pb.redirectErrorStream(true)
            val process = pb.start()
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            val output = readStream(process.inputStream)
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.isNotEmpty()) {
                Log.d(TAG, "sh su OK: exit=$exitCode len=${output.length} cmd='${command.take(60)}'")
                return SuResult(exitCode, output)
            }
            Log.w(TAG, "sh su fail: exit=$exitCode output='$output' cmd='${command.take(60)}'")
        } catch (e: Exception) {
            Log.w(TAG, "sh su exception: ${e.message} cmd='${command.take(60)}'")
        }

        Log.e(TAG, "ALL su methods FAILED for: ${command.take(80)}")
        return SuResult(-1, "ERROR")
    }

    /**
     * 便捷方法：执行 su 命令并返回输出字符串。
     * 失败时返回 "ERROR"。
     */
    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val result = su(command)
        if (result.exitCode == 0 || result.output.isNotEmpty()) {
            result.output
        } else {
            "ERROR"
        }
    }

    data class SuResult(val exitCode: Int, val output: String)
}
