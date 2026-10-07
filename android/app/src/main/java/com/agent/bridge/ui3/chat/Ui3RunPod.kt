package com.agent.bridge.ui3.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.RunPodStatus
import com.agent.bridge.ui2.chat.runPodPillLabel
import com.agent.bridge.ui2.chat.runPodPillAction
import com.agent.bridge.ui2.chat.runPodProgress
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import kotlin.math.PI
import kotlin.math.sin

/** Hapın boyu — model satırındaki monogramdan (34dp) küçük, satırı şişirmesin. */
private val HAP_Y = 28.dp

/**
 * Pod'un tek kelimelik hâli — nokta ve rozet bunu okur, hap değil.
 *
 * Hap zaten [runPodPillLabel] ile konuşuyor; nokta konuşamıyor, yalnız renk
 * verebiliyor. Renk seçimini bir `when` zincirine bırakmak yerine burada saf
 * bir değere indirmek iki şey sağlıyor: teste bağlanabiliyor ve hapla noktanın
 * AYNI kaynağı okuduğu görülebiliyor.
 *
 * Sıra hapın kendi sırasının aynısı (bkz. [Ui3RunPodTusu]): önce `hazir`, sonra
 * `mesgul`. Ters çevirmek `ready && operationActive` gibi geçici bir anda
 * noktayı hapın rengi olmayan bir renge boyardı.
 */
internal enum class Ui3RunPodIsigi { Kapali, Mesgul, PodAcik, Hazir }

internal fun ui3RunPodIsigi(durum: RunPodStatus): Ui3RunPodIsigi = when {
    durum.ready && durum.phase == "ready" -> Ui3RunPodIsigi.Hazir
    durum.operationActive || durum.phase == "starting" || durum.phase == "stopping" ->
        Ui3RunPodIsigi.Mesgul
    // Pod ayakta ama köprü yok: eylem "bağlan". Hapta da bu hâl kehribar ama
    // hareketsiz — para akıyor, iş akmıyor.
    runPodPillAction(durum) == "connect" -> Ui3RunPodIsigi.PodAcik
    else -> Ui3RunPodIsigi.Kapali
}

/**
 * RunPod başlat/durdur hapı — ui2'deki `RunPodControlPill`'in DAVRANIŞI, ui3'ün
 * malzemesiyle (anayasa v2 bölüm 6).
 *
 * KARAR MANTIĞI KOPYALANMADI. Etiket, ilerleme ve eylem seçimi ui2'deki saf
 * fonksiyonlardan geliyor ([runPodPillLabel], [runPodProgress] ve çağıranda
 * `runPodPillAction`) — aynı Gradle modülü, `internal` erişim yetiyor. Böylece
 * iki arayüz aynı durumda aynı şeyi söylüyor ve `RunPodControlTest` ikisini
 * birden koruyor. Kopyalansaydı adımların birinin metni değişince biri geride
 * kalırdı — bu depoda tam olarak öyle bir hata yaşandı (ui2'nin "API sınanıyor"
 * testi kaynak değişince kırmızıya düşmüştü).
 *
 * YERİ DEĞİŞTİ (25.08.2026, kullanıcı: "eğreti duruyor"). Önce sohbetin
 * dibinde, composer yığınının en üstünde, KENDİNE AİT bir satırda duruyordu:
 * opencode oturumunun tamamı boyunca ~34dp yer yiyen, kardeşi olmayan yalnız
 * bir tuş — üstelik RunPod seçili olmasa da oradaydı. Şimdi MODEL SEÇ sheet'inde,
 * RunPod satırının altında: pod'u açma isteği o modeli seçerken doğuyor, tuş da
 * orada. Sohbette geriye ambiyans kalıyor — model çipindeki nokta
 * ([Ui3RunPodNoktasi]).
 *
 * BLUR'SUZ CAM ([GlassLikeSurface]), `GlassSurface` DEĞİL. Hap artık sheet'in
 * İÇİNDE ve altında bulanıklaştırılacak keskin sohbet metni yok; kap zaten cam.
 * "Cam üstüne cam yığılmaz" (anayasa v2 §1.3) — komşusu olan seçenek satırları
 * da aynı yüzeyi kullanıyor.
 *
 * RENKLER PALETTEN, ui2'nin sabitleri DEĞİL. ui2 dolguyu 0xFFFFB300, yazıyı
 * 0xFF251A00 yapıyor; ui3'ün açık temasında kehribar (#7D5915) bir MÜREKKEP
 * tonu — onu dolgu yapıp üstüne koyu yazı koymak okunmaz olurdu. Burada zemin
 * kehribarın alfası, yazı kehribarın kendisi; iki temada da okunuyor.
 *
 * HAREKET BÜTÇESİ (anayasa v2 bölüm 5): nabız ve ışıltı YALNIZ pod açıkken ya
 * da bir geçiş sürerken kurulur. Kapalıyken hap tamamen sabit, sıfır kare.
 */
