package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.DirEntry
import com.agent.bridge.WorkerDirs
import com.agent.bridge.folderBreadcrumb
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Satır asgari boyu: 48dp dokunma hedefi + nefes. Merkez'in DIZIN_MIN_Y'siyle
// aynı sayı — iki ekran aynı ritimde okunsun.
private val SATIR_MIN_Y = 52.dp
private val IKON_KUTU = 32.dp

/**
 * ui3 klasör seçici — ui2'nin `FolderPickerCard`'ının YERİNİ ALIR.
 *
 * Neden yeniden yazıldı (kullanıcı isteği, 18.08.2026): ödünç bileşen ui3
 * ağacında `Ui3OduncKap` ile sarılıp ui2 renkleriyle çiziliyordu ve yeni
 * oturum ekranı ortadan ikiye bölünmüş gibi duruyordu. Ödünç kuralı "ağır
 * bileşen ui2'den" der; buradaki ağırlık gezinme mantığı değil, o zaten
 * ViewModel'de — çizilen şey bir liste. Yeniden yazmanın bedeli düşük,
 * kazancı tek dil.
 *
 * DAVRANIŞ DEĞİŞİKLİĞİ — "Bu klasörü kullan" YOK:
 *
 * ui2'de hedef klasör ayrı bir satıra dokunarak seçiliyordu ve bu sessiz bir
 * tuzaktı: bir kez "Bu klasörü kullan" dedikten sonra başka bir klasöre
 * girsen bile seçim ESKİ klasörde kalıyordu — Başlat'a bastığında beklediğin
 * yerde açılmıyordu. Artık hedef her zaman İÇİNDE BULUNDUĞUN klasör
 * ([konum], yani `WorkerDirs.base`). Bir klasöre girmek onu seçmektir; ayrıca
 * onaylaman gerekmez. Nerede başlayacağın da Başlat tuşunun ALTINDA yazıyor
 * (Ui3YeniOturum), yani karar görünür.
 *
 * Bunun kaybettirdiği tek şey "başka klasöre göz atıp seçimi korumak" — o da
 * hiç istenmemiş bir yetenekti ve yanlış anlaşılan davranışın kaynağıydı.
 *
 * Son kullanılanlar ve favoriler ui2'de daraltılabilir LİSTE'ydi (liste
 * içinde liste); burada yatay ÇİP şeridi: iki satır yer kaplıyor, dokununca
 * oraya gidiyor ve gezinme listesiyle karışmıyor.
 */
@Composable
internal fun Ui3KlasorSecici(
    dizinler: WorkerDirs?,
    aramaSonuclari: List<DirEntry>,
    sonKlasorler: List<String>,
    favoriler: List<String>,
    favoriMi: Boolean,
    konum: String,
    onGit: (String) -> Unit,
    onFavori: (String) -> Unit,
    onAra: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sorgu by remember { mutableStateOf("") }
    var sonSorgu by remember { mutableStateOf("") }

    // 300ms geciktirme ui2'deki gibi: her harfte köprüye istek gitmesin.
    LaunchedEffect(sorgu) {
        if (sorgu == sonSorgu) return@LaunchedEffect
        sonSorgu = sorgu
        onAra(if (sorgu.length >= 2) sorgu else "")
    }

    val aramada = sorgu.length >= 2

    Column(modifier.fillMaxWidth().testTag("klasor_secici")) {
        AramaAlani(sorgu) { sorgu = it }

        if (aramada) {
            if (aramaSonuclari.isEmpty()) {
                BosMetin("Sonuç yok")
            } else {
                aramaSonuclari.forEach { girdi ->
                    KlasorSatiri(girdi.name, girdi.path) { onGit(girdi.path) }
                }
            }
            return@Column
        }

        // KONUM ŞERİDİ: kırıntı yolu + favori yıldızı. Kırıntının her parçası
        // tıklanabilir — derin bir yoldan tek dokunuşla yukarı çıkmak için.
        if (konum.isNotBlank()) {
            KonumSeridi(konum, favoriMi, onGit, onFavori)
        }

        if (dizinler != null && dizinler.parent.isNotBlank()) {
            KlasorSatiri(
                baslik = "Üst klasör",
                ayrinti = dizinler.parent,
                ikon = { Icon(Icons.Filled.ArrowUpward, null, tint = Ui3Colors.vurguHi, modifier = Modifier.size(17.dp)) },
                onTikla = { onGit(dizinler.parent) },
            )
        }

        val altlar = dizinler?.dirs?.filter { it.type == "dir" }.orEmpty()
        if (altlar.isEmpty()) {
            BosMetin(if (dizinler == null) "Yükleniyor…" else "Alt klasör yok — burada başlayabilirsin")
        } else {
            altlar.forEach { dizin ->
                KlasorSatiri(dizin.name, dizin.path) { onGit(dizin.path) }
            }
        }

        if (sonKlasorler.isNotEmpty()) {
            CipSeridi("SON KULLANILAN", sonKlasorler.take(12), konum, onGit)
        }
        if (favoriler.isNotEmpty()) {
            CipSeridi("FAVORİ", favoriler, konum, onGit)
        }
    }
}

/**
 * Arama alanı — composer'ın metin kutusuyla AYNI malzeme (kuyu + ince çizgi).
 * Material `TextField` kullanılmadı: kendi kabı ve alt çizgisi ui3'ün
 * "tek yüzey" diline ikinci bir kutu ekliyor.
 */
