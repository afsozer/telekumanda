package com.agent.bridge

/**
 * Gezgindeki çoklu seçim ve pano mantığı. Compose'suz tutuldu ki kural
 * değişikliği (ör. "kes'ten sonra pano boşalır") testle sabitlensin.
 */

/** Kesilen/kopyalanan öğeler. [move] true ise yapıştırma taşıma olur. */
data class FileClipboard(
    val paths: List<String> = emptyList(),
    val move: Boolean = false,
    // Panoya hangi klasörden alındı — aynı klasöre "taşı" anlamsız, engelleriz.
    val sourceDir: String = "",
) {
    val isEmpty: Boolean get() = paths.isEmpty()
}

fun toggleSelection(selected: Set<String>, path: String): Set<String> =
    if (path in selected) selected - path else selected + path

/**
 * Yapıştırma yapılabilir mi? Boş pano hayır; kes-yapıştır aynı klasöre hayır
 * (hiçbir şey değişmez, kullanıcıya "taşındı" demek yanıltıcı olur).
 */
fun canPasteInto(clipboard: FileClipboard, destDir: String): Boolean {
    if (clipboard.isEmpty || destDir.isBlank()) return false
    if (!clipboard.move) return true
    return !samePath(clipboard.sourceDir, destDir)
}

private fun samePath(a: String, b: String): Boolean =
    a.replace('\\', '/').trimEnd('/').equals(b.replace('\\', '/').trimEnd('/'), ignoreCase = true)

/**
 * "Tümünü seç" düğmesi. Görünen her şey zaten seçiliyse seçimi bırakır —
 * aynı düğme hem seç hem bırak; iki ayrı düğme çubuğu şişirirdi.
 * [all] o an listelenen öğeler; seçim klasör değiştirince zaten sıfırlanır.
 */
fun selectAllToggle(selected: Set<String>, all: List<String>): Set<String> =
    if (all.isNotEmpty() && selected.containsAll(all)) emptySet() else all.toSet()

/**
 * Seçim modu başlığı. Sayı 0 ise çağıran zaten seçim modundan çıkmalı.
 */
fun selectionTitle(count: Int): String = "$count öğe seçildi"

/**
 * Kes/kopyala sonrası seçim temizlenir ama pano dolu kalır: kullanıcı hedef
 * klasöre gidip yapıştıracak. Yapıştırdıktan SONRA pano yalnız taşımada
 * boşalır — kopyalamada birden çok yere yapıştırmak isteyebilir.
 */
fun clipboardAfterPaste(clipboard: FileClipboard): FileClipboard =
    if (clipboard.move) FileClipboard() else clipboard
