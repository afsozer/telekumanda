package com.agent.bridge

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Telefon deposunun Android'e bakan yüzü: kök çözümü, "tüm dosyalara erişim"
 * izni ve MediaStore'a haber verme. Dosya işlemlerinin kendisi [PhoneFiles]'ta
 * (saf JVM, test edilebilir).
 */
object PhoneStorage {

    /** /sdcard (kullanıcıya görünen dış depolama kökü). */
    fun externalRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** /sdcard/AgentBridge */
    fun agentBridgeRoot(): String = File(externalRoot(), PhoneFiles.ROOT_NAME).absolutePath

    fun coworkRoot(): String = File(agentBridgeRoot(), PhoneFiles.COWORK_DIR).absolutePath

    fun picturesRoot(): String = File(agentBridgeRoot(), PhoneFiles.PICTURES_DIR).absolutePath

    fun oldDownloadsRoot(): String = File(agentBridgeRoot(), PhoneFiles.OLD_DOWNLOADS_DIR).absolutePath

    /**
     * Android 11+ için "tüm dosyalara erişim". Altındaki sürümlerde eski
     * WRITE_EXTERNAL_STORAGE zaten yeterli, sormaya gerek yok.
     */
    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * İzni açtıran Ayarlar ekranı. Normal izin diyaloguyla verilemez.
     * Bazı üretici kabuklarında (Honor MagicOS) uygulamaya özel ekran açılmaz —
     * o yüzden genel listeye düşen bir yedek intent de veriyoruz.
     */
    fun requestIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
    }

    /** İskeleti hazırlar. İzin yoksa sessizce false döner. */
    fun ensureSkeleton(): Boolean {
        if (!hasPermission()) return false
        return PhoneFiles.ensureDir(agentBridgeRoot()) &&
            PhoneFiles.ensureDir(coworkRoot()) &&
            PhoneFiles.ensureDir(picturesRoot())
    }

    /**
     * Doğrudan yazılan dosyayı MediaStore'a bildirir. Bu çağrılmazsa görsel
     * Galeri'de görünmez ("kaydettim ama yok" hissi) — /sdcard/AgentBridge
     * standart bir koleksiyon olmadığı için tarayıcı kendiliğinden uğramıyor.
     */
    fun notifyMediaScanner(context: Context, path: String) = notifyMediaScanner(context, listOf(path))

    fun notifyMediaScanner(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext, paths.toTypedArray(), null, null,
            )
        }
    }
}