@Composable
private fun AramaAlani(deger: String, onDeger: (String) -> Unit) {
    val sekil = RoundedCornerShape(Ui3Tokens.r18)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Ui3Tokens.s8)
            .clip(sekil)
            .background(Ui3Colors.kuyu)
            .border(1.dp, Ui3Colors.cizgiInce, sekil)
            .padding(horizontal = Ui3Tokens.s12, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = Ui3Colors.ink3, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (deger.isEmpty()) {
                Text("Klasör ara…", style = Ui3Type.yazi, color = Ui3Colors.ink3)
            }
            BasicTextField(
                value = deger,
                onValueChange = onDeger,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(Ui3Type.yazi.copy(color = Ui3Colors.ink)),
                cursorBrush = SolidColor(Ui3Colors.vurguHi),
                modifier = Modifier.fillMaxWidth().testTag("klasor_ara"),
            )
        }
    }
}

/**
 * Konum şeridi — "buradasın" satırı.
 *
 * Yol MONO yazılıyor (anayasa v1 4.3: yol/komut her zaman mono) ve son parça
 * KOYU: hedefin o olduğunu tek bakışta söylesin diye. Üstündeki parçalar
 * vurgu renginde ve tıklanabilir.
 */
@Composable
private fun KonumSeridi(konum: String, favoriMi: Boolean, onGit: (String) -> Unit, onFavori: (String) -> Unit) {
    val kirintilar = remember(konum) { folderBreadcrumb(konum) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = Ui3Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            kirintilar.forEachIndexed { indeks, (ad, yol) ->
                if (indeks > 0) Text("›", style = Ui3Type.alt, color = Ui3Colors.ink3)
                val sonuncu = indeks == kirintilar.lastIndex
                Text(
                    ad,
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = if (sonuncu) Ui3Colors.ink else Ui3Colors.vurguHi,
                    fontWeight = if (sonuncu) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = if (sonuncu) Modifier else Modifier.clickable { onGit(yol) },
                )
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(Ui3Tokens.pill)
                .clickable { onFavori(konum) }
                .testTag("klasor_favori"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (favoriMi) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (favoriMi) "Favorilerden çıkar" else "Favorilere ekle",
                tint = if (favoriMi) Ui3Colors.amber else Ui3Colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Klasör satırı — Merkez'in dizin satırıyla aynı iskelet (ikon + ad + yol + ›). */
@Composable
private fun KlasorSatiri(
    baslik: String,
    ayrinti: String,
    ikon: @Composable () -> Unit = {
        Icon(Icons.Outlined.Folder, null, tint = Ui3Colors.ink2, modifier = Modifier.size(17.dp))
    },
    onTikla: () -> Unit,
) {
    Column {
        // Satır ayracı: kartın içinde ince çizgi (Merkez'in dizin kartıyla aynı).
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui3Colors.cizgiInce))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SATIR_MIN_Y)
                .clickable(onClick = onTikla)
                .padding(vertical = Ui3Tokens.s8),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(IKON_KUTU).clip(RoundedCornerShape(Ui3Tokens.r12)).background(Ui3Colors.yuzey2),
                contentAlignment = Alignment.Center,
            ) { ikon() }
            Column(Modifier.weight(1f)) {
                Text(baslik, style = Ui3Type.govde, color = Ui3Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ayrinti,
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    // Yolun ANLAMLI ucu sondadır (klasör adı); baştan kırpmak
                    // "C:\Users\..." tekrarını gösterip asıl bilgiyi yiyordu.
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Ui3Colors.ink3, modifier = Modifier.size(17.dp))
        }
    }
}

/**
 * Son kullanılan / favori şeridi — yatay çip.
 *
 * ui2'de bunlar açılıp kapanan liste başlıklarıydı ve açıldıklarında gezinme
 * listesiyle birbirine giriyordu. Çip şeridi iki satır yer kaplıyor, hepsi
 * aynı anda görünüyor ve "gezinme" ile "kısayol" görsel olarak ayrışıyor.
 */
@Composable
private fun CipSeridi(baslik: String, yollar: List<String>, konum: String, onGit: (String) -> Unit) {
    Column(Modifier.padding(top = Ui3Tokens.s12)) {
        Text(baslik, style = Ui3Type.etiket, color = Ui3Colors.ink3)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Ui3Tokens.s8)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        ) {
            yollar.forEach { yol ->
                val ad = yol.substringAfterLast("\\").substringAfterLast("/").ifBlank { yol }
                val burada = yol == konum
                GlassLikeSurface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Ui3Tokens.r12))
                        .clickable { onGit(yol) },
                    shape = RoundedCornerShape(Ui3Tokens.r12),
                ) {
                    Text(
                        ad,
                        style = Ui3Type.alt,
                        color = if (burada) Ui3Colors.vurguHi else Ui3Colors.ink2,
                        fontWeight = if (burada) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = Ui3Tokens.s12, vertical = Ui3Tokens.s8),
                    )
                }
            }
        }
    }
}

@Composable
private fun BosMetin(metin: String) {
    Text(
        metin,
        style = Ui3Type.alt,
        color = Ui3Colors.ink3,
        modifier = Modifier.fillMaxWidth().padding(vertical = Ui3Tokens.s16),
    )
}
