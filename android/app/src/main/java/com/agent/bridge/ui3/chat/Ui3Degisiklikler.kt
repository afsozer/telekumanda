package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.BackendDiffFile
import com.agent.bridge.BackendSessionDiff
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Sheet'in tamamını kaplamasın: arkadaki sohbet görünür kalsın (Ui3Selector'daki
// LISTE_MAKS ile aynı gerekçe ve aynı ölçü).
private val LISTE_MAKS = 400.dp

/**
 * Bir patch satırının anlamı. Renk seçimi `when` zincirine gömülmek yerine saf
 * bir değere indiriliyor — böylece test edilebiliyor ve "yeşil/kırmızı" kararı
 * tek yerde duruyor.
 *
 * `Bilgi` iki farklı şeyi topluyor: dosya başlıkları (`Index:`, `---`, `+++`,
 * `diff --git`) ve git'in "\ No newline at end of file" notu. İkisi de yamanın
 * gövdesi değil, çerçevesi.
 */
internal enum class Ui3PatchSatiri { Ekleme, Silme, Hunk, Bilgi, Baglam }

/**
 * Satırı sınıflandırır.
 *
 * SIRA ÖNEMLİ: başlık satırları `---` ve `+++` ile başlıyor, yani ham "+ ile
 * başlıyorsa ekleme" kuralı onları yeşile/kırmızıya boyardı. Başlıklar ÖNCE
 * eleniyor. Aynı tuzak `@@` için yok ama hunk başlığı da gövdeden ayrı.
 *
 * Köprü iki farklı yama biçimi taşıyabiliyor (ölçüldü, opencode serve 1.18.21):
 * oturum turlarının diff'i jsdiff biçiminde (`Index: dosya` + `===` çizgisi),
 * git tarafı ise `diff --git` biçiminde. İkisinin başlıkları da burada.
 */
internal fun ui3PatchSatiriTipi(satir: String): Ui3PatchSatiri = when {
    satir.startsWith("@@") -> Ui3PatchSatiri.Hunk
    satir.startsWith("---") || satir.startsWith("+++") -> Ui3PatchSatiri.Bilgi
    satir.startsWith("diff --git") || satir.startsWith("index ") ||
        satir.startsWith("Index:") || satir.startsWith("===") ||
        satir.startsWith("new file") || satir.startsWith("deleted file") ||
        satir.startsWith("similarity index") || satir.startsWith("rename ") ||
        satir.startsWith("\\") -> Ui3PatchSatiri.Bilgi
    satir.startsWith("+") -> Ui3PatchSatiri.Ekleme
    satir.startsWith("-") -> Ui3PatchSatiri.Silme
    else -> Ui3PatchSatiri.Baglam
}

/**
 * Dosya durumunun tek harflik rozeti — `git status`un dilinde: E(klendi),
 * S(ilindi), D(eğişti). Tam kelime yazmak dar satırda dosya adından yer
 * çalıyordu; harf + renk ikisi birlikte anlamı taşıyor (renk tek başına
 * bırakılmadı, renk körlüğü).
 */
/**
 * Boş listenin metni.
 *
 * Saf fonksiyon çünkü asıl karar burada: "bu oturum dosya değiştirmedi" bir
 * İDDİA ve köprü listenin başının eksik olduğunu söylüyorsa ([historyGap])
 * YANLIŞ bir iddia. Codex'te geçmişi rollout dosyasından kurulan oturumlarda
 * (köprü yeniden başladıktan sonra ya da diskten açılan eski bir oturumda)
 * eski değişiklikler okunamıyor; orada boş liste "bilmiyorum" demek.
 */
internal fun ui3DegisiklikBosMetni(historyGap: Boolean): String =
    if (historyGap) {
        "Bu oturumun eski değişiklikleri okunamıyor — köprü yeniden başladıktan sonra yapılanlar listelenir."
    } else {
        "Bu oturum dosya değiştirmedi."
    }

internal fun ui3DiffDurumHarfi(status: String): String = when (status) {
    "added" -> "E"
    "deleted" -> "S"
    else -> "D"
}

/**
 * DEĞİŞİKLİKLER — "bu oturum neyi değiştirdi".
 *
 * Uzun otonom koşu bitince telefondan inceleme için: transkripti kaydırıp
 * hangi aracın hangi dosyaya dokunduğunu ayıklamak yerine dosya listesi.
 *
 * İKİ KADEMELİ, AYNI SHEET İÇİNDE: liste → dosyaya dokun → yama. Ayrı bir
 * sheet kimliği açılmadı çünkü kök tek yuvalı (`acikSheet`) ve ikinci kimlik
 * "geri" davranışını kökün geri hedefiyle karıştırırdı; burada geri oku
 * yalnızca yerel durumu sıfırlıyor. Aynı desen [Ui3Selector]'ın bilgi
 * kartında da var.
 *
 * VERİ AKIŞI: liste açılırken bir kez çekiliyor (bkz. `acikSheetAc`), sonra
 * durum [BackendSessionDiff] olarak state'te duruyor. Yoklamaya binmiyor —
 * uzun koşuda her karede oturumun bütün mesaj listesini köprüye okutmak
 * anlamına gelirdi.
 */
