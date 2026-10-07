package com.agent.bridge.udf

/**
 * Mobil imza akışının kullanıcıya görünen hâli.
 *
 * Akış UYAP'ın mimza geçidiyle iki adımda yürüyor ([MobilImzaManager]):
 * `getHash` bir doğrulama kodu döndürüyor ve telefona imza isteği düşürüyor;
 * `getSignature` kullanıcı telefonda PIN'i girene kadar bekleyip imzayı
 * getiriyor. Kod ekranda gösterilmek ZORUNDA: kullanıcı telefonundaki istekte
 * aynı kodu görmeden PIN girmemeli, yoksa neyi imzaladığını doğrulayamaz.
 */
sealed interface MobilImzaDurumu {
    /** Pencere kapalı. */
    object Kapali : MobilImzaDurumu

    /** Telefon numarası + operatör soruluyor. */
    data class Form(val hata: String = "") : MobilImzaDurumu

    /** getHash isteği yolda. */
    object HashIsteniyor : MobilImzaDurumu

    /** Telefonda PIN bekleniyor; kod ekranda duruyor. */
    data class OnayBekleniyor(val dogrulamaKodu: String, val apTransId: String) : MobilImzaDurumu

    data class Tamam(val mesaj: String) : MobilImzaDurumu
    data class Hata(val mesaj: String) : MobilImzaDurumu
}

/**
 * Arayüzde gösterilen operatörler.
 *
 * Yazımları [MobilImzaManager.getOperatorId]'nin tanıdığı ASCII karşılıklar —
 * "TÜRK TELEKOM" yazsaydık `uppercase()` Ü'yü olduğu gibi bırakıp eşleşmeyi
 * kaçırırdı.
 *
 * Editor Pro'daki "TEST (MOCK)" seçeneği BİLEREK yok: sahte imza baytlarını
 * gerçek bir UYAP belgesine yazıyor ve o belge sonradan imzalı görünüyor.
 */
val MOBIL_IMZA_OPERATORLERI = listOf("TURKCELL", "VODAFONE", "TURK TELEKOM")

/**
 * Formdaki girdinin hatası; sorun yoksa null.
 *
 * Numara denetimi [MobilImzaManager.normalizePhoneNumber] ile AYNI kuralı
 * kullanıyor (05 ile başlayan 11 hane) — burada erken söylemek, kullanıcıyı
 * geçide gidip boş dönen bir istekten kurtarıyor.
 */
fun mobilImzaGirdiHatasi(telNo: String, operator: String): String? = when {
    MobilImzaManager.normalizePhoneNumber(telNo) == null ->
        "Telefon numarası 05 ile başlayan 11 hane olmalı (örn. 0532 123 45 67)."
    operator !in MOBIL_IMZA_OPERATORLERI -> "GSM operatörünü seç."
    else -> null
}
