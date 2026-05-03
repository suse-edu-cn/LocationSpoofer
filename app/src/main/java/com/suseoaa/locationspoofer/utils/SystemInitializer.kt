package com.suseoaa.locationspoofer.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统初始化器：检测 root / LSPosed 状态，尝试自行修复，给出明确反馈。
 */
class SystemInitializer(
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val ksuModuleInstaller: KsuModuleInstaller
) {
    companion object {
        private const val TAG = "SystemInitializer"
    }

    data class InitResult(
        val hasRoot: Boolean,
        val isLSPosedActive: Boolean,
        val configWritten: Boolean,
        val message: String,
        val needReboot: Boolean = false
    )

    /**
     * 完整初始化流程：
     * 1. 检测 LSPosed 模块是否激活
     * 2. 检测 root 权限
     * 3. 如果有 root：禁用真实定位提供者、清除定位缓存、写入配置文件
     * 4. 如果没有 root：尝试自行修复
     * 5. 返回初始化结果
     */
    suspend fun initialize(context: Context): InitResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "========== INITIALIZE START ==========")
        val messages = mutableListOf<String>()
        var needReboot = false

        // 1. 检测 LSPosed
        Log.d(TAG, "[Step 1] Checking LSPosed module status...")
        val lsposed = lsposedManager.isModuleActive()
        if (lsposed) {
            messages.add("LSPosed 模块已激活")
            Log.d(TAG, "[Step 1] LSPosed: ACTIVE")
        } else {
            messages.add("LSPosed 模块未激活")
            Log.w(TAG, "[Step 1] LSPosed: NOT ACTIVE - please enable in LSPosed Manager")
        }

        // 2. 检测 root
        Log.d(TAG, "[Step 2] Checking root access...")
        val root = rootManager.checkRootAccess()
        Log.d(TAG, "[Step 2] Root access: $root")
        var configWritten = false

        if (root) {
            messages.add("Root 权限已获取")
            Log.d(TAG, "[Step 2] Root: GRANTED")

            // 3a. 授予 mock_location 权限
            Log.d(TAG, "[Step 3a] Granting mock_location permission...")
            val mockGranted = rootManager.grantMockLocation()
            Log.d(TAG, "[Step 3a] mock_location granted=$mockGranted")
            if (mockGranted) {
                messages.add("模拟定位权限已授予")
            } else {
                messages.add("模拟定位权限授予失败")
            }

            // 3b. 授予关键应用的定位权限（修复被撤销的权限）
            Log.d(TAG, "[Step 3b] Granting location permissions to key apps...")
            rootManager.grantLocationPermissionsToAll()
            messages.add("定位权限已授予关键应用")

            // 3c. 安装 KernelSU 防御模块（iptables 阻断 SUPL + gps.conf）
            Log.d(TAG, "[Step 3c] Installing KSU defense module...")
            val ksuResult = ksuModuleInstaller.install(context)
            Log.d(TAG, "[Step 3c] KSU module: installed=${ksuResult.installed} needReboot=${ksuResult.needReboot} msg=${ksuResult.message}")
            messages.add(ksuResult.message)
            if (ksuResult.needReboot) needReboot = true

            // 3d. 写入配置文件
            Log.d(TAG, "[Step 3c] Writing config file...")
            configWritten = writeConfigFile(context)
            Log.d(TAG, "[Step 3c] configWritten=$configWritten")
            if (configWritten) {
                messages.add("配置文件已写入")
            }

            // 3d. 清除定位缓存
            Log.d(TAG, "[Step 3d] Clearing location cache...")
            val clearResult = rootManager.executeCommand("cmd location providers send-extra-command gps android:clear_location_data")
            Log.d(TAG, "[Step 3c] clear cache result: '$clearResult'")

            // 不禁用定位服务 — 完全依赖 Xposed hook 拦截所有定位数据

        } else {
            messages.add("未获取 Root 权限")
            Log.w(TAG, "[Step 2] Root: DENIED")

            // 4. 诊断
            Log.d(TAG, "[Step 4] Diagnosing root issue...")
            val diagnosis = diagnoseRootIssue()
            Log.d(TAG, "[Step 4] Diagnosis: $diagnosis")
            messages.add(diagnosis)
            if (diagnosis.contains("KernelSU Manager")) {
                needReboot = true
            }
        }

        val summary = messages.joinToString("\n")
        Log.d(TAG, "========== INITIALIZE RESULT ==========")
        Log.d(TAG, "  root=$root lsposed=$lsposed config=$configWritten needReboot=$needReboot")
        Log.d(TAG, "  summary: $summary")
        Log.d(TAG, "========== INITIALIZE END ==========")

        InitResult(
            hasRoot = root,
            isLSPosedActive = lsposed,
            configWritten = configWritten,
            message = summary,
            needReboot = needReboot
        )
    }

    /**
     * 写入配置文件到多个位置
     */
    private suspend fun writeConfigFile(context: Context): Boolean {
        Log.d(TAG, "writeConfigFile START")
        val json = org.json.JSONObject().apply {
            put("lat", 0.0)
            put("lng", 0.0)
            put("active", false)
        }
        val jsonStr = json.toString()
        val base64 = android.util.Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

        // 写入 /data/local/tmp/（需要 root）
        Log.d(TAG, "writeConfigFile: writing to /data/local/tmp/")
        val r1 = rootManager.executeCommand("echo '$base64' | base64 -d > /data/local/tmp/locationspoofer_config.json && chmod 644 /data/local/tmp/locationspoofer_config.json")
        Log.d(TAG, "writeConfigFile: /data/local/tmp/ result='$r1'")

        // 写入 app 内部存储
        return try {
            context.openFileOutput("locationspoofer_config.json", Context.MODE_PRIVATE).use {
                it.write(jsonStr.toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "writeConfigFile: internal storage OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeConfigFile: internal storage FAILED: ${e.message}")
            r1 != "ERROR"
        }
    }

    /**
     * 诊断 root 问题并给出具体建议
     */
    private suspend fun diagnoseRootIssue(): String {
        Log.d(TAG, "diagnoseRootIssue START")
        val suPaths = listOf(
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su"
        )

        var foundSu: String? = null
        for (path in suPaths) {
            Log.d(TAG, "diagnoseRootIssue: checking $path")
            val exists = rootManager.executeCommand("ls -la $path")
            Log.d(TAG, "diagnoseRootIssue: $path → '$exists'")
            if (exists != "ERROR" && exists.isNotEmpty()) {
                foundSu = path
                break
            }
        }

        val whichSu = rootManager.executeCommand("which su")
        Log.d(TAG, "diagnoseRootIssue: which su → '$whichSu'")

        val result = when {
            foundSu != null -> {
                "找到 su: $foundSu，但权限不足。请在 KernelSU Manager 中授权本应用后重启。"
            }
            whichSu != "ERROR" && whichSu.isNotEmpty() && !whichSu.contains("not found") -> {
                "找到 su: $whichSu，但无法执行。可能是 SELinux 限制。"
            }
            else -> {
                "未找到 su 二进制。请确认 KernelSU 已正确安装。"
            }
        }
        Log.d(TAG, "diagnoseRootIssue END: $result")
        return result
    }

    /**
     * 在主线程显示 Toast
     */
    fun showToast(context: Context, message: String) {
        Log.d(TAG, "showToast: $message")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 重启系统（需要 root）
     */
    suspend fun rebootSystem(): Boolean {
        Log.d(TAG, "rebootSystem: executing 'svc power reboot'")
        val result = rootManager.executeCommand("svc power reboot")
        Log.d(TAG, "rebootSystem: result='$result'")
        return result != "ERROR"
    }
}
