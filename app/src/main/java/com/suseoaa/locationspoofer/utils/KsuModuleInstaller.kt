package com.suseoaa.locationspoofer.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自动安装内置的 KernelSU 模块。
 * 模块功能：iptables 阻断 SUPL + 覆盖 gps.conf
 */
class KsuModuleInstaller(private val rootManager: RootManager) {

    companion object {
        private const val TAG = "KsuModuleInstaller"
        private const val MODULE_ID = "location_spoofer_defense"
        private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    }

    data class InstallResult(
        val installed: Boolean,
        val needReboot: Boolean,
        val message: String
    )

    /**
     * 检查模块是否已安装：读取 module.prop 并验证内容
     */
    suspend fun isModuleInstalled(): Boolean = withContext(Dispatchers.IO) {
        val result = rootManager.executeCommand("cat $MODULE_DIR/module.prop")
        Log.d(TAG, "isModuleInstalled check: '$result'")
        val installed = result != "ERROR" && result.isNotEmpty() && result.contains("location_spoofer_defense")
        Log.d(TAG, "isModuleInstalled: $installed")
        installed
    }

    /**
     * 从 app assets 安装 KernelSU 模块。
     * 策略：先把 assets 写到 app 内部存储，再用 su cp 到 /data/adb/modules/。
     * 避免 echo '...' | base64 -d 管道在 su stdin 下不可靠的问题。
     */
    suspend fun install(context: Context): InstallResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "========== INSTALL KSU MODULE ==========")

        // 检查 root
        val hasRoot = rootManager.checkRootAccess()
        if (!hasRoot) {
            Log.e(TAG, "No root access")
            return@withContext InstallResult(false, false, "无 Root 权限")
        }

        // 检查是否已安装
        if (isModuleInstalled()) {
            Log.d(TAG, "Module already installed")
            return@withContext InstallResult(true, false, "模块已安装")
        }

        // 清理可能残留的空目录（之前安装失败留下的）
        Log.d(TAG, "Cleaning up possible stale module dir...")
        rootManager.executeCommand("rm -rf $MODULE_DIR")

        try {
            // Step 1: 把 assets 文件先写到 app 内部存储
            val tmpDir = File(context.filesDir, "ksu_tmp")
            tmpDir.mkdirs()

            val assetFiles = listOf(
                "ksu_module/module.prop",
                "ksu_module/service.sh",
                "ksu_module/system/vendor/etc/gps.conf",
                "ksu_module/sepolicy.rule"
            )

            for (assetPath in assetFiles) {
                try {
                    val content = context.assets.open(assetPath).bufferedReader().readText()
                    val relativePath = assetPath.removePrefix("ksu_module/")
                    val tmpFile = File(tmpDir, relativePath)
                    tmpFile.parentFile?.mkdirs()
                    tmpFile.writeText(content)
                    Log.d(TAG, "Wrote tmp: ${tmpFile.absolutePath} (${content.length} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Optional asset not found: $assetPath")
                }
            }

            // Step 2: 用 su 把文件复制到 KSU 模块目录
            Log.d(TAG, "Creating module directories...")
            rootManager.executeCommand("mkdir -p $MODULE_DIR/system/vendor/etc")
            rootManager.executeCommand("mkdir -p $MODULE_DIR/system/vendor/lib64/hw")
            rootManager.executeCommand("mkdir -p $MODULE_DIR/system/vendor/lib/hw")
            rootManager.executeCommand("mkdir -p $MODULE_DIR/bin")

            // 逐个复制文件
            val copyPairs = mutableListOf(
                File(tmpDir, "module.prop").absolutePath to "$MODULE_DIR/module.prop",
                File(tmpDir, "service.sh").absolutePath to "$MODULE_DIR/service.sh",
                File(tmpDir, "system/vendor/etc/gps.conf").absolutePath to "$MODULE_DIR/system/vendor/etc/gps.conf",
                File(tmpDir, "sepolicy.rule").absolutePath to "$MODULE_DIR/sepolicy.rule"
            )

            // Copy daemon from nativeLibraryDir
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val daemonSo = File(nativeLibDir, "libgnss_spoof_daemon.so")
            if (daemonSo.exists()) {
                copyPairs.add(daemonSo.absolutePath to "$MODULE_DIR/bin/gnss_spoof_daemon")
            } else {
                Log.e(TAG, "Daemon native library not found at ${daemonSo.absolutePath}")
            }

            for ((src, dst) in copyPairs) {
                // cp 命令通过 su stdin 执行，比 echo | base64 更可靠
                if (File(src).exists() || src.startsWith(nativeLibDir)) {
                    val cpCmd = "cp '$src' '$dst' && chmod 644 '$dst'"
                    val cpResult = rootManager.executeCommand(cpCmd)
                    Log.d(TAG, "  cp $src -> $dst : '$cpResult'")
                }
            }

            // 设置可执行权限
            rootManager.executeCommand("chmod 755 $MODULE_DIR/service.sh")
            rootManager.executeCommand("chmod 755 $MODULE_DIR/bin/gnss_spoof_daemon")

            // 确保没有 disable 标记
            rootManager.executeCommand("rm -f $MODULE_DIR/disable")

            // 清理临时文件
            tmpDir.deleteRecursively()

            // Step 3: 验证安装 — 读回 module.prop 内容确认
            val verifyProp = rootManager.executeCommand("cat $MODULE_DIR/module.prop")
            Log.d(TAG, "Verify module.prop: '$verifyProp'")

            val verifyService = rootManager.executeCommand("ls -la $MODULE_DIR/service.sh")
            Log.d(TAG, "Verify service.sh: '$verifyService'")

            val verifyConf = rootManager.executeCommand("ls -la $MODULE_DIR/system/vendor/etc/gps.conf")
            Log.d(TAG, "Verify gps.conf: '$verifyConf'")

            if (verifyProp.contains("ERROR") || !verifyProp.contains("location_spoofer_defense")) {
                Log.e(TAG, "Verification FAILED: module.prop not found or incorrect")
                return@withContext InstallResult(false, false, "模块安装失败：文件未写入")
            }

            Log.d(TAG, "========== MODULE INSTALLED ==========")
            return@withContext InstallResult(true, true, "模块安装成功，需要重启生效")

        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}", e)
            return@withContext InstallResult(false, false, "安装失败: ${e.message}")
        }
    }

    /**
     * 卸载模块
     */
    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Uninstalling module...")
        val result = rootManager.executeCommand("rm -rf $MODULE_DIR")
        Log.d(TAG, "Uninstall result: $result")
        result != "ERROR"
    }
}
