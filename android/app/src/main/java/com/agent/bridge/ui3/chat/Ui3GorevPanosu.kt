package com.agent.bridge.ui3.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.OpencodeTodo
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Bağlam doluluğunun "artık umursa" eşiği. ALTINDA HİÇBİR ŞEY ÇİZİLMEZ;
// üstünde kehribar uyarı + Sıkıştır aksiyonu. 80: opencode'un kendi otomatik
// özetlemesi devreye girmeden önce kullanıcının karar verebileceği son aralık.
internal const val UI3_BAGLAM_ESIGI = 80

/** Doluluk "artık umursa" eşiğini aştı mı? null = köprü ölçemiyor -> uyarı yok. */
internal fun baglamDoldu(baglamYuzdesi: Int?): Boolean =
    baglamYuzdesi != null && baglamYuzdesi >= UI3_BAGLAM_ESIGI

/**
 * Pano ÇİZİLİR Mİ?
 *
 * Kural: todo varsa her zaman; todo yokken yalnız doluluk eşiği aşılmışsa.
 * Boşta ve listesiz bir oturumda ekranda kalıcı bir kart bırakmak, sohbetin en
 * dar yerinden (composer'ın üstü) sürekli yer yemek demek — bu depoda RunPod
 * hapı tam bu yüzden kaldırıldı (25.08.2026, kullanıcı: "eğreti duruyor").
 *
 * SÜREKLİ "Bağlam %X" ÇUBUĞU KALDIRILDI (25.08.2026): aynı sayı zaten yukarıda,
 * adada duruyordu ve pano ikinci bir kalıcı gösterge olarak aynı bilgiyi ikinci
 * kez çiziyordu. Geriye yalnız EYLEM DEĞERİ olan hâli kaldı — eşik aşılınca
 * kehribar uyarı + Sıkıştır. Bu yüzden `calisiyor` artık görünürlük ölçütü
 * değil: turun sürüyor olması tek başına gösterilecek bir şey üretmiyordu.
 */
internal fun gorevPanosuGorunur(todos: List<OpencodeTodo>, baglamYuzdesi: Int?): Boolean =
    todos.isNotEmpty() || baglamDoldu(baglamYuzdesi)

/** Tamamlanan madde sayısı. İptal edilen de "artık beklemiyor" sayılır. */
internal fun gorevPanosuBiten(todos: List<OpencodeTodo>): Int = todos.count { it.bitti }

/**
 * Panonun tek satırlık özeti: "3/7 · <şu anki maddenin metni>".
 *
 * Metin seçimi sırayla: in_progress madde > ilk bitmemiş madde > "" (hepsi
 * bitti). "İlk bitmemiş"e düşmek şart — bazı ajanlar todowrite'ı yalnız
 * pending/completed ile kullanıyor, in_progress'i hiç işaretlemiyor; o durumda
 * satır boş kalsaydı pano yalnız sayı gösteren bir kutuya inerdi.
 *
 * Compose'un DIŞINDA: uzun otonom koşuyu izlemenin bütün değeri bu satırda ve
 * cihazsız sınanabilmesi gerekiyor.
 */
internal fun gorevPanosuOzeti(todos: List<OpencodeTodo>): String {
    if (todos.isEmpty()) return ""
    val sayac = "${gorevPanosuBiten(todos)}/${todos.size}"
    val odak = todos.firstOrNull { it.suradaki } ?: todos.firstOrNull { !it.bitti }
    return if (odak == null) sayac else "$sayac · ${odak.content}"
}