@Composable
internal fun Ui3RunPodTusu(
    durum: RunPodStatus,
    onTikla: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isik = ui3RunPodIsigi(durum)
    val hazir = isik == Ui3RunPodIsigi.Hazir
    val mesgul = isik == Ui3RunPodIsigi.Mesgul
    val podAcik = isik == Ui3RunPodIsigi.PodAcik

    val nabiz = if (hazir || mesgul) nabizDegeri() else 0f
    val isilti = if (hazir) isiltiFazi() else 0f
    val dolusuk by animateFloatAsState(
        targetValue = runPodProgress(durum) ?: 0f,
        animationSpec = tween(650),
        label = "runpod-adim",
    )

    val sekil = Ui3Tokens.pill
    val kehribar = Ui3Colors.amber
    val murekkep = when {
        hazir || podAcik -> kehribar
        mesgul -> Ui3Colors.running
        else -> Ui3Colors.ink2
    }

    GlassLikeSurface(
        modifier = modifier
            .height(HAP_Y)
            // Hafif nefes YALNIZ pod açıkken: bu hâl para yakıyor, listenin
            // içinde sessizce durmasın.
            .then(
                if (hazir) {
                    Modifier.graphicsLayer {
                        scaleX = 1f + nabiz * 0.02f
                        scaleY = 1f + nabiz * 0.02f
                    }
                } else {
                    Modifier
                },
            )
            // Geçiş sürerken kapalı: iki kez tetiklemek köprüye ikinci bir
            // başlat/durdur isteği göndermek demek (ui2'de de öyle).
            .clickable(enabled = !mesgul, onClick = onTikla)
            .testTag("runpod_hap"),
        shape = sekil,
        tint = if (hazir || podAcik) GlassTint.Amber else GlassTint.Notr,
        // Nabız tonun ALTINDA duran dolguda: kehribar yıkama nefes alırken
        // yüzey cam ailesinden kopmuyor.
        dolgu = if (hazir) kehribar.copy(alpha = 0.10f + nabiz * 0.12f) else null,
    ) {
        if (mesgul) {
            // Adım ilerlemesi: hapın içini soldan dolduran şerit. Yüzde değil,
            // köprünün bildirdiği ADIM (pod → ssh → model → tünel → health).
            Box(Modifier.matchParentSize().clip(sekil)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(dolusuk)
                        .background(Ui3Colors.running.copy(alpha = 0.24f + nabiz * 0.12f)),
                )
            }
        }
        if (hazir) {
            Isiltilar(isilti, kehribar, Modifier.matchParentSize())
        }
        Text(
            runPodPillLabel(durum),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
            style = Ui3Type.alt,
            color = murekkep,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Pod'un sohbetteki tek izi — model çipinin başındaki 7dp'lik nokta.
 *
 * Hapın yerine geçmiyor, ONUN AMBİYANSI: RunPod saatlik para yakıyor ve
 * "açık kaldı mı" sorusunun cevabı ekranda durmalı (eski hapın en değerli
 * yanıydı). Ama bunun için bir SATIR harcamak gerekmiyordu — çipin içinde
 * yer bedava, üstelik nokta yalnız RunPod seçiliyken çiziliyor.
 *
 * Dokunma hedefi yok: çipin kendisi zaten model sheet'ini açıyor ve eylem
 * orada. Noktanın içinde ikinci bir tıklama alanı, aynı yere giden iki farklı
 * hedef demek olurdu.
 *
 * Nabız YALNIZ hazır ve meşgul hâlde (anayasa v2 §5). Kapalıyken sessiz gri
 * bir nokta: "RunPod seçili ama pod kapalı" bilgisini taşıyor, göz almıyor.
 */
@Composable
internal fun Ui3RunPodNoktasi(isik: Ui3RunPodIsigi, modifier: Modifier = Modifier) {
    val canli = isik == Ui3RunPodIsigi.Hazir || isik == Ui3RunPodIsigi.Mesgul
    val nabiz = if (canli) nabizDegeri() else 0f
    val renk = when (isik) {
        Ui3RunPodIsigi.Hazir, Ui3RunPodIsigi.PodAcik -> Ui3Colors.amber
        Ui3RunPodIsigi.Mesgul -> Ui3Colors.running
        Ui3RunPodIsigi.Kapali -> Ui3Colors.ink3
    }
    Box(
        modifier
            .size(7.dp)
            .clip(CircleShape)
            // Sabit hâlde tam opak; canlı hâlde nefes alıyor. Taban 0.55:
            // altına inince açık temada nokta zeminde kayboluyordu.
            .background(renk.copy(alpha = if (canli) 0.55f + nabiz * 0.45f else 1f))
            .testTag("runpod_nokta"),
    )
}

/**
 * Işıltılar — ui2'nin beyaz kıvılcımları KEHRİBAR tonda.
 *
 * ui2 `Color.White` çiziyor; koyu zeminde doğru ama ui3 açık temada da çalışıyor
 * ve açık kehribar dolgunun üstünde beyaz kıvılcım görünmezdi. Kehribarın
 * kendisi iki temada da zeminden ayrılıyor.
 */
@Composable
private fun Isiltilar(faz: Float, renk: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(Ui3Tokens.pill)) {
        // (yatay başlangıç, dikey konum, faz kayması) — ui2'deki dizinin aynısı.
        val kivilcimlar = listOf(
            Triple(0.12f, 0.30f, 0.00f),
            Triple(0.29f, 0.72f, 0.36f),
            Triple(0.56f, 0.24f, 0.68f),
            Triple(0.76f, 0.68f, 0.16f),
            Triple(0.91f, 0.34f, 0.52f),
        )
        kivilcimlar.forEach { (basX, y, kayma) ->
            val tur = (faz + kayma) % 1f
            val parilti = (sin(tur * 2f * PI).toFloat() + 1f) / 2f
            val x = ((basX + faz * 0.08f) % 1f) * size.width
            kivilcimCiz(
                merkez = Offset(x, y * size.height),
                yaricap = (1.6f + parilti * 2.2f).dp.toPx(),
                renk = renk.copy(alpha = 0.20f + parilti * 0.60f),
            )
        }
    }
}

/** Dört uçlu yıldız — ui2'deki `drawRunPodSparkle`'ın geometrisi. */
private fun DrawScope.kivilcimCiz(merkez: Offset, yaricap: Float, renk: Color) {
    val ic = yaricap * 0.23f
    val yol = Path().apply {
        moveTo(merkez.x, merkez.y - yaricap)
        lineTo(merkez.x + ic, merkez.y - ic)
        lineTo(merkez.x + yaricap, merkez.y)
        lineTo(merkez.x + ic, merkez.y + ic)
        lineTo(merkez.x, merkez.y + yaricap)
        lineTo(merkez.x - ic, merkez.y + ic)
        lineTo(merkez.x - yaricap, merkez.y)
        lineTo(merkez.x - ic, merkez.y - ic)
        close()
    }
    drawPath(yol, renk)
}

@Composable
private fun nabizDegeri(): Float {
    val gecis = rememberInfiniteTransition(label = "runpod-nabiz")
    val deger by gecis.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "runpod-nabiz-deger",
    )
    return deger
}

@Composable
private fun isiltiFazi(): Float {
    val gecis = rememberInfiniteTransition(label = "runpod-isilti")
    val deger by gecis.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "runpod-isilti-faz",
    )
    return deger
}
