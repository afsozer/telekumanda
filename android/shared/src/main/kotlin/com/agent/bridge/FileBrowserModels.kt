package com.agent.bridge

private val DRIVE_SPEC = Regex("^[A-Za-z]:$")

fun browserParentPath(path: String): String? {
    val clean = path.trim().replace('\\', '/').trimEnd('/')
    if (clean.isBlank() || clean == "/") return null
    val slash = clean.lastIndexOf('/')
    val parent = when {
        slash < 0 -> null            // "C:" — sürücü kökünün üstü yok
        slash == 0 -> "/"
        else -> clean.substring(0, slash)
    }
    // "C:/Users"in üstü "C:" DEĞİL "C:/". Ters bölüsüz sürücü adı Windows'ta
    // "o sürücüdeki geçerli dizin" demek: köprü onu path.resolve ile köprünün
    // kendi çalışma klasörüne çeviriyordu, yani C:\Users'ta "üst klasör"e
    // basınca sürücü kökü yerine bridge klasörüne düşülüyordu.
    return if (parent != null && DRIVE_SPEC.matches(parent)) "$parent/" else parent
}

/**
 * Yol bu klasörün kendisi ya da altında mı? Ayraç ve büyük/küçük harf duyarsız
 * (Windows). "Son klasör" hafızası da bu soruyla çalışıyor: kayıt yalnız kökün
 * altında gezinilirken tutulur (loadBrowserDir) ve okunurken doğrulanır
 * (browserResumeDir).
 */
fun isUnderDir(path: String, dir: String): Boolean {
    val p = path.trim().replace('\\', '/').trimEnd('/')
    val d = dir.trim().replace('\\', '/').trimEnd('/')
    if (p.isBlank() || d.isBlank()) return false
    return p.equals(d, ignoreCase = true) || p.startsWith("$d/", ignoreCase = true)
}

/**
 * Gezginin bulunduğu yol bu sürücü kökünün altında mı? Sürücü şeridinde hangi
 * çipin seçili görüneceğini belirler — soru genel "klasörün altında mı"
 * sorusuyla aynı, oraya devreder.
 */
fun isUnderDrive(base: String, driveRoot: String): Boolean = isUnderDir(base, driveRoot)

// Kök-kilitli gezgin (cowork) için üst klasör: base rootLock'un kendisi veya
// üstündeyse null (yukarı çıkış yok), altındaysa normal üst klasör. Windows
// yolları için ayraç normalize edilir ve karşılaştırma case-insensitive'dir.
// rootLock null/boşsa eski davranış (browserParentPath) korunur.
fun confinedParentPath(base: String, rootLock: String?): String? {
    if (rootLock.isNullOrBlank()) return browserParentPath(base)
    val normBase = base.trim().replace('\\', '/').trimEnd('/')
    val normRoot = rootLock.trim().replace('\\', '/').trimEnd('/')
    if (normBase.isBlank()) return null
    val inside = normBase.length > normRoot.length &&
        normBase.startsWith("$normRoot/", ignoreCase = true)
    if (!inside) return null
    return browserParentPath(base)
}

// Kök-kilitli gezgin başlığı: base köke göreli gösterilir ("örnek çalışma/belgeler");
// base kökün kendisiyse boş döner (çağıran "Çalışma Alanları" gibi bir ad basar).
fun relativeToRoot(base: String, rootLock: String?): String {
    if (rootLock.isNullOrBlank()) return base
    val normBase = base.trim().replace('\\', '/').trimEnd('/')
    val normRoot = rootLock.trim().replace('\\', '/').trimEnd('/')
    if (normBase.equals(normRoot, ignoreCase = true)) return ""
    if (normBase.startsWith("$normRoot/", ignoreCase = true)) return normBase.substring(normRoot.length + 1)
    return base
}

