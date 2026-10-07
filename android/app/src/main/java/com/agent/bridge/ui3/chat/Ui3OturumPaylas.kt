package com.agent.bridge.ui3.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.shell.Ui3Onayla
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * PAYLAŞ — oturumu herkese açık bir linkte yayınlama.
 *
 * İKİ KADEMELİ, AYNI SHEET İÇİNDE (Ui3GeriSar'daki desenin aynısı; kök tek
 * sheet yuvalı, ikinci kimlik "geri" davranışını karıştırırdı):
 * paylaşılmamışsa ONAY, paylaşılmışsa LİNK.
 *
 * ONAY ATLANAMAZ ve `yikici=true` ile gül renkli, bilerek: bu uygulamanın
 * yaptığı tek DIŞA AÇILMA. Kullanıcı "Paylaş"a dokunduğunda transkriptin —
 * dosya yolları, kod, konuştuğu her şey — linke sahip herkese görünür hâle
 * geleceğini eylemi seçtiği ANDA okumalı. Geri alınabilir olması onayı
 * gereksiz kılmıyor: yayın kaldırılana kadar dışarıda.
 *
 * Link üretildikten sonra sheet KAPANMIYOR (geri sarmadan ayrıldığı nokta):
 * kullanıcının linkle yapacağı bir iş var — paylaşım sayfası ya da panoya
 * kopyalama. Paylaşım sayfası onaydan hemen sonra kendiliğinden açılıyor
 * ("paylaş" derken kastedilen şey o), ama tek yol o değil: sayfayı kapatan
 * kullanıcı linki burada bulur.
 */
@Composable
internal fun ColumnScope.Ui3OturumPaylas(
    link: String,
    onPaylas: ((String) -> Unit) -> Unit,
    onKaldir: () -> Unit,
    onGeri: (() -> Unit)?,
) {
    val baglam = LocalContext.current
    val pano = LocalClipboardManager.current

    // Android paylaşım sayfası. Ayrı fonksiyon: hem onaydan sonra
    // kendiliğinden hem de "Paylaşım sayfası" satırından çağrılıyor.
    fun paylasimSayfasi(url: String) {
        if (url.isBlank()) return
        val niyet = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        baglam.startActivity(Intent.createChooser(niyet, "Oturum linkini paylaş"))
    }

    if (link.isBlank()) {
        SheetBasligi("OTURUMU PAYLAŞ", null, onGeri)
        Ui3Onayla(
            baslik = "Oturum herkese açık bir linkte yayınlansın mı?",
            // Kesin ve somut: "paylaş" kelimesi telefonda "arkadaşına gönder"
            // çağrışımı yapıyor, oysa burada olan şey İNTERNETE koymak.
            aciklama = "Bu sohbetin tamamı — yazdıkların, ajanın cevapları, " +
                "dosya yolları ve kod parçaları — linke sahip HERKESİN " +
                "görebileceği bir sayfada yayınlanır. Sonra “Paylaşımı kaldır” " +
                "ile yayından indirebilirsin.",
            onayMetni = "Yayınla",
            yikici = true,
            onOnay = { onPaylas { url -> paylasimSayfasi(url) } },
            onVazgec = { onGeri?.invoke() },
        )
        return
    }

    SheetBasligi("PAYLAŞILDI", "yayında", onGeri)
    Text(
        link,
        style = Ui3Type.govde,
        color = Ui3Colors.ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s8)
            .testTag("paylasim_link"),
    )
    PaylasSatiri(
        etiket = "Paylaşım sayfası",
        testEtiketi = "paylasim_intent",
        ikonu = { Icon(Icons.Filled.IosShare, null, tint = Ui3Colors.ink2, modifier = Modifier.padding(2.dp)) },
    ) { paylasimSayfasi(link) }
    PaylasSatiri(
        etiket = "Linki kopyala",
        testEtiketi = "paylasim_kopyala",
        ikonu = { Icon(Icons.Filled.ContentCopy, null, tint = Ui3Colors.ink2, modifier = Modifier.padding(2.dp)) },
    ) { pano.setText(AnnotatedString(link)) }

    // Kaldırma onaysız: yıkıcı olan yayınlamaktı, indirmek güvenli yön.
    Box(
        Modifier
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Ui3Colors.rose.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .background(Ui3Colors.rose.copy(alpha = 0.12f))
            .clickable(onClick = onKaldir)
            .padding(vertical = 11.dp)
            .testTag("paylasim_kaldir"),
        contentAlignment = Alignment.Center,
    ) {
        Text("Paylaşımı kaldır", style = Ui3Type.govde, color = Ui3Colors.rose, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PaylasSatiri(
    etiket: String,
    testEtiketi: String,
    ikonu: @Composable () -> Unit,
    onTikla: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTikla)
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12)
            .testTag(testEtiketi),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(Ui3Colors.yuzey2)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { ikonu() }
        Text(etiket, style = Ui3Type.govde, color = Ui3Colors.ink2, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * AGENTS.md OLUŞTUR — projeyi analiz edip dosyayı yazan tur.
 *
 * Onay, geri sarmadaki gibi bir YIKIM uyarısı değil (init var olan dosyayı
 * yerinde iyileştiriyor, silmiyor): burada söylenmesi gereken şey satıra
 * dokunmanın bir TUR başlattığı — yani model çağrısı, süre ve para. Menüdeki
 * diğer satırlar bir sheet açıyor, bu satır işi BAŞLATIYOR; ayrım kullanıcıya
 * önceden söylenmezse "menüye bakıyordum, ajan koşmaya başladı" olurdu.
 */
@Composable
internal fun ColumnScope.Ui3AgentsInit(
    calisiyor: Boolean,
    onBaslat: () -> Unit,
    onGeri: (() -> Unit)?,
) {
    // Sheet kapanınca bileşen çıkış animasyonu boyunca yaşıyor (kök son içeriği
    // koruyor), yani basıldı bilgisi `remember`da kalıcı olurdu. Yalnız çift
    // dokunuşu engellemek için var; tur başlayınca zaten `calisiyor` devralıyor.
    var basildi by remember { mutableStateOf(false) }

    SheetBasligi("AGENTS.md OLUŞTUR", null, onGeri)

    if (calisiyor) {
        Text(
            "Tur sürerken başlatılamaz — önce durdur ya da bitmesini bekle.",
            style = Ui3Type.govde,
            color = Ui3Colors.attention,
            modifier = Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16)
                .testTag("agents_init_calisiyor"),
        )
        return
    }

    Ui3Onayla(
        baslik = "AGENTS.md için bir tur koşulsun mu?",
        aciklama = "Ajan projeyi inceler (yapı dosyaları, betikler, CI, mevcut " +
            "yönergeler) ve AGENTS.md'yi yazar ya da var olanı yerinde " +
            "iyileştirir. Bu NORMAL BİR TURDUR: sohbette akar, süre ve " +
            "model kullanır; durdurmak istersen normal durdurma tuşuyla kesersin.",
        onayMetni = "Başlat",
        onOnay = { if (!basildi) { basildi = true; onBaslat() } },
        onVazgec = { onGeri?.invoke() },
    )
}
