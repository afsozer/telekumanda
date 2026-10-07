package com.agent.bridge

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Delege metotlarının boşta kalmasını (hiçbir yerden ulaşılamamasını) yakalayan
 * bekçi testi.
 *
 * Sorun: shared modülündeki bir delegeye yeni public metot eklenip
 * RemoteViewModel'daki elle yönlendirme satırı eklenmeyi unutulunca kod
 * derlenir, testler geçer, ama metoda hiçbir yerden ulaşılamaz — sessizce ölü
 * kod olur. Bu gerçekten yaşandı (copyBrowserEntry).
 *
 * Kural: her delege metodunun adı, iki kaynak ağacından (app/src/main,
 * shared/src/main) birinde **kullanım** olarak geçmelidir. (Eskiden
 * desktop/src/main de taranıyordu; Compose Desktop istemcisi tarayıcı
 * arayüzüne geçilince kaldırıldı.)
 * Kullanım sayılanlar:
 *  - `ad(` çağrısı ya da `::ad` metot referansı (yalın),
 *  - `<alıcı>.ad(` / `<alıcı>?.ad(` / `<alıcı>::ad` — burada <alıcı>, metodun
 *    tanımlandığı delege sınıfından bildirilmiş bir değişken.
 *
 * Referans sayılmayanlar:
 *  - tanım satırları (`fun <ad>(` öneki kırpılır, kalanında aranır — böylece
 *    RemoteViewModel'daki `fun x() = mcpDelegate.x()` yönlendirmeleri kaybolmaz;
 *    kırpma olmadan bu satırlar yanlışlıkla referans sayılır, bütünüyle
 *    elenirse gerçek yönlendirmeler yiter),
 *  - yorum satırları (yorumlar metinden ayıklanır).
 *
 * Ad çakışması tuzağı: `refreshMcp` gibi bir ad başka bir delege sınıfında da
 * varsa (McpDelegate), o sınıfın tanım satırı ve o sınıfa giden yönlendirme
 * (`mcpDelegate.refreshMcp()`) bu metoda ulaşılabilirlik sağlamaz; kullanımın
 * doğru alıcı üzerinden olması gerekir.
 */
class DelegateReachabilityTest {