@Composable
internal fun ColumnScope.Ui3Degisiklikler(
    diff: BackendSessionDiff?,
    yukleniyor: Boolean,
    onGeri: (() -> Unit)?,
) {
    var acikDosya by remember { mutableStateOf<String?>(null) }

    // SHEET KAPANINCA BİLEŞEN YAŞAMAYA DEVAM EDİYOR: kök çıkış animasyonu
    // boyunca son içeriği koruyor (`acikSheet ?: sonSheetKimligi`), yani
    // `remember` sıfırlanmıyor. Bir yamaya bakıp sheet'i kapatan kullanıcı
    // "Değişiklikler"i yeniden açtığında dosya listesi yerine o yamayı
    // buluyordu. Her açılışta yeni bir yükleme başladığı için (`acikSheetAc`)
    // yükleme bayrağının yükselen kenarı "yeniden açıldı" demek.
    LaunchedEffect(yukleniyor) { if (yukleniyor) acikDosya = null }

    val secili = diff?.files?.firstOrNull { it.path == acikDosya }
    if (secili != null) {
        DosyaYamasi(secili) { acikDosya = null }
        return
    }

    SheetBasligi("DEĞİŞİKLİKLER", diff?.let { ozetMetni(it) }, onGeri)

    // YÜKLENİRKEN ESKİ LİSTE KORUNUR: tazeleme sırasında listeyi boşaltmak
    // "bu oturum dosya değiştirmedi" boş durumunu bir an için yanlışlıkla
    // gösterirdi. Elde hiçbir şey yokken (ilk açılış) çark çizilir.
    if (yukleniyor && diff == null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s20),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Ui3Colors.vurguHi,
                strokeWidth = 2.dp,
            )
            Text("Değişiklikler okunuyor…", style = Ui3Type.govde, color = Ui3Colors.ink2)
        }
        return
    }

    val dosyalar = diff?.files.orEmpty()
    if (dosyalar.isEmpty()) {
        // BOŞ LİSTE HER ZAMAN "değişmedi" DEMEK DEĞİL: köprü listenin başının
        // eksik olduğunu söylüyorsa (codex'te geçmişi rollout'tan kurulan
        // oturum) "bu oturum dosya değiştirmedi" cümlesi düpedüz yanlış olur.
        Text(
            ui3DegisiklikBosMetni(diff?.historyGap == true),
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16)
                .testTag("degisiklik_bos"),
        )
        return
    }

    if (diff?.historyGap == true) KirpmaNotu("Liste eksik — köprü yeniden başlamadan önceki değişiklikler okunamıyor.")
    if (diff?.truncated == true) KirpmaNotu("Liste kırpıldı — köprü büyük yamaları kesiyor.")

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = LISTE_MAKS).testTag("degisiklik_liste"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Ui3Tokens.s12,
            vertical = Ui3Tokens.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(dosyalar, key = { it.path }) { dosya ->
            DosyaSatiri(dosya) { acikDosya = dosya.path }
        }
    }
}

/** Başlık altı özeti: kaç dosya, kaç tur, toplam +/−. */
private fun ozetMetni(diff: BackendSessionDiff): String? {
    if (diff.files.isEmpty()) return null
    val parcalar = listOfNotNull(
        "${diff.files.size} dosya",
        diff.turns.takeIf { it > 1 }?.let { "$it tur" },
        "+${diff.additions} −${diff.deletions}",
    )
    return parcalar.joinToString(" · ")
}

