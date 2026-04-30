package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RootManager {

    suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("id").contains("uid=0")
    }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("appops set com.suseoaa.locationspoofer android:mock_location allow") != "ERROR"
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        executeCommand("appops set com.suseoaa.locationspoofer android:mock_location deny") != "ERROR"
    }

    fun executeCommand(command: String, timeoutMs: Long = 8000L): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val outputFuture = executor.submit<String> {
                BufferedReader(InputStreamReader(process.inputStream)).readText()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                executor.shutdownNow()
                return "TIMEOUT"
            }
            val output = try {
                outputFuture.get(500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) { "" }
            executor.shutdown()
            output.ifEmpty { "SUCCESS" }
        } catch (e: Exception) {
            "ERROR"
        }
    }
}
