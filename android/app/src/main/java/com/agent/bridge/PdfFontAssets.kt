package com.agent.bridge

import android.content.Context
import java.io.File

/**
 * [PdfFontFix]'in gömdüğü yüzler.
 *
 * Neden paketten, cihazın `/system/fonts` dizininden değil: Android'de statik bir
 * **kalın sans** yüzü yok (DroidSans-Bold, değişken Roboto-Regular'a bağlı bir
 * sembolik bağ). Kalınlığı kaybetmemek için yüzleri kendimiz taşıyoruz.
 *
 * Yüzler Arimo (sans), Tinos (serif) ve Cousine (mono) — sırasıyla Arial, Times New
 * Roman ve Courier ile metrik uyumlu OFL fontlar; UYAP evrakında geçen adlar tam
 * olarak bunlar, dolayısıyla satır sarması ve sayfa düzeni değişmiyor. Hepsi
 * Latin-1 + Latin Extended-A'ya indirgendi: on iki yüz toplam ~440 KB.
 * Lisans metni assets/pdf-fonts/OFL.txt.
 */
class PdfFontAssets(context: Context) {
    private val assets = context.applicationContext.assets
    private val cache = HashMap<String, ByteArray?>()

    /** İstenen yüzün bayt dizisi; paket bozuksa null (düzeltme atlanır). */
    @Synchronized
    fun load(face: PdfFontFix.Face): ByteArray? = cache.getOrPut(face.assetName) {
        runCatching { assets.open("$DIR/${face.assetName}").use { it.readBytes() } }.getOrNull()
    }

    private companion object {
        const val DIR = "pdf-fonts"
    }
}

/**
 * PDF'i açmadan önce gerekiyorsa fontlarını gömer ve düzeltilmiş kopyayı döner.
 * Düzeltme uygulanmazsa (ya da uygulanıp açılamazsa) orijinal dosya döner —
 * bu yol hiçbir koşulda görüntüyü bugünkünden kötü hale getirmemeli.
 */
fun ensurePdfFontsEmbedded(source: File, cacheDir: File, fonts: PdfFontAssets): File {
    if (!source.isFile || source.length() > PdfFontFix.MAX_INPUT_BYTES) return source
    val fixed = File(cacheDir, "fontfix-${source.length()}-${source.lastModified()}-${source.name}")
    if (fixed.isFile && fixed.length() > 0) return fixed
    return runCatching {
        val repaired = PdfFontFix.repair(source.readBytes()) { fonts.load(it) } ?: return source
        cacheDir.mkdirs()
        fixed.writeBytes(repaired)
        fixed
    }.getOrElse {
        runCatching { fixed.delete() }
        source
    }
}
