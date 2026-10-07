package com.agent.bridge

/**
 * İndirme önbelleğinde tutulacak dosya sayısı kararı — saf (Android'siz) kısım.
 *
 * Neden gerekli: açılan her uzak PDF telefona iniyor ve hiç silinmiyordu. Bir
 * kitap 13 MB; hukuk kütüphanesinde gezinen biri günler içinde yüz MB'ları
 * sessizce dolduruyor. `cacheDir` olduğu için Android yer sıkışınca temizler
 * ama bu, önce telefonu doldurup sonra rastgele bir anda tüm önbelleği
 * kaybetmek demek. Kendi tavanımızı koyuyoruz.
 */
data class CacheEntry(val name: String, val lastUsed: Long)

/** İndirme önbelleğinde tutulan en yeni PDF sayısı. */
internal const val PDF_CACHE_KEEP = 20

/**
 * [keep] en yeni kullanılanı bırakır, gerisinin adını döner.
 *
 * Ölçüt "son KULLANIM"dır, indirme zamanı değil: önbellekte zaten var olan bir
 * belgeyi açmak indirme yapmıyor, dolayısıyla yalnız indirme zamanına bakılsaydı
 * her gün açılan bir kitap eskiyip düşerdi. Çağıran, önbellekten karşıladığı
 * dosyanın damgasını tazeliyor.
 *
 * Eşitlikte ad'a göre kararlı sıralama: aynı milisaniyede inen iki dosyada
 * hangisinin düşeceği rastgele olmasın.
 */
fun cacheFilesToEvict(files: List<CacheEntry>, keep: Int = PDF_CACHE_KEEP): List<String> {
    if (keep <= 0 || files.size <= keep) return emptyList()
    return files
        .sortedWith(compareByDescending<CacheEntry> { it.lastUsed }.thenBy { it.name })
        .drop(keep)
        .map { it.name }
}