/**
 * Görev panosu — uzun otonom koşuyu transkript kaydırmadan izlemek için.
 *
 * KAPALIYKEN TEK SATIR. Kart composer'ın üstünde duruyor ve oradaki her dp
 * sohbetten çalınıyor; pano bir "durum çubuğu" olmalı, bir ekran değil. Tam
 * liste dokununca AÇILIR — ayrı bir sheet DEĞİL, çünkü sheet açmak sohbeti
 * tamamen örtüyor ve pano tam da "akışa bakarken yanında dursun" diye var.
 *
 * BLUR'SUZ CAM ([GlassLikeSurface]): kartın altından keskin sohbet metni
 * geçiyor ama kart kendi dolgusunu taşıyor ve tek satır yüksekliğinde —
 * `GlassSurface`in blur maliyeti her akış karesinde ödenirdi. Genişleyince de
 * aynı yüzey: açılıp kapanan bir kartın malzemesi değişmemeli.
 *
 * HAREKET BÜTÇESİ: yalnız listenin açılıp kapanması. Nabız/parıltı YOK — pano
 * gün boyu ekranda kalan bir şey. (Doluluk çubuğunun akan animasyonu çubukla
 * birlikte kalktı; bkz. `gorevPanosuGorunur`.)
 */
@Composable
internal fun Ui3GorevPanosu(
    todos: List<OpencodeTodo>,
    baglamYuzdesi: Int?,
    calisiyor: Boolean,
    onSikistir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!gorevPanosuGorunur(todos, baglamYuzdesi)) return

    var acik by remember { mutableStateOf(false) }
    val dolu = baglamDoldu(baglamYuzdesi)

    GlassLikeSurface(
        modifier = modifier.fillMaxWidth().testTag("gorev_panosu"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
        tint = if (dolu) GlassTint.Amber else GlassTint.Notr,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (todos.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { acik = !acik }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                ) {
                    Text(
                        gorevPanosuOzeti(todos),
                        modifier = Modifier.weight(1f).testTag("pano_ozet"),
                        style = Ui3Type.alt,
                        color = Ui3Colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // BAĞLAM YALNIZ EŞİK ÜSTÜNDE. Altında hiçbir şey çizilmiyor: aynı
            // yüzde adada zaten duruyor ve pano onun kopyası olmamalı. Burada
            // kalan tek şey EYLEM: "doldu" uyarısı ve onu çözen tuş.
            if (dolu) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = if (todos.isEmpty()) 9.dp else 0.dp,
                            bottom = 9.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                ) {
                    Text(
                        "Bağlam %$baglamYuzdesi dolu",
                        modifier = Modifier.weight(1f).testTag("pano_baglam"),
                        style = Ui3Type.rozet,
                        color = Ui3Colors.amber,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // SIKIŞTIR yalnız tur BİTMİŞKEN. Köprü tur sürerken
                    // özetlemeyi zaten reddediyor; tuşu o an göstermek
                    // kullanıcıya çalışmayan bir düğme sunmak olurdu.
                    if (!calisiyor) {
                        Text(
                            "Sıkıştır",
                            modifier = Modifier
                                .clip(Ui3Tokens.pill)
                                .clickable(onClick = onSikistir)
                                .background(Ui3Colors.amber.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                .testTag("pano_sikistir"),
                            style = Ui3Type.rozet,
                            color = Ui3Colors.amber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = acik && todos.isNotEmpty(),
                enter = expandVertically(tween(220)),
                exit = shrinkVertically(tween(180)),
            ) {
                Column(
                    Modifier
                        // Uzun listede kart bütün ekranı yemesin: pano kendi
                        // içinde kayar, composer boyunu korur (slash listesinde
                        // aynı tuzağa düşülmüştü).
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, top = Ui3Tokens.s8, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    todos.forEach { PanoSatiri(it) }
                }
            }
        }
    }
}

@Composable
private fun PanoSatiri(todo: OpencodeTodo) {
    val renk = when {
        todo.status == "completed" -> Ui3Colors.done
        todo.status == "cancelled" -> Ui3Colors.ink3
        todo.suradaki -> Ui3Colors.running
        else -> Ui3Colors.ink3
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        // Durum noktası — ikon yerine renk. Tamamlanan içi dolu, süren halka,
        // bekleyen soluk. Metnin başına emoji koymak satırı Türkçe metinde
        // hizasız bırakıyordu.
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(if (todo.bitti || todo.suradaki) renk else renk.copy(alpha = 0.45f)),
        )
        Text(
            todo.content,
            modifier = Modifier.weight(1f),
            style = Ui3Type.alt,
            color = if (todo.bitti) Ui3Colors.ink3 else Ui3Colors.ink,
            // Biten madde üstü çizili: listede gözün "kalanı" bulması için.
            textDecoration = if (todo.status == "completed") TextDecoration.LineThrough else null,
            fontWeight = if (todo.suradaki) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ------------------------------------------------------------- bağlam ayrıntısı

/**
 * Büyük sayıyı binlik ayraçla yazar: 44555 -> "44.555".
 *
 * Ayraç ELDE konuyor. `String.format`/`NumberFormat` cihaz diline bakıyor ve
 * aynı sayı iki telefonda "44.555" / "44,555" çıkıyor — bu depoda Türkçe-I
 * tuzağıyla aynı sınıftan bir hata. 0 ve altı "bilinmiyor" demek: köprü
 * pencereyi ısıtmadan 0 gönderiyor ve sıfır yazmak "pencere sıfır" gibi
 * okunurdu.
 */
internal fun baglamSayisi(deger: Int): String {
    if (deger <= 0) return "—"
    val ham = deger.toString()
    return ham.reversed().chunked(3).joinToString(".").reversed()
}

/**
 * BAĞLAM AYRINTISI — adaya dokununca açılan küçük yüzey.
 *
 * Ada 28dp'lik bir çubuk ve üstünde yalnız yüzde var; "44 bin token nereye
 * gitti" sorusunun cevabı oraya sığmıyor. Yüzeyde ham sayılar duruyor ve
 * yüzdenin YOK olduğu hâl açıkça yazılıyor — köprü pencereyi bilmiyorken ada
 * hiçbir şey göstermediği için kullanıcı "bozuk mu" diye düşünüyordu.
 *
 * Sıkıştır burada da var: panodaki tuşun kopyası değil, aynı ucun ikinci
 * kapısı — pano yalnız %80 üstünde çiziliyor, oysa kullanıcı bağlamı istediği
 * an özetlemek isteyebilir.
 */
@Composable
internal fun ColumnScope.Ui3BaglamAyrintisi(
    yuzde: Int?,
    tokenlar: Int,
    pencere: Int,
    calisiyor: Boolean,
    sikistirilabilir: Boolean,
    onSikistir: () -> Unit,
    onGeri: (() -> Unit)?,
) {
    SheetBasligi(
        "BAĞLAM",
        // Yüzde yokken başlıkta da yalan söylenmiyor: köprü ölçemiyorsa bunu
        // yaz, "%0" gösterme.
        if (yuzde != null) "%$yuzde dolu" else "doluluk ölçülemiyor",
        onGeri,
    )
    BaglamSatiri("Kullanılan", "${baglamSayisi(tokenlar)} token")
    BaglamSatiri(
        "Pencere",
        if (pencere > 0) "${baglamSayisi(pencere)} token" else "bilinmiyor",
    )
    BaglamSatiri(
        "Doluluk",
        if (yuzde != null) "%$yuzde" else "—",
        vurgu = baglamDoldu(yuzde),
    )

    if (!sikistirilabilir) return
    if (calisiyor) {
        Text(
            "Tur sürerken sıkıştırılamaz — önce durdur ya da bitmesini bekle.",
            style = Ui3Type.alt,
            color = Ui3Colors.ink3,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
        )
        return
    }
    Box(
        Modifier
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ui3Colors.amber.copy(alpha = 0.14f))
            .clickable(onClick = onSikistir)
            .padding(vertical = 11.dp)
            .testTag("baglam_sikistir"),
        contentAlignment = Alignment.Center,
    ) {
        Text("Sıkıştır", style = Ui3Type.govde, color = Ui3Colors.amber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BaglamSatiri(etiket: String, deger: String, vurgu: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(etiket, style = Ui3Type.govde, color = Ui3Colors.ink3, modifier = Modifier.weight(1f))
        Text(
            deger,
            style = Ui3Type.govde,
            color = if (vurgu) Ui3Colors.amber else Ui3Colors.ink,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
