package com.agent.bridge

import kotlin.math.max

/**
 * Görüntüleyicideki zoom/pan sınırları. Compose'suz saf matematik: "zoomluyken
 * fotoğrafı ekrandan kaçıramamalı" ve "az kalmış zoom kendiliğinden 1x'e
 * kilitlenmeli" kuralları burada testle sabit.
 */

/** ContentScale.Fit'in verdiği çizim boyutu (1x'te). */
data class FittedSize(val width: Float, val height: Float)

fun fitInside(viewportW: Float, viewportH: Float, contentW: Float, contentH: Float): FittedSize {
    if (viewportW <= 0f || viewportH <= 0f || contentW <= 0f || contentH <= 0f) {
        return FittedSize(0f, 0f)
    }
    val byWidth = viewportW / contentW
    val byHeight = viewportH / contentH
    val k = minOf(byWidth, byHeight)
    return FittedSize(contentW * k, contentH * k)
}

/**
 * Kaydırmayı fotoğrafın kenarlarıyla sınırlar. Görüntü ekrandan küçükse
 * (ör. dikey fotoğrafın yatay ekseni) kayma hakkı sıfırdır — ortada durur.
 */
fun clampPan(value: Float, contentExtent: Float, viewportExtent: Float): Float {
    val slack = max(0f, (contentExtent - viewportExtent) / 2f)
    return value.coerceIn(-slack, slack)
}

/**
 * Jest bittiğinde zoom'un oturacağı değer. Eşiğin altı 1x'e KİLİTLENİR:
 * elle tam 1.00'e getirmeye çalışmak sinir bozucuydu ve yan fotoğrafa geçiş
 * ancak tam 1x'te açıldığı için kullanıcı takılıp kalıyordu.
 */
fun settleScale(scale: Float, snapBelow: Float = 1.15f): Float =
    if (scale < snapBelow) 1f else scale
