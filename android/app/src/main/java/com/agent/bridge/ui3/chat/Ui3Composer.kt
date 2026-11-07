package com.agent.bridge.ui3.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.QueuedPrompt
import com.agent.bridge.ui2.chat.queuedPromptPreview
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.layout.Spacer

// Kovan ölçüleri (7 Kas 2026): 58dp kapsül, 46dp yuvarlak gönder/durdur.
private val KOVAN_YARICAP = 29.dp
private val GONDER = 46.dp
// Durdurun çerçeve ışığı yolu bu yarıçapla kuruluyor; GONDER'in yarısı = daire.
private val DUGME_YARICAP_DP = 23.dp
private val GONDER_YARICAP = RoundedCornerShape(DUGME_YARICAP_DP)

// Metin alanının üst sınırı: yaklaşık 5 satır; uzun metin alanın içinde kayar.
private val METIN_MAKS_Y = 132.dp

/**
 * Kovan composer'ı (7 Kas 2026): tek kapsül; solda "＋" (ek), ortada yazı
 * alanı, sağda yuvarlak gönder. Eski iki katlı şerit (model çipi, tur
 * ayarları, yenile, kullanım, klasör) kalktı; onlar sohbet üst barındaki tur
 * ayarları menüsünde. "＋" önce menüye taşınmıştı, kullanıcı geri istedi:
 * ek eklemek sık iş, menüye gömülmez.
 *
 * Tur sürerken composer KİLİTLENMEZ: gönderin yerini durdur alır, metin
 * yazılmışsa ve sağlayıcı destekliyorsa solunda "yönlendir" şimşeği çıkar.
 */