@Composable
private fun DosyaSatiri(dosya: BackendDiffFile, onTikla: () -> Unit) {
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onTikla)
            .testTag("degisiklik_dosya"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier.padding(horizontal = Ui3Tokens.s12, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DurumRozeti(dosya.status)
            Column(Modifier.weight(1f)) {
                // KLASÖR SOLDA VE SESSİZ, DOSYA ADI SAĞDA VE OKUNUR. Uzun yolda
                // tek satırlık kırpma tam da ayırt edici kısmı (dosya adını)
                // yiyordu; ad ayrı satırda, yol üstünde soluk duruyor.
                val klasor = dosya.path.substringBeforeLast('/', "")
                if (klasor.isNotBlank()) {
                    Text(
                        klasor,
                        style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                        color = Ui3Colors.ink3,
                        maxLines = 1,
                        // Yolun BAŞI kırpılır: iki dosya çoğunlukla derin
                        // klasörlerde ayrışıyor, kökte değil.
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                Text(
                    dosya.path.substringAfterLast('/'),
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SayacRozeti(dosya.additions, dosya.deletions)
        }
    }
}

/** E/S/D harfi, durumun rengiyle. Harf + renk birlikte: renk tek başına değil. */
@Composable
private fun DurumRozeti(status: String) {
    val renk = when (status) {
        "added" -> Ui3Colors.done
        "deleted" -> Ui3Colors.danger
        else -> Ui3Colors.ink3
    }
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(renk.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ui3DiffDurumHarfi(status),
            style = Ui3Type.rozet.copy(fontFamily = Ui3Mono),
            color = renk,
        )
    }
}

/**
 * +n / −n rozetleri.
 *
 * Sıfır olan taraf ÇİZİLMEZ: yeni eklenen bir dosyada "−0" bilgi taşımıyor,
 * yalnız satırı dolduruyor.
 */
@Composable
private fun SayacRozeti(eklenen: Int, silinen: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (eklenen > 0) {
            Text(
                "+$eklenen",
                style = Ui3Type.rozet.copy(fontFamily = Ui3Mono),
                color = Ui3Colors.done,
                modifier = Modifier.testTag("degisiklik_ekleme"),
            )
        }
        if (silinen > 0) {
            Text(
                "−$silinen",
                style = Ui3Type.rozet.copy(fontFamily = Ui3Mono),
                color = Ui3Colors.danger,
                modifier = Modifier.testTag("degisiklik_silme"),
            )
        }
    }
}

/**
 * Tek dosyanın yaması.
 *
 * YATAY KAYDIRMA VAR, SARMA YOK: kod satırı sarıldığında yama okunmaz hâle
 * geliyor — girinti kayıyor ve bir satırın nerede bitip diğerinin nerede
 * başladığı belirsizleşiyor. Uzun satır sağa taşar, kullanıcı kaydırır.
 */
@Composable
private fun ColumnScope.DosyaYamasi(dosya: BackendDiffFile, onGeri: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Ui3Tokens.s8, end = Ui3Tokens.s20, bottom = Ui3Tokens.s8),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onGeri)
                .testTag("degisiklik_geri"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Dosya listesine dön",
                tint = Ui3Colors.ink2,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                dosya.path,
                style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                color = Ui3Colors.ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        SayacRozeti(dosya.additions, dosya.deletions)
    }

    if (dosya.truncated) KirpmaNotu("Yama kırpıldı — tamamı için bilgisayara bak.")

    // Yama satırları TEK TEK Text: tek bir AnnotatedString'de renklendirmek de
    // mümkündü ama o zaman satır zeminini boyayamıyoruz (ekleme/silme satırları
    // soluk yeşil/kırmızı bir yatak üstünde çok daha hızlı taranıyor) ve uzun
    // yamada tek dev metin ölçümü kare düşürüyor. LazyColumn satır satır
    // ölçtüğü için ekranda olmayan satırlar hiç çizilmiyor.
    val yatay = rememberScrollState()
    val satirlar = remember(dosya.path, dosya.patch) { dosya.patch.split('\n') }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = LISTE_MAKS)
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(Ui3Colors.kuyu)
            .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r12))
            .testTag("degisiklik_yama"),
    ) {
        items(satirlar.size) { i -> YamaSatiri(satirlar[i], yatay) }
    }
}

@Composable
private fun YamaSatiri(satir: String, yatay: androidx.compose.foundation.ScrollState) {
    val tip = ui3PatchSatiriTipi(satir)
    val murekkep = when (tip) {
        Ui3PatchSatiri.Ekleme -> Ui3Colors.done
        Ui3PatchSatiri.Silme -> Ui3Colors.danger
        Ui3PatchSatiri.Hunk -> Ui3Colors.vurguHi
        Ui3PatchSatiri.Bilgi -> Ui3Colors.ink3
        Ui3PatchSatiri.Baglam -> Ui3Colors.ink2
    }
    val yatak = when (tip) {
        Ui3PatchSatiri.Ekleme -> Ui3Colors.done.copy(alpha = 0.10f)
        Ui3PatchSatiri.Silme -> Ui3Colors.danger.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(yatak)
            // Kaydırma durumu PAYLAŞILIYOR: satırların her biri kendi
            // scroll'una sahip olsaydı yatayda kaydırmak yamayı merdivene
            // çevirirdi (bir satır kayar, komşusu yerinde kalır).
            .horizontalScroll(yatay),
    ) {
        Text(
            // Boş satır da yer tutmalı: yama içindeki boş bağlam satırı
            // atlanırsa satır sayısı yamanınkiyle tutmaz.
            satir.ifEmpty { " " },
            style = Ui3Type.alt.copy(fontFamily = Ui3Mono, lineHeight = 17.sp),
            color = murekkep,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        )
    }
}

/** Eksik gösterdiğimizi SÖYLEYEN satır — kırpılmış bir diff'i tam sanmak,
 *  incelemenin tamamını boşa çıkarır. */
@Composable
private fun KirpmaNotu(metin: String) {
    Text(
        metin,
        style = Ui3Type.alt,
        color = Ui3Colors.attention,
        modifier = Modifier
            .padding(start = Ui3Tokens.s20, end = Ui3Tokens.s20, bottom = Ui3Tokens.s8)
            .testTag("degisiklik_kirpma"),
    )
}
