package com.agent.bridge

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * İki tek seferlik göç:
 *
 * 1. Kök adı: uygulama AgentBridge'ken kök `/sdcard/AgentBridge`'ti, ad
 *    Telekumanda olunca `/sdcard/Telekumanda` oldu. Eski kökün içi yeni köke
 *    taşınır ([renameRootIfNeeded]).
 * 2. Eski indirilenler `/sdcard/Download/AgentBridge` altındaydı, yeni düzen
 *    kökün kendisi. Eskiler `old downloads` klasörüne taşınır.
 *
 * Neden taşıyoruz (2): `/sdcard/<kök>` standart bir MediaStore koleksiyonu
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
    private const val KEY_ROOT_RENAMED = "root_renamed_telekumanda_v1"

    fun legacyDownloadsDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PhoneFiles.LEGACY_ROOT_NAME)

    /** /sdcard/Telekumanda — uygulamanın eski adıyla kullanılan kök. */
    fun legacyRootDir(): File =
        File(PhoneStorage.externalRoot(), PhoneFiles.LEGACY_ROOT_NAME)

    /**
     * Gerekliyse taşır. Dönüş: taşınan üst düzey öğe sayısı (0 = yapacak iş yoktu).
     * İzin yoksa hiçbir şey yapmaz ve bayrağı da yakmaz — izin verilince tekrar denenir.
     */
    fun migrateIfNeeded(context: Context): Int =
        renameRootIfNeeded(context) + migrateOldDownloadsIfNeeded(context)

    /**
     * Uygulamanın adı Telekumanda olunca kök de `/sdcard/Telekumanda`'ten
     * `/sdcard/Telekumanda`'ya geçti. Eski kökün İÇİNDEKİLER yeni köke taşınır;
     * klasörü toptan yeniden adlandırmıyoruz çünkü yeni kök bu noktada
     * [PhoneStorage.ensureSkeleton] tarafından zaten kurulmuş oluyor.
     *
     * Dönüş: taşınan üst düzey öğe sayısı (0 = yapacak iş yoktu).
     */
    fun renameRootIfNeeded(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ROOT_RENAMED, false)) return 0
        if (!PhoneStorage.hasPermission()) return 0

        val legacy = legacyRootDir()
        val hedef = File(PhoneStorage.agentBridgeRoot())
        if (!legacy.isDirectory || legacy.absolutePath == hedef.absolutePath) {
            prefs.edit().putBoolean(KEY_ROOT_RENAMED, true).apply()
            return 0
        }
        if (!PhoneFiles.ensureDir(hedef.absolutePath)) return 0

        var tasinan = 0
        legacy.listFiles()?.forEach { kaynak ->
            val varis = File(hedef, kaynak.name)
            val ok = when {
                // İskelet yüzünden iki tarafta da duran klasörler (CoworkSpaces,
                // Pictures) BİRLEŞTİRİLİR; uniqueChild burada "CoworkSpaces (1)"
                // üretip dosyaları ikinci bir yere saklardı.
                varis.isDirectory && kaynak.isDirectory -> birlestir(kaynak, varis)
                // Adı tutan bir DOSYA varsa üzerine yazmıyoruz, yanına koyuyoruz.
                varis.exists() -> kaynak.renameTo(PhoneFiles.uniqueChild(hedef.absolutePath, kaynak.name))
                else -> kaynak.renameTo(varis) || birlestir(kaynak, varis)
            }
            if (ok) tasinan++
        }

        if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
        prefs.edit().putBoolean(KEY_ROOT_RENAMED, true).apply()
        return tasinan
    }

    /** Kopyala-sonra-sil: `renameTo` farklı birim/dolu hedefte başarısız olur. */
    private fun birlestir(kaynak: File, varis: File): Boolean {
        val kopyalandi = runCatching { kaynak.copyRecursively(varis, overwrite = false) }.getOrDefault(false)
        if (kopyalandi) kaynak.deleteRecursively()
        return kopyalandi
    }

    private fun migrateOldDownloadsIfNeeded(context: Context): Int {
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