/**
 * Konum şeridinde gösterilecek kısa yol. Tam yol (özellikle
 * /storage/emulated/0/... ve C:\Users\...) şeride sığmıyor ve baştan kırpılınca
 * işe yaramaz hale geliyor — "neredeyim" sorusunu SON parçalar cevaplıyor.
 * Kırpma olduysa başa "…/" konur ki tam yol olmadığı belli olsun.
 */
fun shortDisplayPath(path: String, segments: Int = 2): String {
    val norm = path.trim().replace('\\', '/').trimEnd('/')
    if (norm.isBlank()) return path.trim()
    val parts = norm.split('/').filter { it.isNotBlank() }
    if (parts.size <= segments) return norm
    return "…/" + parts.takeLast(segments).joinToString("/")
}

/**
 * Verilen yolun ait olduğu çalışma alanı: kökün hemen altındaki ilk klasör.
 * Yol kökün kendisi ya da dışıysa boş döner — "son klasör" hafızası yalnız bir
 * alanın İÇİNDE gezinirken tutulur, alan listesinde değil.
 *
 * Dönen yol ayraçları normalize edilmiş hâldedir; anahtar üretimi zaten
 * ayraç/büyük-küçük harf duyarsız olduğu için sorun değil.
 */
fun coworkWorkspaceFor(rootPath: String, path: String): String {
    val normRoot = rootPath.trim().replace('\\', '/').trimEnd('/')
    val normPath = path.trim().replace('\\', '/').trimEnd('/')
    if (normRoot.isBlank() || normPath.isBlank()) return ""
    if (!normPath.startsWith("$normRoot/", ignoreCase = true)) return ""
    val segment = normPath.substring(normRoot.length + 1).substringBefore('/')
    if (segment.isBlank()) return ""
    return normPath.substring(0, normRoot.length + 1 + segment.length)
}

/**
 * Çalışma klasörü tuşunun açacağı yer: kayıtlı son klasör hâlâ bu kökün
 * altındaysa o, değilse (hiç kayıt yok / başka köke ait / klasör taşınmış)
 * kökün kendisi. Kök cowork'te çalışma alanı, diğer oturumlarda cwd — kural
 * ikisinde de aynı. Kayıt köprüden geldiği hâliyle (ters bölülü) saklanır ve
 * aynen döner — loadBrowserDir'e verilecek yol bu.
 */
fun browserResumeDir(root: String, saved: String?): String =
    if (!saved.isNullOrBlank() && isUnderDir(saved, root)) saved else root

/** Cowork çağıranları için anlamlı ad; doğrulama bütün klasör köklerinde aynı. */
fun coworkResumeDir(workspace: String, saved: String?): String = browserResumeDir(workspace, saved)

// Gezgin sıralaması: ad / değiştirilme tarihi / boyut. Ad her zaman ikincil
// anahtar (eşit tarih/boyutta kararlı, öngörülebilir sıra). ascending=false
// yalnız BİRİNCİL anahtarı ters çevirir; ad kırılımı hep A→Z kalır.
enum class FileSortKey { NAME, MTIME, SIZE }

fun sortDirEntries(entries: List<DirEntry>, key: FileSortKey, ascending: Boolean): List<DirEntry> {
    val nameOrder = compareBy(String.CASE_INSENSITIVE_ORDER, DirEntry::name)
    val primary: Comparator<DirEntry> = when (key) {
        FileSortKey.NAME -> nameOrder
        FileSortKey.MTIME -> compareBy(DirEntry::mtime)
        FileSortKey.SIZE -> compareBy(DirEntry::size)
    }
    val directed = if (ascending) primary else primary.reversed()
    val comparator = if (key == FileSortKey.NAME) directed else directed.then(nameOrder)
    return entries.sortedWith(comparator)
}

fun formatByteCount(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, size / 1024.0)
    size < 1024 * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.US, size / 1024.0 / 1024.0)
    else -> "%.1f GB".format(java.util.Locale.US, size / 1024.0 / 1024.0 / 1024.0)
}
