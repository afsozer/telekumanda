package com.agent.bridge.ui3.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Type

/*
 * KOVAN KABUĞU (7 Kas 2026). Kovan'ın telefon arayüzünün yapı taşları:
 * düz üst bar (geri oku + başlık + sağda simgeler), bölüm etiketi, beyaz kart
 * içinde chevron'lu satırlar. Ölçüler Kovan ekran görüntülerinden
 * (`docs/kovan-referans/`) dp'ye çevrildi.
 */

/** Üst bar simge düğmesi: 48dp dokunma alanı, 26dp simge. */
@Composable
internal fun KovanSimge(
    ikon: ImageVector,
    aciklama: String,
    onTikla: () -> Unit,
    modifier: Modifier = Modifier,
    renk: Color = Ui3Colors.ink,
    nokta: Color? = null,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onTikla),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ikon, contentDescription = aciklama, tint = renk, modifier = Modifier.size(26.dp))
        if (nokta != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 9.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(nokta),
            )
        }
    }
}

/**
 * Kovan üst barı. [onGeri] verilirse solda geri oku çıkar; [buyuk] ana ekranın
 * kalın başlığıdır. [alt] verilirse başlığın altında sessiz bir satır ve onun
 * altında ince çizgi çizilir (sohbet ekranı); bu barda başlık küçük ve yarı
 * kalındır (kullanıcı 7 Kas 2026: sohbet başlığı çok büyüktü).
 */
@Composable
internal fun KovanUstBar(
    baslik: String,
    modifier: Modifier = Modifier,
    onGeri: (() -> Unit)? = null,
    solda: (@Composable () -> Unit)? = null,
    buyuk: Boolean = false,
    alt: (@Composable () -> Unit)? = null,
    sagda: @Composable RowScope.() -> Unit = {},
) {
    val kucuk = alt != null
    Column(modifier.fillMaxWidth().testTag("kovan_ust_bar")) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (kucuk) 56.dp else 64.dp)
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                onGeri != null -> KovanSimge(Icons.AutoMirrored.Filled.ArrowBack, "Geri", onGeri)
                solda != null -> solda()
                else -> Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.width(if (onGeri != null && !kucuk) 16.dp else 4.dp))
            Text(
                baslik,
                style = Ui3Type.govde.copy(
                    fontSize = when {
                        // 22sp: telefon 366dp genişlikte, üç simgeyle 26sp "Telekumanda"yı kesiyordu.
                        buyuk -> 22.sp
                        kucuk -> 18.sp
                        else -> 24.sp
                    },
                    lineHeight = if (kucuk) 23.sp else 30.sp,
                    fontWeight = when {
                        buyuk -> FontWeight.Bold
                        kucuk -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                    letterSpacing = if (buyuk) (-0.3).sp else 0.sp,
                ),
                color = Ui3Colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("baslik"),
            )
            sagda()
        }
        if (alt != null) {
            Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) { alt() }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ui3Colors.cizgiInce))
        }
    }
}

/** Kartın üstündeki sessiz bölüm adı ("Bilgisayarlar", "Ajanlar"). */
@Composable
internal fun KovanBolum(metin: String, modifier: Modifier = Modifier) {
    Text(
        metin,
        style = Ui3Type.govde.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        color = Ui3Colors.ink3,
        modifier = modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 10.dp),
    )
}

/** Satırları taşıyan beyaz kart: 20dp köşe, 1dp çizgi. */
@Composable
internal fun KovanKart(modifier: Modifier = Modifier, icerik: @Composable ColumnScope.() -> Unit) {
    val sekil = RoundedCornerShape(20.dp)
    Column(
        modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clip(sekil)
            .background(Ui3Colors.yuzey1)
            .border(1.dp, Ui3Colors.cizgi, sekil),
        content = icerik,
    )
}

/**
 * Kart satırı: solda simge (ya da monogram), başlık + sessiz alt satır, sağda
 * chevron. [ayrac] true ise altına metin hizasından başlayan ince çizgi çekilir.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KovanSatir(
    baslik: String,
    modifier: Modifier = Modifier,
    alt: String? = null,
    altRengi: Color = Ui3Colors.ink3,
    sol: (@Composable () -> Unit)? = null,
    sag: (@Composable () -> Unit)? = {
        Icon(Icons.Filled.ChevronRight, null, tint = Ui3Colors.ink3, modifier = Modifier.size(24.dp))
    },
    ayrac: Boolean = false,
    onUzunBas: (() -> Unit)? = null,
    onTikla: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp)
                .combinedClickable(onClick = onTikla, onLongClick = onUzunBas)
                .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sol != null) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { sol() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    baslik,
                    style = Ui3Type.govde.copy(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                    color = Ui3Colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!alt.isNullOrBlank()) {
                    Text(
                        alt,
                        style = Ui3Type.govde.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        color = altRengi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            sag?.invoke()
        }
        if (ayrac) {
            Box(
                Modifier
                    .padding(start = if (sol != null) 76.dp else 18.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Ui3Colors.cizgiInce),
            )
        }
    }
}

/** Satır başındaki simge, köşesinde durum noktası (Kovan'daki yeşil "bağlı"). */
@Composable
internal fun KovanSatirSimgesi(ikon: ImageVector, nokta: Color? = null) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(ikon, contentDescription = null, tint = Ui3Colors.ink3, modifier = Modifier.size(26.dp))
        if (nokta != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = 6.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Ui3Colors.yuzey1)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(nokta),
            )
        }
    }
}

/** Kovan'ın "CL" kutusu: açık zeminli, çizgili kare içinde vurgu renginde kısaltma. */
@Composable
internal fun KovanMonogram(metin: String, nokta: Color? = null) {
    val sekil = RoundedCornerShape(12.dp)
    Box(Modifier.size(44.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(sekil)
                .background(Ui3Colors.kuyu)
                .border(1.dp, Ui3Colors.cizgi, sekil),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                metin,
                style = Ui3Type.govde.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                color = Ui3Colors.vurgu,
            )
        }
        if (nokta != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Ui3Colors.yuzey1)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(nokta),
            )
        }
    }
}

/**
 * Uygulamanın işareti, "Yörünge" (7 Kas 2026): açık uçlu halka ve boşluğunda
 * nokta. Launcher simgesiyle aynı geometri (docs/logo/yorunge.svg, 432'lik
 * tuval: halka r=104, kalınlık 30, nokta r=27, açıklık 15°-85°). Renkler
 * temadan: halka mürekkep, nokta vurgu, iki temada da okunur.
 */
@Composable
internal fun TelekumandaIsareti(modifier: Modifier = Modifier, olcu: androidx.compose.ui.unit.Dp = 28.dp) {
    val halka = Ui3Colors.ink
    val nokta = Ui3Colors.vurgu
    androidx.compose.foundation.Canvas(modifier.size(olcu)) {
        // İşaretin dış sınırı merkezden 131 birim; 140'a sığdırıp optik kaydırma payı bırak.
        val f = size.minDimension / 280f
        val c = androidx.compose.ui.geometry.Offset(size.width / 2 - 6 * f, size.height / 2 + 6 * f)
        val r = 104f * f
        drawArc(
            color = halka,
            startAngle = -15f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 30f * f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        drawCircle(nokta, radius = 27f * f, center = androidx.compose.ui.geometry.Offset(c.x + 66.8f * f, c.y - 79.7f * f))
    }
}
