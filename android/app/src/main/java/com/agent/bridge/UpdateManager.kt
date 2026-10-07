package com.agent.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// UpdateInfo shared modülünde (UpdateInfo.kt).

class AndroidUpdateManager(private val context: Context) : UpdateManager {
    private val channel = if (BuildConfig.IS_LITE) UpdateChannel.LITE else UpdateChannel.STANDARD
    // Tailscale cold-start (phone WiFi waking + NAT punchthrough) can take >5s on first
    // packet, so give the initial connect a generous window before reporting failure.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun checkUpdate(settings: BridgeSettings): UpdateInfo? = withContext(Dispatchers.IO) {
        val response = client.newCall(buildRequest(settings, normalizeBase(settings.baseUrl) + channel.manifestPath)).await()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val json = JSONObject(it.body?.string().orEmpty())
            channel.validateManifest(json.optString("applicationId"), json.optString("apkPath"), context.packageName)
            val versionCode = json.getInt("versionCode")
            if (versionCode <= BuildConfig.VERSION_CODE) return@withContext null
            val apkPath = json.getString("apkPath")
            UpdateInfo(
                versionCode = versionCode,
                versionName = json.optString("versionName", versionCode.toString()),
                notes = json.optString("notes"),
                apkUrl = normalizeBase(settings.baseUrl) + apkPath,
            )
        }
    }

    override suspend fun downloadApk(
        settings: BridgeSettings,
        apkUrl: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        onProgress(0f)
        val response = client.newCall(buildRequest(settings, apkUrl)).await()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val body = it.body ?: throw IOException("Empty response body")
            val target = File(context.cacheDir, "update.apk")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val total = body.contentLength()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
            target
        }
    }

    override fun installApk(file: File): Boolean {
        if (BuildConfig.IS_LITE) {
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IOException("İndirilen APK okunamadı")
            if (archive.packageName != context.packageName) throw IOException("APK Lite uygulamasına ait değil")
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
                else @Suppress("DEPRECATION") archive.versionCode.toLong()
            if (version <= BuildConfig.VERSION_CODE) throw IOException("APK daha yeni bir Lite sürümü değil")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun buildRequest(settings: BridgeSettings, url: String): Request {
        val builder = Request.Builder().url(url)
        if (settings.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.token}")
        }
        return builder.get().build()
    }

    private fun normalizeBase(url: String): String = url.trim().trimEnd('/')
}
