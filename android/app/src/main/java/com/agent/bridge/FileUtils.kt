package com.agent.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File

fun isMarkdownFile(path: String): Boolean {
    val clean = path.substringBefore('#')
    val ext = clean.substringAfterLast('.', "").lowercase()
    return ext == "md" || ext == "markdown"
}

/**
 * Markdown OLMAYAN ama editörde açılabilen düz metin türleri.
 *
 * Editör zaten ham metin üzerinde çalışıyor ve ham kaydediyor; köprünün okuma
 * ve yazma uçları da tür bakmıyor. Yani bu dosyaları dışarı yollamak için
 * teknik bir sebep yoktu — .txt'yi görmek için harici uygulama seçtirmek
 * gereksiz bir engeldi.
 *
 * Uzantısız dosyalar (LICENSE, Makefile…) BİLEREK yok: uzantısı olmayan her
 * şeyi metin saymak ikili dosyaları da editöre sokardı.
 */
private val PlainTextExtensions = setOf(
    "txt", "log", "csv", "tsv", "json", "jsonl", "ini", "cfg", "conf", "env",
    "yml", "yaml", "toml", "xml", "html", "htm", "css", "sql", "sh", "bat",
    "ps1", "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "gradle",
    "properties", "gitignore", "srt", "vtt",
)

fun isPlainTextFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").lowercase() in PlainTextExtensions

fun isDocxFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").equals("docx", ignoreCase = true)

/**
 * UYAP UDF. `.udfx` (imzalı/şifreli varyant) BİLEREK dışarıda: kabı aynı ama
 * içeriği çözülmüş değil, uygulama içi editörde açmak yanlış olur.
 */
fun isUdfFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").equals("udf", ignoreCase = true)

/** Blok editörünün açtığı belge türleri (DOCX + UDF aynı editörü besler). */
fun isBlockEditorFile(path: String): Boolean = isDocxFile(path) || isUdfFile(path)

/**
 * Açık belgenin paylaşımda görünecek adı.
 *
 * Eleme `isDocxFile` ile yapılıyordu ve UDF açıkken BAŞARISIZ oluyordu: ad
 * yedeğe düşüyor, `dilekce.udf` paylaşım ekranında "belge.docx" diye
 * görünüyordu (kullanıcı bildirdi 20.08.2026). Baytlar doğruydu — yalnız etiket
 * yanlıştı, alıcı uygulama da onu Word belgesi sanıyordu. Blok editörü DOCX ve
 * UDF'in İKİSİNİ birden açtığı için eleme de ikisini birden tanımalı.
 *
 * Yedek adın uzantısı hâlâ `.docx`: buraya yalnız blok editörünün açtığı bir
 * belge düşebilir ve hangisi olduğu okunamıyorsa DOCX daha az zararlı tahmin
 * (paylaşım ekranı adsız kalmasın diye yedek gerekiyor).
 */
fun sharedDocumentName(name: String): String =
    name.takeIf { isBlockEditorFile(it) } ?: "belge.docx"

fun isPdfFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").equals("pdf", ignoreCase = true)

/**
 * Dahili tablo okuyucunun açtığı türler.
 *
 * `.xls` BİLEREK yok: adı benziyor ama bambaşka bir ikili biçim (BIFF), okuyucu
 * yalnız OOXML biliyor. Listeye eklemek "açıldı sanıp hata gösteren" bir ekran
 * üretirdi; harici uygulamaya düşmesi daha dürüst.
 *
 * `.xlsm` var: makro barındıran ama gövdesi birebir aynı olan XLSX. Makroyu
 * çalıştırmıyoruz, yalnız hücreleri okuyoruz — risk yok.
 */
private val SpreadsheetExtensions = setOf("xlsx", "xlsm")

fun isSpreadsheetFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").lowercase() in SpreadsheetExtensions

/**
 * Bir dosyayı hangi görüntüleyici açar? Bu ayrım ÜÇ ayrı yerde tekrarlanıyordu
 * (gezgin, telefon gezgini, sohbetteki dosya bağlantısı); PDF eklenirken
 * bunlardan biri atlandı ve sohbetten açılan PDF hâlâ dışarı düşüyordu. Tek
 * kapı: yeni bir tür eklenince üç yer birden öğrenir.
 */
// DOCX = "blok editörü": DOCX ve UDF aynı ekrana gider (06.08.2026).
enum class ViewerKind { MARKDOWN, DOCX, IMAGE, VIDEO, PDF, SHEET, EXTERNAL }

fun viewerKindFor(path: String): ViewerKind = when {
    // Markdown ve düz metin AYNI editöre gider (ikisi de ham metin). Editör
    // Markdown olmayanda önizleme sekmesini kapatıyor: .txt içinde "#" ile
    // başlayan bir satır başlık gibi çizilmesin.
    isMarkdownFile(path) || isPlainTextFile(path) -> ViewerKind.MARKDOWN
    isBlockEditorFile(path) -> ViewerKind.DOCX
    isVideoFile(path) -> ViewerKind.VIDEO
    isImageFile(path) -> ViewerKind.IMAGE
    isPdfFile(path) -> ViewerKind.PDF
    isSpreadsheetFile(path) -> ViewerKind.SHEET
    else -> ViewerKind.EXTERNAL
}

// ExoPlayer'ın oynattığı kap biçimleri.
// webp/gif BİLEREK burada değil: hareketli de olsalar tek kare olarak da
// gelebiliyorlar ve görsel görüntüleyicinin zoom/paylaş akışını kaybetmesinler.
private val VideoFileExtensions = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "avi")

fun isVideoFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").lowercase() in VideoFileExtensions

internal fun videoMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "3gp" -> "video/3gpp"
    "avi" -> "video/x-msvideo"
    else -> "video/mp4"
}

// Dahili görsel görüntüleyicinin açtığı biçimler (BitmapFactory'nin çözebildikleri;
// heic/heif API 28+ — altında kullanıcıya çözme hatası gösterilir, harici uygulamaya
// düşülmez çünkü cihazlarda güvenilir bir galeri hedefi yok).
private val ImageFileExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

fun isImageFile(path: String): Boolean =
    path.substringBefore('#').substringAfterLast('.', "").lowercase() in ImageFileExtensions

internal fun imageMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    else -> "application/octet-stream"
}

// Compute the parent of a path manually. java.io.File on Android (Linux) treats '\' as a
// normal character, so File("C:\\Users\\Name").parent returns null for Windows paths from
// the bridge. Handle both separators and keep the drive root (e.g. "C:" -> "C:\").

internal fun parentPath(path: String): String? {
    val p = path.trim().trimEnd('\\', '/')
    val idx = p.lastIndexOfAny(charArrayOf('\\', '/'))
    if (idx <= 0) return null
    val parent = p.substring(0, idx)
    return if (parent.endsWith(":")) "$parent\\" else parent.ifBlank { null }
}

// formatIsoTime shared/TimeFormat.kt'ye taşındı (Android + masaüstü ortak).
