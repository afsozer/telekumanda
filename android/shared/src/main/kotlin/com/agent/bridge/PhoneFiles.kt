package com.agent.bridge

import java.io.File

// Telefonun kendi diskindeki dosya işlemleri. `java.io.File` saf JVM — bu yüzden
// Android'siz shared modülde durabiliyor ve geçici klasörle düz JUnit'te test
// edilebiliyor (madde 11.3'ün ruhu: Android'e özel olan yalnız KÖK çözümü ve
// izin kontrolü, onlar app tarafında PhoneStorage'ta).
//
// Köprüdeki /dirs ile aynı sözleşmeyi konuşur: aynı DirEntry, aynı WorkerDirs.
// Böylece gezgin UI'ı kaynağın PC mi telefon mu olduğunu bilmek zorunda kalmaz.

enum class FileSource { PC, PHONE }

object PhoneFiles {

    // AgentBridge kökünün altındaki sabit düzen. PC'deki ~/CoworkSpaces ile aynı
    // yazım — eşleştirme kodu ad dönüştürmek zorunda kalmasın.
    const val ROOT_NAME = "AgentBridge"
    const val COWORK_DIR = "CoworkSpaces"
    const val PICTURES_DIR = "Pictures"
    const val OLD_DOWNLOADS_DIR = "old downloads"

    fun listDir(path: String): WorkerDirs {
        val dir = File(path)
        if (!dir.isDirectory) {
            return WorkerDirs(ok = false, base = path, parent = dir.parent.orEmpty(), dirs = emptyList())
        }
        // listFiles() okuma izni yoksa null döner — bunu boş klasörle KARIŞTIRMA,
        // kullanıcıya "klasör boş" demek yerine izin sorununu göstermeliyiz.
        val children = dir.listFiles() ?: return WorkerDirs(
            ok = false, base = dir.absolutePath, parent = dir.parent.orEmpty(), dirs = emptyList(),
        )
        val entries = children.map { f ->
            DirEntry(
                name = f.name,
                path = f.absolutePath,
                type = if (f.isDirectory) "dir" else "file",
                size = if (f.isDirectory) 0L else f.length(),
                mtime = f.lastModified(),
            )
        }
        return WorkerDirs(ok = true, base = dir.absolutePath, parent = dir.parent.orEmpty(), dirs = entries)
    }

    fun ensureDir(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    /** Klasörse içeriğiyle birlikte siler. */
    fun delete(path: String): Boolean = File(path).deleteRecursively()

    fun rename(path: String, newName: String): Boolean {
        val src = File(path)
        val safe = sanitizeName(newName)
        if (safe.isBlank() || !src.exists()) return false
        val target = File(src.parentFile, safe)
        if (target.exists()) return false
        return src.renameTo(target)
    }

    fun move(path: String, destDir: String): Boolean {
        val src = File(path)
        val dir = File(destDir)
        if (!src.exists() || !dir.isDirectory) return false
        val target = File(dir, src.name)
        if (target.exists()) return false
        // renameTo aynı birim içinde çalışır; /sdcard içinde kaldığımız sürece
        // yeterli. Birim değişirse (USB OTG) kopyala-sil'e düşeriz.
        if (src.renameTo(target)) return true
        return runCatching {
            src.copyRecursively(target, overwrite = false)
            src.deleteRecursively()
        }.getOrDefault(false)
    }

    /**
     * Kopyalar. Ad çakışırsa " (1)" ekler — aynı klasöre yapıştırmak geçerli
     * bir iş. Klasörü kendi altına kopyalamayı reddeder (sonsuz döngü).
     */
    fun copy(path: String, destDir: String): Boolean {
        val src = File(path)
        val dir = File(destDir)
        if (!src.exists() || !dir.isDirectory) return false
        if (isWithin(src.absolutePath, dir.absolutePath)) return false
        val target = uniqueChild(destDir, src.name)
        return runCatching { src.copyRecursively(target, overwrite = false) }.getOrDefault(false)
    }

    /** Aynı adlı dosya varsa "ad (1).uzanti" üretir. */
    fun uniqueChild(dir: String, name: String): File {
        val safe = sanitizeName(name).ifBlank { "dosya" }
        val parent = File(dir)
        var candidate = File(parent, safe)
        if (!candidate.exists()) return candidate
        val stem = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "$stem ($i)" else "$stem ($i).$ext"
            candidate = File(parent, suffix)
            i++
        }
        return candidate
    }

    // Yol ayırıcı ve üst-klasör kaçışı ad alanına giremez: kullanıcı yeniden
    // adlandırırken "../../x" yazarsa dosya başka yere taşınmamalı.
    fun sanitizeName(name: String): String =
        name.replace('\\', '/').substringAfterLast('/').trim().trim('.')

    // Aşağısı YOL MANTIĞI: Android yollarıyla (POSIX, '/') çalışır ve bilerek
    // java.io.File kullanmaz — File.absolutePath testleri koşturan Windows'ta
    // yolu "C:\..." yapıp mantığı bozuyor. Saf dize olunca hem doğru hem
    // platformdan bağımsız test edilebilir.

    private fun normalize(path: String): String =
        path.replace('\\', '/').trimEnd('/').ifBlank { "/" }

    fun join(vararg parts: String): String =
        parts.filter { it.isNotBlank() }
            .joinToString("/") { it.replace('\\', '/').trim('/') }
            .let { if (parts.firstOrNull()?.startsWith("/") == true) "/$it" else it }

    /** [path], [root] ağacının içinde mi? Gezinme korkuluğu için. */
    fun isWithin(root: String, path: String): Boolean {
        if (root.isBlank()) return true
        val r = normalize(root)
        val p = normalize(path)
        return p == r || p.startsWith("$r/")
    }

    /**
     * Kilitli kökte üst klasör satırı gizlenir. Kök dışına çıkacaksa boş döner.
     */
    fun parentWithin(root: String, path: String): String {
        if (path.isBlank()) return ""
        val p = normalize(path)
        val parent = p.substringBeforeLast('/', "")
        if (parent.isBlank()) return ""
        if (root.isNotBlank() && !isWithin(root, parent)) return ""
        return parent
    }

    /**
     * Cowork çalışma alanının telefondaki karşılığı. PC'deki klasör adı birebir
     * korunur; ad çözülemezse boş döner (çağıran sessizce vazgeçer).
     */
    fun coworkSpacePath(agentBridgeRoot: String, workspaceName: String): String {
        val safe = sanitizeName(workspaceName)
        if (agentBridgeRoot.isBlank() || safe.isBlank()) return ""
        return join(agentBridgeRoot, COWORK_DIR, safe)
    }
}
