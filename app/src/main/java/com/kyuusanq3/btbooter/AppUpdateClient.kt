package com.kyuusanq3.btbooter

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal sealed class UpdateCheck {
    data object UpToDate : UpdateCheck()
    data class Available(val versionName: String) : UpdateCheck()
}

internal class AppUpdateClient(context: Context) {
    private val appContext = context.applicationContext

    fun checkForUpdate(): UpdateCheck {
        val connection = open(RELEASES_API_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Update check failed: HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val remote = json.getString("tag_name")
            val local = BuildConfig.VERSION_NAME
            return if (isNewerVersion(remote, local)) {
                UpdateCheck.Available(remote.trim().trimStart('v', 'V'))
            } else {
                UpdateCheck.UpToDate
            }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadApk(onProgress: (Int) -> Unit): File {
        val connection = open(APK_URL)
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed: HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            val target = updateApkFile(appContext)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.delete()
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (totalBytes > 0L) {
                            val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    companion object {
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/kyuusanq3/bt-booter/releases/latest"
        private const val APK_URL =
            "https://github.com/kyuusanq3/bt-booter/releases/latest/download/bt-booter.apk"
        private const val USER_AGENT = "BTBooter/1.0"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 300_000
    }
}

internal fun updateApkFile(context: Context): File {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    return File(dir, "bt-booter.apk")
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = remote.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
    val localParts = local.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
    val length = maxOf(remoteParts.size, localParts.size)
    for (index in 0 until length) {
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val localPart = localParts.getOrElse(index) { 0 }
        if (remotePart > localPart) return true
        if (remotePart < localPart) return false
    }
    return false
}

internal fun logUpdateFailure(message: String, error: Exception) {
    Log.w("BtBooter", message, error)
}
