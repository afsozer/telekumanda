package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ApprovalInfo
import com.agent.bridge.ApprovalOption
import com.agent.bridge.ApprovalQuestion
import com.agent.bridge.QuestionAnswerDraft
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import dev.chrisbanes.haze.HazeState

/**
 * Onay bölgesi — composer'ın üstünde SABİT duran onay kartı.
 *
 * İKİ DURUM burada:
 *
 * 1. **Düz onay** (komut çalıştırma izni): başlık + komut + izin ver/reddet.
 * 2. **Seçenekli onay** ([ApprovalInfo.options] dolu): izin/ret dışında
 *    sağlayıcının verdiği şıklar — "bu oturumda hep izin ver" gibi.
 *
 * Üçüncü durum — **soru** ([ApprovalInfo.questions] dolu, AskUserQuestion) —
 * artık burada DEĞİL: [Ui3SoruAkisi] ile sohbet akışının içine çiziliyor.
 * Gerekçe orada.
 *
 * Sabit kalmasının sebebi ikisinin farkı: düz onay tek dokunuşluk ve kısa,
 * gözden kaçmaması için composer'ın hemen üstünde durması işe yarıyor.
 */
@Composable
internal fun Ui3OnayBolgesi(
    onay: ApprovalInfo,
    hazeState: HazeState,
    calismaDizini: String?,
    onIzin: () -> Unit,
    onRet: () -> Unit,
    onSecenek: (ApprovalOption) -> Unit,
    onHepsineIzin: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    DuzOnayKarti(
        hazeState = hazeState,
        baslik = onay.summary.ifBlank { onay.tool },
        komut = onay.description.ifBlank { null },
        calismaDizini = calismaDizini,
        secenekler = onay.options,
        onIzin = onIzin,
        onRet = onRet,
        onSecenek = onSecenek,
        onHepsineIzin = onHepsineIzin,
        modifier = modifier,
    )
}

/**
 * Soru kartları (AskUserQuestion) — SOHBET AKIŞININ İÇİNDE, son öğe olarak.
 *
 * Önce composer'ın üstünde sabit bir blok olarak çiziliyordu ve kullanıcı
 * 20.08.2026'da bildirdi: kartlar ekranın altına çakılı olduğu için sohbeti
 * daraltıyor, geriye kalan ince şeritten kaydırınca metin kartların ALTINA
 * giriyordu. Soru bloğu doğası gereği uzun (4 soru × 4 şık), yani sabit
 * durduğunda okunacak alandan kalıcı olarak çalıyor.
 *
 * ui2 bunu baştan doğru yapmış: kartlar `LazyColumn`'un öğeleri
 * (`ChatRootScreen`, `__question_submit__` / soru öğeleri). Akışın parçası
 * olunca yukarı kaydırıp soruyu doğuran mesajları rahatça okuyabiliyorsun,
 * kart da yerinde duruyor.
 *
 * İKİ SONUÇ:
 * - Kendi içinde kaydırma ve yükseklik tavanı YOK; artık gerekmiyor, blok
 *   sohbetle birlikte kayıyor. Tavan sabit düzendeki mecburiyetti.
 * - GERÇEK CAM DEĞİL ([GlassLikeSurface]): kart akışın İÇİNDE, akış da
 *   `hazeSource`. Cam kendi kaynağını örnekleyemez (haze kuralı: cam kaynağın
 *   dışında kardeş olmalı), blur sessizce devre dışı kalırdı.
 *
 * Taslak (hangi şık seçili) [QuestionAnswerDraft] ile tutulur — shared'daki saf
 * veri sınıfı, ui2 de aynısını kullanıyor. Seçim mantığı iki arayüzde ayrışmasın.
 * Taslağın kendisi bu bileşenin DIŞINDA yaşamalı: `LazyColumn` öğesi ekrandan
 * çıkınca içindeki `remember` atılır ve seçimler silinirdi.
 */
@Composable
internal fun Ui3SoruAkisi(
    sorular: List<ApprovalQuestion>,
    taslak: QuestionAnswerDraft,
    onTaslak: (QuestionAnswerDraft) -> Unit,
    onCevapla: () -> Unit,
    onRet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        sorular.forEach { soru ->
            SoruKarti(
                soru = soru,
                seciliIdler = taslak.selectedOptionIds(soru.id),
                onSec = { secenek -> onTaslak(taslak.select(soru, secenek)) },
            )
        }
        GonderKarti(
            cevaplanan = taslak.answeredCount(sorular),
            toplam = sorular.size,
            tamam = taslak.isComplete(sorular),
            onGonder = onCevapla,
            onRet = onRet,
        )
    }
}

/**
 * Tek soru: başlık rozeti + soru metni + şıklar.
 *
 * Çoklu seçimde işaret kare, tekli seçimde daire — hangi soruda birden fazla
 * seçebileceğin şekilden okunur (ui2'nin `UserInputCard`'ıyla aynı ayrım).
 */
