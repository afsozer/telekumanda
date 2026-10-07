package com.agent.bridge

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Tek seferlik göç: eski indirilenler `/sdcard/Download/AgentBridge` altındaydı,
 * yeni düzen `/sdcard/AgentBridge`. Eskiler `old downloads` klasörüne taşınır.
 *
 * Neden taşıyoruz: `/sdcard/AgentBridge` standart bir MediaStore koleksiyonu
 * değil, oraya `RELATIVE_PATH` ile yazılamıyor — yeni düzen doğrudan dosya
 * erişimi (MANAGE_EXTERNAL_STORAGE) gerektiriyor, eskiyi de yanına almak
 * gerekiyor ki iki ayrı yerde dosya aramayalım.
 *
 * Göçten sonra eski MediaStore kayıtlarının `localUri`'leri geçersizleşir; bu
 * yüzden indirilenler geçmişi de temizlenir. Dosyalar kaybolmaz, yeni telefon
 * gezgininde `old downloads` klasöründe durur.
 */
object PhoneStorageMigration {

    private const val PREFS = "phone_storage"
    private const val KEY_DONE = "old_downloads_migrated_v1"

    fun legacyDownloadsDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AgentBridge")

    /**
     * Gerekliyse taşır. Dönüş: taşınan üst düzey öğe sayısı (0 = yapacak iş yoktu).
     * İzin yoksa hiçbir şey yapmaz ve bayrağı da yakmaz — izin verilince tekrar denenir.
     */
    fun migrateIfNeeded(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return 0
        if (!PhoneStorage.hasPermission()) return 0

        val legacy = legacyDownloadsDir()
        if (!legacy.isDirectory) {
            // Taşınacak bir şey yok; bir daha bakmayalım.
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return 0
        }

        val hedef = File(PhoneStorage.oldDownloadsRoot())
        if (!PhoneFiles.ensureDir(hedef.absolutePath)) return 0

        var tasinan = 0
        // Klasörü toptan değil TEK TEK taşıyoruz: bir dosya kilitliyse ya da ad
        // çakışıyorsa geri kalanı yine de kurtaralım.
        legacy.listFiles()?.forEach { kaynak ->
            val varis = PhoneFiles.uniqueChild(hedef.absolutePath, kaynak.name)
            if (kaynak.renameTo(varis)) {
                tasinan++
            } else if (runCatching { kaynak.copyRecursively(varis, overwrite = false) }.getOrDefault(false)) {
                kaynak.deleteRecursively()
                tasinan++
            }
        }

        // Boşaldıysa eski klasörü de kaldır (dolu kaldıysa dokunma, iz kalsın).
        if (legacy.listFiles()?.isEmpty() == true) legacy.delete()

        prefs.edit().putBoolean(KEY_DONE, true).apply()
        return tasinan
    }
}