@Composable
internal fun Ui3Composer(
    metin: String,
    onMetin: (String) -> Unit,
    calisiyor: Boolean,
    gonderilebilir: Boolean,
    onGonder: () -> Unit,
    onDurdur: () -> Unit,
    onEk: () -> Unit,
    modifier: Modifier = Modifier,
    // Tur ortası canlı gönderim; null ise tuş hiç çizilmez.
    onYonlendir: (() -> Unit)? = null,
) {
    val sekil = RoundedCornerShape(KOVAN_YARICAP)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp, max = METIN_MAKS_Y + 16.dp)
            .clip(sekil)
            .background(Ui3Colors.yuzey1)
            .border(1.dp, Ui3Colors.cizgi, sekil)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("composer"),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(GONDER)
                .clip(CircleShape)
                .clickable(onClick = onEk)
                .testTag("composer_ek"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Dosya veya görsel ekle",
                tint = Ui3Colors.ink2,
                modifier = Modifier.size(26.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 46.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val alanStili = Ui3Type.yazi.copy(fontSize = 17.sp, lineHeight = 23.sp)
            if (metin.isEmpty()) {
                Text("Mesaj yaz", style = alanStili, color = Ui3Colors.ink3)
            }
            // DIŞ SÖZLEŞME String, İÇERİDE TextFieldValue: metin dıştan
            // değişince (slash komutu seçmek, gönderimden sonra temizlemek)
            // imleç metnin SONUNA konur (ui2 `Composer.kt` ile aynı çözüm).
            var alanDegeri by remember { mutableStateOf(TextFieldValue(metin)) }
            // Yukarı/aşağı okun hangi satıra gideceği yalnız buradan bilinir.
            var yazYerlesimi by remember { mutableStateOf<TextLayoutResult?>(null) }
            if (alanDegeri.text != metin) {
                alanDegeri = TextFieldValue(metin, TextRange(metin.length))
            }
            BasicTextField(
                value = alanDegeri,
                onValueChange = {
                    alanDegeri = it
                    if (it.text != metin) onMetin(it.text)
                },
                textStyle = LocalTextStyle.current.merge(alanStili.copy(color = Ui3Colors.ink)),
                cursorBrush = SolidColor(Ui3Colors.vurgu),
                // Dıştan verticalScroll verilmez: alanın kendi kaydırması
                // imleci görünür tutar, yükseklik üst sınırla kısıtlı.
                onTextLayout = { yazYerlesimi = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .ui3OkTuslari({ alanDegeri }, { alanDegeri = it }, { yazYerlesimi })
                    .testTag("composer_alan"),
            )
        }
        if (calisiyor) {
            if (gonderilebilir && onYonlendir != null) {
                Box(
                    Modifier
                        .size(GONDER)
                        .clip(CircleShape)
                        .background(Ui3Colors.vurgu.copy(alpha = 0.14f))
                        .clickable(onClick = onYonlendir)
                        .testTag("composer_yonlendir"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Yönlendir (turu kes)",
                        tint = Ui3Colors.vurgu,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            DurdurTusu(onDurdur)
        } else {
            Box(
                Modifier
                    .size(GONDER)
                    .clip(CircleShape)
                    // Kovan: boşken soluk vurgu, yazınca tam vurgu.
                    .background(if (gonderilebilir) Ui3Colors.vurgu else Ui3Colors.vurgu.copy(alpha = 0.35f))
                    .clickable(enabled = gonderilebilir, onClick = onGonder)
                    .testTag("composer_gonder"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "Gönder",
                    tint = Ui3Colors.birincilMurekkep.copy(alpha = if (gonderilebilir) 1f else 0.7f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * ÇERÇEVEDE DOLAŞAN IŞIK — "bu tuş şu an bir işin ortasında" demenin ui3'çe
 * söylenişi.
 *
 * Uydurulmuş bir efekt değil: mesaj rayındaki akan ışıkla (`RayliSatir`) aynı
 * renk ailesi, aynı tempo (1500ms, doğrusal, baştan başlar) ve aynı fikir.
 * İkisi aynı anda ekranda olabildiği için tempoyu paylaşmaları önemliydi —
 * farklı periyot iki ayrı saat gibi çarpışırdı.
 *
 * Neden yay değil YOL: tuşlar daire değil, [DUGME_YARICAP_DP] yarıçaplı
 * yuvarlatılmış kare; `drawArc` bu kenara oturmuyor. Kenarın kendisi `Path`
 * olarak kuruluyor ve `PathMeasure` ile bir parçası kesiliyor. Parça kuyruk
 * gibi sönsün diye tek çizgi değil, opaklığı artan altı dilim: baş parlak,
 * kuyruk sönük.
 *
 * [aktif] false iken `rememberInfiniteTransition` HİÇ kurulmuyor, yani kare
 * üretimi de yok. Anayasa v2 §5: sürekli animasyona hakkı olan tek şey devam
 * eden bir iştir; boştaki bir tuşta dönen ışık gürültüdür.
 */
@Composable
private fun Modifier.dolasanIsik(aktif: Boolean, renk: Color, kalinlik: Dp): Modifier {
    if (!aktif) return this
    // KUYRUĞUN OPAKLIK ARALIĞI TEMAYA GÖRE (20.08.2026, kullanıcı bildirdi:
    // "aydınlık modda kendini hiç belli etmiyor").
    //
    // Koyu zeminde bu gerçekten bir IŞIK: çevresinden PARLAK olduğu için göz
    // onu anında yakalıyor, 0.90 opaklık fazlasıyla yetiyor. Açık zeminde
    // "beyazdan parlak" diye bir şey yok — aynı etkiyi ancak KOYULUK farkı
    // verebilir ve o da tam opaklık ister. Yarı saydam bir koyu ton, altındaki
    // açık zeminle karışıp fısıltıya dönüyordu.
    val koyuTema = Ui3Colors.palet.koyuMu
    val tabanOpaklik = if (koyuTema) 0.14f else 0.08f
    val tavanOpaklik = if (koyuTema) 0.90f else 1f
    val gecis = rememberInfiniteTransition(label = "dolasan-isik")
    val ilerleme by gecis.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "dolasan-isik-ilerleme",
    )
    // Path/PathMeasure her karede yeniden kurulmasın: çizim sıcak yolda.
    val yol = remember { Path() }
    val olcer = remember { PathMeasure() }
    val parca = remember { Path() }
    return drawBehind {
        val kalinlikPx = kalinlik.toPx()
        val yaricap = DUGME_YARICAP_DP.toPx()
        yol.reset()
        // Çerçeve kalınlığının YARISI kadar içeri: çizgi kenarın ortasına
        // otursun, dışarı taşıp kırpılmasın.
        yol.addRoundRect(
            RoundRect(
                Rect(
                    kalinlikPx / 2f,
                    kalinlikPx / 2f,
                    size.width - kalinlikPx / 2f,
                    size.height - kalinlikPx / 2f,
                ),
                CornerRadius(yaricap - kalinlikPx / 2f),
            ),
        )
        olcer.setPath(yol, true)
        val boy = olcer.length
        if (boy <= 0f) return@drawBehind
        // Kuyruk çevrenin dörtte biri: daha uzunu "yürüyen ışık" değil "dönen
        // çerçeve" gibi okunuyordu.
        val kuyruk = boy * 0.25f
        val bas = ilerleme * boy
        val dilim = 6
        for (i in 0 until dilim) {
            val opaklik = tabanOpaklik + (tavanOpaklik - tabanOpaklik) * ((i + 1f) / dilim)
            val basla = bas + kuyruk * (i / dilim.toFloat())
            val bitir = bas + kuyruk * ((i + 1f) / dilim)
            parca.reset()
            // Çevre başa sarabilir; `PathMeasure` sarmayı kendisi yapmadığı
            // için parça iki kesitte alınır.
            if (bitir <= boy) {
                olcer.getSegment(basla, bitir, parca, true)
            } else if (basla >= boy) {
                olcer.getSegment(basla - boy, bitir - boy, parca, true)
            } else {
                olcer.getSegment(basla, boy, parca, true)
                olcer.getSegment(0f, bitir - boy, parca, true)
            }
            drawPath(
                parca,
                color = renk.copy(alpha = opaklik),
                style = Stroke(width = kalinlikPx, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Durdur tuşu — cyan halka, içi boş. "Devam eden bir şeyi kes" jesti, başlatan
 * gradyan diskle aynı görsel ağırlıkta olmamalı; o yüzden dolgu değil çerçeve.
 *
 * Çerçevede dolaşan ışık [dolasanIsik]'tan geliyor (kullanıcı isteği
 * 18.08.2026); gerekçesi ve ölçüleri orada. Burada `aktif = true` sabit çünkü
 * bu bileşen zaten yalnız tur sürerken besteleniyor (çağıranda
 * `if (calisiyor)`) — tur bitince ağaçtan düşüyor, animasyon da onunla.
 */
@Composable
private fun DurdurTusu(onDurdur: () -> Unit) {
    val halka = Ui3Colors.running
    // SABİT HALKA AÇIK TEMADA SOLUYOR — hareketin görünmesinin ŞARTI bu.
    //
    // Dolaşan ışıkla sabit çerçeve AYNI renk. Koyu temada sorun değil: halka
    // sönük bir hat, ışık onun üstünde parlıyor. Açık temada `running` zaten
    // KOYU bir ton (#176B64) ve 0.60 alfayla çizilen halka fiilen dolaşan
    // parça kadar koyu oluyordu — ışık kendi çerçevesinin içinde kayboluyordu
    // (kullanıcı bildirdi 20.08.2026). Halkayı geri çekince fark 0.20 → 1.0'a
    // çıkıyor. Tuş yalnız tur sürerken çiziliyor, yani soluk halka tek başına
    // hiç görünmüyor; her zaman ışıkla birlikte.
    val halkaAlfa = if (Ui3Colors.palet.koyuMu) 0.60f else 0.20f
    Box(
        Modifier
            .size(GONDER)
            .clip(GONDER_YARICAP)
            .background(halka.copy(alpha = 0.06f))
            .border(1.5.dp, halka.copy(alpha = halkaAlfa), GONDER_YARICAP)
            .dolasanIsik(aktif = true, renk = halka, kalinlik = 1.5.dp)
            .clickable(onClick = onDurdur)
            .testTag("composer_durdur"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(halka),
        )
    }
}

/**
 * Bekleyen prompt kuyruğu — composer'ın ÜSTÜNDE, kendi cam kartında.
 *
 * Kuyruk istemci tarafında ve kalıcı: tur sürerken gönder tuşuna basılan her
 * prompt buraya düşer, tur bitince sıradaki kendiliğinden gider. Kapalıyken
 * yalnız ilk sıradaki tek satır görünür (composer'ın önüne geçmesin), dokununca
 * bütün sıra açılır ve öğeler tek tek çıkarılabilir — ui2'deki panelin ui3
 * karşılığı, özet metni ([queuedPromptPreview]) oradan ödünç.
 */
@Composable
internal fun Ui3KuyrukPaneli(
    hazeState: HazeState,
    kuyruk: List<QueuedPrompt>,
    onCikar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (kuyruk.isEmpty()) return
    // Anahtar ilk prompt: sıra baştan tükenince panel kendiliğinden kapanır,
    // yeni gelen prompt açık paneli kapatmaz.
    var acik by remember(kuyruk.first().id) { mutableStateOf(false) }
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().testTag("kuyruk_paneli"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { acik = !acik }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            ) {
                Text("Sıra · ${kuyruk.size}", style = Ui3Type.alt, color = Ui3Colors.vurguHi)
                Text(
                    queuedPromptPreview(kuyruk.first()),
                    modifier = Modifier.weight(1f),
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (acik) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (acik) "Sırayı daralt" else "Sırayı aç",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (acik) {
                kuyruk.forEachIndexed { sira, prompt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    ) {
                        Text(
                            "${sira + 1}. ${queuedPromptPreview(prompt)}",
                            modifier = Modifier.weight(1f),
                            style = Ui3Type.alt,
                            color = Ui3Colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 28dp kutu, ama hedef 28dp değil: satırın geri kalanı
                        // tıklanabilir DEĞİL (düz metin), o yüzden Compose'un
                        // 48dp'ye kadar en yakın düğmeye verme davranışı burada
                        // çalışıyor. Büyütmek her kuyruk satırını uzatırdı.
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onCikar(prompt.id) }
                                .testTag("kuyruk_cikar"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Sıradan çıkar",
                                tint = Ui3Colors.ink3,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