@Composable
private fun SoruKarti(
    soru: ApprovalQuestion,
    seciliIdler: Set<String>,
    onSec: (ApprovalOption) -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth().testTag("soru_karti"),
        shape = RoundedCornerShape(Ui3Tokens.r21),
    ) {
        Column(
            Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        ) {
            if (soru.header.isNotBlank()) {
                Text(
                    soru.header.uppercase(),
                    style = Ui3Type.etiket,
                    color = Ui3Colors.vurguHi,
                    modifier = Modifier
                        .clip(Ui3Tokens.pill)
                        .background(Ui3Colors.yuzey2)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(soru.question, style = Ui3Type.govde, color = Ui3Colors.ink)
            soru.options.forEach { secenek ->
                val secili = secenek.id in seciliIdler
                SikSatiri(
                    secenek = secenek,
                    secili = secili,
                    coklu = soru.multiple,
                    onTikla = { onSec(secenek) },
                )
            }
        }
    }
}

@Composable
private fun SikSatiri(
    secenek: ApprovalOption,
    secili: Boolean,
    coklu: Boolean,
    onTikla: () -> Unit,
) {
    val sekil = if (coklu) RoundedCornerShape(6.dp) else CircleShape
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(if (secili) Ui3Colors.vurgu.copy(alpha = 0.16f) else Ui3Colors.yuzey1)
            .border(
                1.dp,
                if (secili) Ui3Colors.vurguHi.copy(alpha = 0.45f) else Ui3Colors.cizgi,
                RoundedCornerShape(Ui3Tokens.r12),
            )
            .clickable(onClick = onTikla)
            .padding(horizontal = 11.dp, vertical = 10.dp)
            .testTag(if (secili) "sik_secili" else "sik"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(17.dp)
                .clip(sekil)
                .background(if (secili) Ui3Colors.vurguHi else Ui3Colors.yuzey2)
                .border(1.dp, if (secili) Ui3Colors.vurguHi else Ui3Colors.cizgi, sekil),
            contentAlignment = Alignment.Center,
        ) {
            if (secili) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Seçili",
                    tint = Ui3Colors.birincilMurekkep,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                secenek.label,
                style = Ui3Type.govde,
                color = Ui3Colors.ink,
                fontWeight = if (secili) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (secenek.description.isNotBlank()) {
                Text(secenek.description, style = Ui3Type.alt, color = Ui3Colors.ink2)
            }
        }
    }
}

/**
 * Gönder kartı: kaç sorunun cevaplandığını yazar ve hepsi tamamlanınca
 * göndermeyi açar. Eksikken tuş kapalı — yarım cevap göndermek sağlayıcıda
 * hataya düşüyordu.
 */
@Composable
private fun GonderKarti(
    cevaplanan: Int,
    toplam: Int,
    tamam: Boolean,
    onGonder: () -> Unit,
    onRet: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth().testTag("soru_gonder"),
        shape = RoundedCornerShape(Ui3Tokens.r21),
        tint = GlassTint.Amber,
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$cevaplanan / $toplam cevaplandı",
                style = Ui3Type.alt,
                color = if (tamam) Ui3Colors.done else Ui3Colors.attention,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Ui3Colors.rose.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(onClick = onRet)
                    .padding(horizontal = 15.dp, vertical = 9.dp)
                    .testTag("soru_ret"),
            ) {
                Text("Vazgeç", style = Ui3Type.alt, color = Ui3Colors.rose)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (tamam) Ui3Colors.birincilZemin else Ui3Colors.yuzey2)
                    .clickable(enabled = tamam, onClick = onGonder)
                    .padding(horizontal = 17.dp, vertical = 9.dp)
                    .testTag("soru_gonder_tus"),
            ) {
                Text(
                    "Gönder",
                    style = Ui3Type.alt,
                    color = if (tamam) Ui3Colors.birincilMurekkep else Ui3Colors.ink3,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DuzOnayKarti(
    hazeState: HazeState,
    baslik: String,
    komut: String?,
    calismaDizini: String?,
    secenekler: List<ApprovalOption>,
    onIzin: () -> Unit,
    onRet: () -> Unit,
    onSecenek: (ApprovalOption) -> Unit,
    onHepsineIzin: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().testTag("onay_karti"),
        shape = RoundedCornerShape(Ui3Tokens.r21),
        tint = GlassTint.Amber,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8)) {
            Text("ONAY BEKLİYOR", style = Ui3Type.etiket, color = Ui3Colors.attention)
            Text(baslik, style = Ui3Type.govde, color = Ui3Colors.ink)
            if (komut != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Ui3Tokens.r12))
                        .background(Ui3Colors.kuyu)
                        .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r12))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    // "Kod, yol, komut, log HER ZAMAN mono + yüzey kutusunda"
                    // (anayasa v2 bölüm 4).
                    Text(
                        komut,
                        style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                        color = Ui3Colors.vurguHi,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!calismaDizini.isNullOrBlank()) {
                Text(
                    calismaDizini,
                    style = Ui3Type.etiket.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            // Sağlayıcının verdiği ek şıklar (örn. "bu oturumda hep izin ver"
            // ya da ACP'nin allow_once/allow_always seçenekleri).
            secenekler.forEach { secenek ->
                SikSatiri(secenek = secenek, secili = false, coklu = false) { onSecenek(secenek) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ui3Colors.birincilZemin)
                        .clickable(onClick = onIzin)
                        .padding(horizontal = 19.dp, vertical = 9.dp)
                        .testTag("onay_kabul"),
                ) {
                    Text(
                        "İzin ver",
                        style = Ui3Type.govde,
                        color = Ui3Colors.birincilMurekkep,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Ui3Colors.rose.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onRet)
                        .padding(horizontal = 17.dp, vertical = 9.dp)
                        .testTag("onay_ret"),
                ) {
                    Text("Reddet", style = Ui3Type.govde, color = Ui3Colors.rose)
                }
                if (onHepsineIzin != null) {
                    Text(
                        "hep izin ver",
                        style = Ui3Type.alt,
                        color = Ui3Colors.ink2,
                        modifier = Modifier
                            .clip(Ui3Tokens.pill)
                            .clickable(onClick = onHepsineIzin)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("onay_hep"),
                    )
                }
            }
        }
    }
}
