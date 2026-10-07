package com.agent.bridge.ui3.material

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.agent.bridge.ui2.components.LocalUi2KartYuzeyi
import com.agent.bridge.ui2.components.Ui2KartYuzeyi
import com.agent.bridge.ui2.theme.LocalUi2Colors
import com.agent.bridge.ui2.theme.Ui2Colors
import com.agent.bridge.ui2.theme.Ui2Theme
import com.agent.bridge.ui3.theme.LocalUi3Palet
import com.agent.bridge.ui3.theme.Ui3Palet
import com.agent.bridge.ui3.theme.Ui3Tokens

/**
 * ui2'den ödünç alınan ekranları ui3'ün MALZEMESİYLE çizer.
 *
 * Önce bu ekranlar düz `Ui2Theme` ile sarılıyordu; işlev tamdı ama görünüm
 * ui2'ydi — Dosyalar, Ayarlar ve Operasyon'da hiç cam yoktu ve kullanıcı bunu
 * bir kısıt olarak kabul etmedi (haklı olarak: kabul edilecek bir şey değil,
 * çözülecek bir şey).
 *
 * Ekranları yeniden yazmak yerine PALETİ değiştiriyoruz. ui2 bileşenleri bütün
 * renklerini `LocalUi2Colors`tan okuyor; o local'e ui3 paletinden türetilmiş
 * bir [Ui2Colors] verildiğinde:
 *
 *  - `bg` ve `chatBg` SAYDAM olur → arkadaki ui3 mesh'i görünür,
 *  - `surface` ui3'ün cam yüzeyi olur → kartlar buzlu cama döner,
 *  - çizgi, mürekkep, vurgu ve durum renkleri ui3 paletinden gelir.
 *
 * Yerleşim, davranış ve mantık ui2'de kalır; değişen yalnız malzeme. Cam
 * BLUR'u yok (bu ekranlar `hazeSource` ağacının içinde ve kaydırılıyorlar —
 * anayasa v2 §1.3 kaydırılan yüzeyde gerçek camı zaten yasaklıyor); görünen
 * şey ui3'ün `GlassLikeSurface`'iyle aynı: ton + kenar + saydamlık.
 */
@Composable
fun Ui3OduncKap(icerik: @Composable () -> Unit) {
    val palet = LocalUi3Palet.current
    val camPalet = remember(palet) { ui2CamPaleti(palet) }
    // Ui2Theme MaterialTheme'i (tipografi, şekiller, m3 renk şeması) kuruyor;
    // üstüne kendi renk local'imizi geçiyoruz.
    Ui2Theme(dark = palet.koyuMu) {
        CompositionLocalProvider(
            LocalUi2Colors provides camPalet,
            LocalUi2KartYuzeyi provides camKart,
            content = icerik,
        )
    }
}

/**
 * ui2'nin `SurfaceCard`ının ui3 gövdesi.
 *
 * Yalnız palet değiştirmek yetmedi: kart saydamlaşıyordu ama düz dolgu + düz
 * kenar olarak kalıyordu, yani "ui2 kartı soluk renkte" gibi duruyordu. Cam
 * dilini veren şey rim gradyanı, spekular hat ve gren — bunlar
 * [GlassLikeSurface]'te.
 *
 * BLUR YOK, bilinçli: bu kartların hepsi kaydırılan listelerin içinde ve her
 * karta `hazeEffect` koymak her kaydırma karesinde blur'u yeniden hesaplatır
 * (anayasa v2 §1.3). Görünen fark, sohbetteki kullanıcı balonuyla aynı.
 *
 * `clip` tıklamadan ÖNCE: yoksa dalga efekti (ripple) yuvarlak köşenin dışına
 * taşıyor. `GlassLikeSurface` içeride tekrar kırpıyor, zararsız.
 */
@OptIn(ExperimentalFoundationApi::class)
private val camKart: Ui2KartYuzeyi = { modifier, onTikla, onUzunBas, icerik ->
    val sekil = RoundedCornerShape(Ui3Tokens.r18)
    val tiklama = when {
        onUzunBas != null -> Modifier.combinedClickable(
            onClick = onTikla ?: {},
            onLongClick = onUzunBas,
        )
        onTikla != null -> Modifier.clickable(onClick = onTikla)
        else -> Modifier
    }
    GlassLikeSurface(
        modifier = modifier.fillMaxWidth().clip(sekil).then(tiklama),
        shape = sekil,
    ) {
        Box(Modifier.fillMaxWidth()) { icerik() }
    }
}

/**
 * ui3 paletinden ui2 renk sözleşmesine köprü.
 *
 * Alan eşlemesi ANLAMA göre yapıldı, isme göre değil: ui2'nin `surface`'ı
 * kart/sheet yüzeyi, ui3'te bunun karşılığı `yuzey1`. `surface2` bir tık daha
 * belirgin yüzey (seçili satır) — ui3'te `yuzey2`.
 */
private fun ui2CamPaleti(p: Ui3Palet) = Ui2Colors(
    // Zeminler saydam: ui3'ün mesh'i ve camı arkadan görünsün.
    bg = Color.Transparent,
    chatBg = Color.Transparent,
    navBg = Color.Transparent,
    // surface CAM (saydam): kartlar buzlu, arkasından mesh süzülür.
    surface = p.yuzey1,
    // surface2 OPAK olmak ZORUNDA. ui2'nin `LoadingSkeleton`'ı bu rengi
    // `copy(alpha = …)` ile kullanıyor ve `copy` alfayı EKLEMEZ, DEĞİŞTİRİR:
    // %6'lık saydam bir renk orada %90 opak bir bloğa dönüşüyordu — Dosyalar
    // yüklenirken ekranda koyu gri kutular çıktı (cihazda görüldü). Seçili
    // satır / aktif sekme zemini olarak da opak olması zaten doğru.
    surface2 = if (p.koyuMu) Color(0xFF1A1D22) else Color(0xFFE6E9EF),
    // Modal seçiciler (model seçici gibi) OPAK: saydam cam `surface`
    // modal'da arkadaki ekranı gösterip seçim anında dikkat dağıtıyordu
    // (kullanıcı, 17.08.2026). surface2'den bir tık ayrık ki seçili satır
    // sheet zemininde kaybolmasın.
    sheetSurface = if (p.koyuMu) Color(0xFF14171C) else Color(0xFFEDEFF4),
    line = p.cizgi,
    lineStrong = p.vurguHi.copy(alpha = 0.45f),
    ink = p.ink,
    ink2 = p.ink2,
    ink3 = p.ink3,
    accent = p.vurguHi,
    onAccent = p.birincilMurekkep,
    pillFill = p.vurgu.copy(alpha = 0.20f),
    pillOn = p.vurguHi,
    codeBg = p.kuyu,
    running = p.running,
    attention = p.attention,
    done = p.done,
    danger = p.danger,
)
