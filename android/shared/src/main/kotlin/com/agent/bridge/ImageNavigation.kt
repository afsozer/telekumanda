package com.agent.bridge

/**
 * Görsel görüntüleyicide klasör içi gezinme. Compose'suz tutuldu: sınır
 * davranışı (klasörün başı/sonu) testle sabitlensin.
 */

/**
 * [current] indeksinden [delta] kadar ötedeki görselin indeksi; klasörün
 * dışına taşarsa null. SARMA YOK — sondan başa dönmek "klasör bitti"
 * bilgisini gizler, kullanıcı aynı görselleri döndüğünü fark etmez.
 */
fun adjacentImageIndex(current: Int, total: Int, delta: Int): Int? {
    if (total <= 0 || current !in 0 until total) return null
    val next = current + delta
    return if (next in 0 until total) next else null
}

/**
 * Yatay sürüklemenin yönü: +1 sonraki, -1 önceki, 0 eşiğin altında.
 * Parmağı SOLA çekmek SONRAKİ görseli getirir (sayfa çevirir gibi).
 */
fun swipeDirection(dragPx: Float, thresholdPx: Float): Int = when {
    thresholdPx <= 0f -> 0
    dragPx <= -thresholdPx -> 1
    dragPx >= thresholdPx -> -1
    else -> 0
}