    @Test
    fun testAllDelegateMethodsAreReachable() {
        val repoRoot = findRepoRoot()
        val sharedMain = File(repoRoot, "android/shared/src/main")
        val scanTrees = listOf(
            File(repoRoot, "android/app/src/main"),
            File(repoRoot, "android/shared/src/main")
        )

        // Kaynak dizinler yoksa sessizce geçme — anlamlı mesajla başarısız ol.
        (listOf(sharedMain) + scanTrees).forEach { dir ->
            assertTrue("Kaynak dizin bulunamadı: ${dir.path}", dir.isDirectory)
        }

        // 1) shared/src/main altındaki *Delegate.kt dosyalarından, tam 4 boşluk
        //    girintili "fun <ad>" satırlarını topla. Aynı dosyada aynı adla
        //    birden çok tanım (overload) tek metot sayılır.
        val defLineRegex = Regex("^    fun ([A-Za-z_][A-Za-z0-9_]*)")
        val methods = mutableListOf<MethodDef>()
        sharedMain.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Delegate.kt") }
            .sortedBy { it.path }
            .forEach { file ->
                val byName = linkedMapOf<String, MutableSet<Int>>()
                readText(file).lines().forEachIndexed { index, line ->
                    defLineRegex.find(line)?.let { match ->
                        byName.getOrPut(match.groupValues[1]) { mutableSetOf() }
                            .add(index + 1)
                    }
                }
                byName.forEach { (name, defLines) ->
                    methods.add(MethodDef(file, name, defLines))
                }
            }

        // Sayı sağlaması: şu an 216 metot var. Sıfıra yakın çıkıyorsa delege
        // dosyaları bulunamıyor demektir. Sayı, raporlarda doğrulanabilmesi için
        // test çıktısına (JUnit XML system-out) da yazılır.
        println("Taranan delege metodu sayısı: ${methods.size}")
        assertTrue(
            "Beklenen ~216 delege metodu bulunamadı; bulunan: ${methods.size} — " +
                "delege dosyaları doğru taranamıyor olabilir.",
            methods.size >= 100
        )

        // 2) Üç kaynak ağacındaki tüm .kt dosyalarını oku. Her satırdan yorumları
        //    (// ve /* */, çok satırlı bloklar dahil) ve string literalleri
        //    ayıkla; geriye "temiz kod" kalır. Kelime kümesi, yalnızca adı
        //    içeren dosyaları satır satır taramak için hızlı ön elemedir.
        val scannedFiles = scanTrees.flatMap { tree ->
            tree.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val cleanedLines = scannedFiles.associateWith { file -> cleanFile(file) }
        val wordSets = scannedFiles.associateWith { file ->
            cleanedLines.getValue(file)
                .flatMap { line -> line.split(NON_WORD) }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        val receivers = buildReceiverMap(scannedFiles, cleanedLines)

        // 3) Her metot için en az bir kullanım ara.
        val unreachable = mutableListOf<String>()
        for (method in methods.sortedWith(compareBy({ it.file.path }, { it.name }))) {
            if (!hasUsage(method, cleanedLines, wordSets, receivers)) {
                unreachable.add("${method.file.name}: ${method.name}")
            }
        }

        if (unreachable.isNotEmpty()) {
            fail(
                "Hiçbir yerden ulaşılamayan delege metotları (${unreachable.size}):\n" +
                    unreachable.joinToString("\n") { "  - $it" } +
                    "\nRemoteViewModel'a (veya ilgili tüketiciye) yönlendirme satırı " +
                    "eklenmemiş olabilir."
            )
        }
    }

    /**
     * Metodun adı temiz kodda kullanım olarak geçiyor mu?
     *
     * Satır "fun <ad>(" ile başlıyorsa o önek bir tanımdır ve kırpılır; geri
     * kalanında aranır. Böylece RemoteViewModel'daki
     * `fun x() = mcpDelegate.x()` yönlendirme satırı bütünüyle elenmez
     * (sağ tarafındaki çağrı korunur), ama saf tanım satırları
     * (ör. McpDelegate'teki `fun refreshMcp(...)`) referans sayılmaz.
     */
    private fun hasUsage(
        method: MethodDef,
        cleanedLines: Map<File, List<String>>,
        wordSets: Map<File, Set<String>>,
        receivers: Map<String, Set<String>>
    ): Boolean {
        val patterns = UsagePatterns.forMethod(method, receivers)
        for ((file, lines) in cleanedLines) {
            // Ön eleme: adı hiç içermeyen dosyayı satır satır tarama.
            if (method.name !in wordSets.getValue(file)) continue
            for (line in lines) {
                if (patterns.matches(line)) return true
            }
        }
        return false
    }

    /**
     * Tüm dosyalardaki temiz kodda `val/var x: Sınıf` ve `val/var x = Sınıf(`
     * bildirimlerini bulup sınıf adı -> alıcı değişken adları eşlemesini kurar.
     * Delege sınıfının adı dosya adıyla aynıdır (AgyDelegate.kt -> AgyDelegate).
     */
    private fun buildReceiverMap(
        files: List<File>,
        cleanedLines: Map<File, List<String>>
    ): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val typed = Pattern.compile(
            "\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)\\b"
        )
        val inferred = Pattern.compile(
            "\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
        )
        for (file in files) {
            for (line in cleanedLines.getValue(file)) {
                var m = typed.matcher(line)
                while (m.find()) {
                    // Regex'te gruplar zorunlu; eşleşen her satırda dolu gelir.
                    map.getOrPut(m.group(2)!!) { mutableSetOf() }.add(m.group(1)!!)
                }
                m = inferred.matcher(line)
                while (m.find()) {
                    map.getOrPut(m.group(2)!!) { mutableSetOf() }.add(m.group(1)!!)
                }
            }
        }
        return map
    }

    /**
     * Yorumları (satır içi `//`, blok `/* */`, KDoc `/** */`, çok satırlı
     * bloklar) ve string literalleri (çift tırnak ve üç çift tırnak) ayıklar.
     * Amaç: yorumlarda geçen adların referans sayılmaması; `"http://..."` gibi
     * string içeriğinin de yorum sanılıp satırı kırpmaması.
     */
    private fun cleanFile(file: File): List<String> {
        val lines = readText(file).lines()
        val out = ArrayList<String>(lines.size)
        var inBlock = false
        var inTriple = false
        for (line in lines) {
            val sb = StringBuilder(line.length)
            var i = 0
            val n = line.length
            while (i < n) {
                when {
                    inBlock -> {
                        val close = line.indexOf("*/", i)
                        if (close < 0) {
                            i = n // satırın gerisi blok yorumu
                        } else {
                            i = close + 2
                            inBlock = false
                        }
                    }
                    inTriple -> {
                        val close = line.indexOf("\"\"\"", i)
                        if (close < 0) {
                            i = n // satırın gerisi üç tırnaklı string
                        } else {
                            i = close + 3
                            inTriple = false
                        }
                    }
                    line.startsWith("//", i) -> i = n
                    line.startsWith("/*", i) -> {
                        inBlock = true
                        i += 2
                    }
                    line[i] == '"' -> {
                        if (line.startsWith("\"\"\"", i)) {
                            inTriple = true
                            i += 3
                        } else {
                            i++
                            while (i < n) {
                                if (line[i] == '\\') {
                                    i += 2
                                    continue
                                }
                                if (line[i] == '"') {
                                    i++
                                    break
                                }
                                i++
                            }
                        }
                    }
                    else -> {
                        sb.append(line[i])
                        i++
                    }
                }
            }
            out.add(sb.toString())
        }
        return out
    }

    /**
     * Depo kökünü çalışma dizininden yukarı çıkarak bulur. Gradle testleri
     * android/app dizininden çalışır; kök, içinde "android/shared/src/main"
     * bulunan ilk üst dizindir. Kişiye özel mutlak yol kullanılmaz.
     */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "android/shared/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        fail(
            "Depo kökü bulunamadı: çalışma dizininden (${File("").absolutePath}) " +
                "yukarı çıkılırken 'android/shared/src/main' içeren bir dizine rastlanmadı."
        )
        throw IllegalStateException("ulaşılmaz") // fail her zaman throw eder
    }

    private fun readText(file: File): String =
        file.readText(Charsets.UTF_8).removePrefix("\uFEFF") // BOM'a karşı

    private data class MethodDef(
        val file: File,
        val name: String,
        val defLines: Set<Int>
    )

    /**
     * Tek bir metot için derlenmiş arama kalıpları. Kalıplar metot başına bir
     * kez derlenir; her satırda yeniden derlemek testi gereksiz yavaşlatır.
     */
    private class UsagePatterns(
        private val defPrefix: Pattern,
        private val bareCall: Pattern,
        private val methodRef: Pattern,
        private val receiverCalls: List<Pattern>
    ) {
        fun matches(line: String): Boolean {
            var search = line
            // Tanım öneki: "fun <ad>(" — kırp, kalanında ara.
            val def = defPrefix.matcher(line)
            if (def.find()) {
                search = line.substring(def.end())
            }
            if (bareCall.matcher(search).find()) return true
            if (methodRef.matcher(search).find()) return true
            for (p in receiverCalls) {
                if (p.matcher(search).find()) return true
            }
            return false
        }

        companion object {
            fun forMethod(
                method: MethodDef,
                receivers: Map<String, Set<String>>
            ): UsagePatterns {
                val name = Pattern.quote(method.name)
                // Delege sınıfı dosyayla aynı adı taşır (AgyDelegate.kt -> AgyDelegate).
                val cls = method.file.name.removeSuffix(".kt")
                val defPrefix = Pattern.compile("^\\s*fun\\s+" + name + "\\s*\\(")
                // Yalın çağrı: ad( — öncesinde tanımlayıcı ya da nokta olmamalı
                // (nokta varsa alıcı çağrısıdır, aşağıda sınıf eşleşmesi aranır).
                val bareCall = Pattern.compile("(?<![A-Za-z0-9_.])" + name + "\\s*\\(")
                // Yalın metot referansı: ::ad
                val methodRef = Pattern.compile("(?<![A-Za-z0-9_])::" + name + "\\b")
                val recvs = receivers[cls].orEmpty()
                val receiverCalls = buildList {
                    for (r in recvs) {
                        val rq = Pattern.quote(r)
                        add(Pattern.compile("(?<![A-Za-z0-9_])" + rq + "\\s*\\??\\.\\s*" + name + "\\s*\\("))
                        add(Pattern.compile("(?<![A-Za-z0-9_])" + rq + "\\s*::\\s*" + name + "\\b"))
                    }
                }
                return UsagePatterns(defPrefix, bareCall, methodRef, receiverCalls)
            }
        }
    }

    companion object {
        private val NON_WORD = Regex("[^A-Za-z0-9_]+")
    }
}
