package com.agent.bridge

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * OTA paketinin bütünlüğü. Köprünün `latest.json`'u paketin SHA-256 özetini
 * taşır; uygulama paketi indirirken özeti hesaplar ve tutmazsa kurmaz.
 *
 * Bu, yarım inen, bozulan ya da köprüde değiştirilen paketi yakalar. Köprünün
 * kendisi ele geçirilirse özeti de değiştirebilir; o durumda son savunma
 * Android'in, güncellemeyi yalnız aynı imza anahtarıyla imzalanmışsa kurmasıdır.
 */
object UpdateIntegrity {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * Bildirimdeki özeti doğrular ve küçük harfe çevirir. Özet yoksa ya da
     * biçimi bozuksa güncelleme reddedilir: özetsiz paket kurulmaz.
     */
    fun expectedDigest(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (!HEX64.matches(value)) throw IOException("Güncelleme bildiriminde geçerli bir SHA-256 özeti yok")
        return value
    }

    /** [input]'u [output]'a kopyalar ve kopyalanan baytların SHA-256 özetini döndürür. */
    fun copyWithDigest(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            copied += read
            onBytes(copied)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Özetler tutmazsa [IOException] fırlatır. */
    fun verify(expected: String, actual: String) {
        if (!MessageDigest.isEqual(expected.toByteArray(), actual.lowercase().toByteArray())) {
            throw IOException("İndirilen güncelleme paketi doğrulanamadı (SHA-256 tutmuyor)")
        }
    }
}
