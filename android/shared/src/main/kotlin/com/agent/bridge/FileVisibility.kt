package com.agent.bridge

/**
 * "Gizli dosyaları göster" anahtarının kuralı. Filtre İSTEMCİDE, çünkü iki
 * kaynak farklı davranıyordu: köprü nokta-dosyaları hep eliyordu, telefon
 * tarafı hiç elemiyordu ve `.nomedia` listede duruyordu. Tek kural iki
 * kaynağa da uygulanınca anahtar her yerde aynı şeyi yapar.
 */

/**
 * Windows'ta "gizli" ÖZNİTELİĞİ Node'un `fs.stat`'ından okunamıyor, o yüzden
 * öznitelikle gizlenenleri ancak adla eleyebiliyoruz. Her listelemede `attrib`
 * çağırmak doğru sonucu verirdi ama her klasör açılışına gecikme bindirirdi —
 * pratikte gördüğümüz gizli isimler bunlar.
 */
private val HiddenNames = setOf(
    "desktop.ini",
    "thumbs.db",
    "\$recycle.bin",
    "system volume information",
    // Teknik olarak gizli değil ama gezginde gürültü; köprü de hep eliyordu.
    "node_modules",
)

fun isHiddenEntry(name: String): Boolean =
    name.startsWith(".") || name.lowercase() in HiddenNames

fun filterHidden(entries: List<DirEntry>, showHidden: Boolean): List<DirEntry> =
    if (showHidden) entries else entries.filterNot { isHiddenEntry(it.name) }
