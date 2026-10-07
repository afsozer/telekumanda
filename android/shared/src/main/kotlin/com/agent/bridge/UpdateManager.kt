package com.agent.bridge

import java.io.File

/**
 * OTA güncelleme yöneticisi için platform arayüzü (Faz 1).
 *
 * - Android gerçeklemesi: AndroidUpdateManager (APK indir + installer intent — app)
 *
 * Tek gerçekleme Android'dedir. (Masaüstü jpackage/MSI kanalı 10.08.2026'da
 * kaldırıldı: Compose Desktop istemcisinin yerini köprünün servis ettiği
 * tarayıcı arayüzü aldı ve onun güncelleme kanalına ihtiyacı yok — sayfa
 * yenilendiğinde son sürüm gelir.)
 *
 * UpdateDelegate yalnız bu arayüzü bilir.
 */
interface UpdateManager {
    suspend fun checkUpdate(settings: BridgeSettings): UpdateInfo?
    suspend fun downloadApk(settings: BridgeSettings, apkUrl: String, sha256: String, onProgress: (Float) -> Unit): File
    fun installApk(file: File): Boolean
}
