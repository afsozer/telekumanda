package com.agent.bridge.udf

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * UDF'in `sign.sgn` (PKCS#7/CMS) bloğundan imzalayan adlarını çıkarır.
 *
 * Gömülü X.509 sertifikaları okunup her birinin subject DN'indeki CN alanı
 * alınır. İmza DOĞRULAMASI yapılmaz — bu yalnız "belgeyi kim imzalamış"
 * sorusunun okunabilir cevabı; geçerlilik denetimi UYAP'ın işi.
 *
 * Saf JVM olduğu için `shared`'da yaşıyor ve düz JUnit ile test edilebiliyor.
 */
fun udfSignerNames(signatureBytes: ByteArray?): List<String> {
    if (signatureBytes == null || signatureBytes.isEmpty()) return emptyList()
    return try {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = factory.generateCertificates(ByteArrayInputStream(signatureBytes))
        val names = certificates.mapNotNull { certificate ->
            (certificate as? X509Certificate)?.subjectX500Principal?.name
                ?.split(",")
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                ?.substring(3)
                ?.trim()
        }.distinct()
        // Zincirde kişi de makam da bulunur; kullanıcıya gösterilecek olan
        // kişidir. İkisi birden varsa sertifika otoritelerini ele.
        val people = names.filterNot { name ->
            val upper = name.uppercase()
            AUTHORITY_MARKERS.any { upper.contains(it) }
        }
        if (people.isNotEmpty()) people else names
    } catch (e: Exception) {
        // Bozuk/desteklenmeyen imza bloğu belgenin okunmasını engellemez.
        emptyList()
    }
}

private val AUTHORITY_MARKERS = listOf(
    "CA", "KÖK", "KOK", "ESHS", "SERTİFİKA", "SERTIFIKA", "ROOT", "SUE", "MALİ MÜHÜR", "MUHUR",
)
