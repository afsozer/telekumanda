package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.BuildConfig
import com.agent.bridge.availableBackendIds
import com.agent.bridge.userFacingBackendLabel
import com.agent.bridge.userFacingProviderMonogram
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.backendUsesWorkspaces
import com.agent.bridge.coworkWorkspaces
import com.agent.bridge.enterBackend
import com.agent.bridge.startBackendSession
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.shell.Monogram
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Yeni oturum — BOŞ SEKMENİN İÇİNDE (kullanıcı kararı).
 *
 * ui3'te sıfırdan oturum başlatmanın hiçbir yolu yoktu: "＋" boş bir sekme
 * açıyordu ama o sekmeyi dolduracak ekran yazılmamıştı. ui2'de bu ayrı bir
 * ekran (`NewSessionScreen`); burada sekmenin içinde duruyor, yani ekran
 * değiştirmeden aynı yerde kalıyorsun ve seçim yapınca sekme sohbete dönüyor.
 *
 * MANTIK ui2'den birebir: sağlayıcı seçimi `enterBackend`, başlatma
 * `startBackendSession`, cowork'te `startCoworkNamedWorkspace`, "başlat"
 * koşulu ve son klasöre ekleme aynı sırayla. Sıra önemli — cowork'te workspace
 * boş VE ad doluysa adlandırılmış alan açılıyor, aksi halde normal başlatma.
 *
 * KLASÖR GEZGİNİ ARTIK ui3'ÜN KENDİ BİLEŞENİ ([Ui3KlasorSecici], 18.08.2026).
 * Önce ui2'nin `FolderPickerCard`'ı ödünç alınmıştı ("ağır bileşen ui2'den"
 * kuralı) ama buradaki ağırlık gezinme mantığıydı ve o zaten ViewModel'de;
 * ödünç alınan şey yalnız bir listeydi ve ui2 renkleriyle çizilince ekran
 * ortadan ikiye bölünmüş gibi duruyordu. Hedef seçme mantığı da orada
 * değişti: "Bu klasörü kullan" yok, içinde bulunduğun klasör hedeftir.
 */
// Alta sabitlenen Başlat tuşunun kapladığı dikey yer (iki satır metin + dolgu
// + alt boşluk). Kaydırılan gövdenin sonuna bu kadar boşluk konuyor ki son
// satır tuşun altında kalmasın.
private val BASLAT_ALANI = 92.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Ui3YeniOturum(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
    // Dock + gezinme çubuğu payı. Kaydırılan gövdenin SONUNA ve sabit Başlat
    // tuşunun altına konuyor — dolgu olarak verilseydi liste camın arkasına
    // giremez, altta düz bir bant kalırdı.
    altBosluk: Dp = 0.dp,
) {
    val saglayicilar = uiState.availableBackendIds()
    var secili by remember { mutableStateOf(uiState.backend ?: "claude-app") }
    // Cowork alan seçimi EKRANA ÖZEL: motor state'ine yazılmaz.
    var seciliAlan by remember { mutableStateOf("") }
    var yeniAlanAdi by remember { mutableStateOf("") }

    val sonKlasorler = remember { actions.folderPickerRecent() }
    val favoriler = remember { actions.folderPickerFavorites() }

    LaunchedEffect(secili) {
        if (backendUsesWorkspaces(secili)) actions.loadCoworkWorkspaces()
        else actions.loadWorkerDirs("")
    }

    val dizinler = uiState.workerDirs
    // HEDEF = İÇİNDE BULUNDUĞUN KLASÖR (kullanıcı isteği 18.08.2026).
    //
    // Eskiden ayrı bir `seciliDizin` durumu vardı ve yalnız "Bu klasörü kullan"
    // satırı onu yazıyordu; hiç dokunmazsan `kok`a düşüyordu. Sonuç sessiz bir
    // tuzaktı: bir kez seçtikten sonra başka klasöre girmek seçimi
    // DEĞİŞTİRMİYORDU, yani Başlat beklediğin yerde açmıyordu. Artık tek
    // kaynak `WorkerDirs.base` — gezinmek seçmektir, ayrı bir onay yok.
    val kok = dizinler?.base.orEmpty()
    val baslatilabilir = if (backendUsesWorkspaces(secili)) true else kok.isNotBlank()

    // BAŞLAT TUŞU KAYDIRILMIYOR, ALTA SABİT (18.08.2026).
    //
    // Önce klasör seçicinin ÜSTÜNDEydi (ui2 plan 8.6'dan devir). "Bu klasörü
    // kullan" satırı kalkıp hedef "içinde bulunduğun klasör" olunca bu yerleşim
    // bozuldu: listede birkaç klasör derine inince tuş da, üstündeki hedef yazısı
    // da ekrandan çıkıyordu — yani nerede başlayacağını göremeden başlatıyordun
    // (cihazda görüldü). Sabit tuş ikisini de her an ekranda tutuyor.
    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s16)
            .testTag("yeni_oturum"),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Text(
            "Yeni oturum",
            style = Ui3Type.baslik,
            color = Ui3Colors.ink,
            modifier = Modifier.padding(top = Ui3Tokens.s8),
        )

        GlassLikeSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Ui3Tokens.r26),
        ) {
            Column(
                Modifier.padding(Ui3Tokens.s16),
                verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            ) {
                Text("SAĞLAYICI", style = Ui3Type.etiket, color = Ui3Colors.ink3)
                if (BuildConfig.IS_LITE) {
                    Column(verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8)) {
                        saglayicilar.forEach { id ->
                            SaglayiciKarti(
                                id = id,
                                etiket = uiState.userFacingBackendLabel(id),
                                aciklama = liteSaglayiciAciklamasi(id),
                                monogram = uiState.userFacingProviderMonogram(id),
                                secili = secili == id,
                                onTikla = {
                                    secili = id
                                    actions.enterBackend(id)
                                },
                            )
                        }
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    ) {
                        saglayicilar.forEach { id ->
                            SaglayiciCipi(
                                id = id,
                                etiket = uiState.userFacingBackendLabel(id),
                                monogram = uiState.userFacingProviderMonogram(id),
                                secili = secili == id,
                                onTikla = {
                                    secili = id
                                    actions.enterBackend(id)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (backendUsesWorkspaces(secili)) {
            CalismaAlanlari(
                uiState = uiState,
                seciliAlan = seciliAlan,
                yeniAlanAdi = yeniAlanAdi,
                onAlan = { seciliAlan = it },
                onYeniAd = { yeniAlanAdi = it },
            )
        } else if (!BuildConfig.IS_LITE) {
            Text("PROJE KLASÖRÜ", style = Ui3Type.etiket, color = Ui3Colors.ink3)
            GlassLikeSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Ui3Tokens.r26),
            ) {
                // ARTIK ui2'nin `FolderPickerCard`'ı DEĞİL (kullanıcı isteği
                // 18.08.2026): ödünç bileşen `Ui3OduncKap` ile ui2 renklerine
                // dönüyordu ve ekran ortadan ikiye bölünmüş gibi duruyordu.
                // Gezinme mantığı zaten ViewModel'de; çizilen şey bir liste.
                Box(Modifier.padding(Ui3Tokens.s16)) {
                    Ui3KlasorSecici(
                        dizinler = dizinler,
                        aramaSonuclari = uiState.folderSearchResults,
                        sonKlasorler = sonKlasorler,
                        favoriler = favoriler,
                        favoriMi = actions.folderPickerIsFavorite(kok),
                        konum = kok,
                        onGit = { actions.loadWorkerDirs(it) },
                        onFavori = { actions.folderPickerToggleFavorite(it) },
                        onAra = { actions.updateFolderSearchQuery(kok, it) },
                    )
                }
            }
        }
        // Sabit tuşun + dock'un + jest çubuğunun kapladığı yer: son satır
        // bunların altında kalmasın ama oraya KADAR kayabilsin.
        Box(Modifier.padding(bottom = BASLAT_ALANI + altBosluk))
    }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = Ui3Tokens.s16,
                    end = Ui3Tokens.s16,
                    top = Ui3Tokens.s8,
                    bottom = Ui3Tokens.s8 + altBosluk,
                )
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (baslatilabilir) Ui3Colors.birincilZemin else Ui3Colors.yuzey2)
                .clickable(enabled = baslatilabilir) {
                    if (backendUsesWorkspaces(secili) && seciliAlan.isBlank() && yeniAlanAdi.isNotBlank()) {
                        actions.startCoworkNamedWorkspace(yeniAlanAdi.trim())
                    } else {
                        val hedef = if (backendUsesWorkspaces(secili)) seciliAlan else kok
                        actions.startBackendSession(secili, hedef)
                        // Son klasöre YALNIZ başarılı başlatmada eklenir (ui2 plan 8.3).
                        if (!backendUsesWorkspaces(secili) && hedef.isNotBlank()) {
                            actions.folderPickerAddRecent(hedef)
                        }
                    }
                }
                .padding(vertical = 13.dp)
                .testTag("yeni_oturum_baslat"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Başlat",
                    style = Ui3Type.govde,
                    color = if (baslatilabilir) Ui3Colors.birincilMurekkep else Ui3Colors.ink3,
                    fontWeight = FontWeight.Bold,
                )
                // NEREDE başlayacağı tuşun İÇİNDE yazıyor. "Bu klasörü kullan"
                // satırı kalkınca hedefi doğrulayacak tek yer burası kaldı;
                // aşağıdaki listeye bakmadan da kararı görebilmelisin.
                val hedefMetni = if (backendUsesWorkspaces(secili)) {
                    seciliAlan.substringAfterLast("\\").ifBlank { yeniAlanAdi.trim() }
                        .ifBlank { "yeni çalışma alanı" }
                } else {
                    kok
                }
                if (!BuildConfig.IS_LITE && hedefMetni.isNotBlank()) {
                    Text(
                        hedefMetni,
                        style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                        color = if (baslatilabilir) {
                            Ui3Colors.birincilMurekkep.copy(alpha = 0.72f)
                        } else {
                            Ui3Colors.ink3
                        },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.padding(top = 2.dp, start = Ui3Tokens.s16, end = Ui3Tokens.s16),
                    )
                }
            }
        }
    }
}

internal fun liteSaglayiciAciklamasi(id: String): String = when (id) {
    "claude-app" -> "Uzun sohbetler ve genel görevler"
    "codex-app" -> "Kodlama ve proje işleri"
    "agy" -> "Hızlı, genel amaçlı yardım"
    else -> "Yeni bir oturum başlat"
}

@Composable
private fun SaglayiciKarti(
    id: String,
    etiket: String,
    aciklama: String,
    monogram: String,
    secili: Boolean,
    onTikla: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onTikla)
            .testTag(if (secili) "saglayici_karti_secili" else "saglayici_karti"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
        tint = if (secili) GlassTint.Vio else GlassTint.Notr,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ui3Tokens.s12, vertical = Ui3Tokens.s12),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(id, olcu = 32.dp, metin = monogram)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    etiket,
                    style = Ui3Type.govde,
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    aciklama,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink3,
                    fontWeight = FontWeight.Normal,
                )
            }
            if (secili) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Seçili",
                    tint = Ui3Colors.vurguHi,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SaglayiciCipi(
    id: String,
    etiket: String,
    monogram: String,
    secili: Boolean,
    onTikla: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .clickable(onClick = onTikla)
            .testTag(if (secili) "saglayici_secili" else "saglayici"),
        shape = RoundedCornerShape(Ui3Tokens.r12),
        tint = if (secili) GlassTint.Vio else GlassTint.Notr,
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(id, olcu = 20.dp, metin = monogram)
            Text(
                etiket,
                style = Ui3Type.alt,
                color = if (secili) Ui3Colors.ink else Ui3Colors.ink2,
                fontWeight = if (secili) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (secili) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Seçili",
                    tint = Ui3Colors.vurguHi,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun CalismaAlanlari(
    uiState: RemoteUiState,
    seciliAlan: String,
    yeniAlanAdi: String,
    onAlan: (String) -> Unit,
    onYeniAd: (String) -> Unit,
) {
    Text("ÇALIŞMA ALANI", style = Ui3Type.etiket, color = Ui3Colors.ink3)
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Ui3Tokens.r26),
    ) {
        Column(
            Modifier.padding(Ui3Tokens.s12),
            verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        ) {
            AlanSatiri(
                baslik = "Yeni çalışma alanı",
                detay = "Bir ad yaz ya da boş bırak (otomatik ad üretilir)",
                secili = seciliAlan.isBlank(),
                onTikla = { onAlan("") },
            )
            if (seciliAlan.isBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Ui3Tokens.r12))
                        .background(Ui3Colors.kuyu)
                        .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r12))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (yeniAlanAdi.isEmpty()) {
                        Text("Çalışma alanı adı (opsiyonel)", style = Ui3Type.alt, color = Ui3Colors.ink3)
                    }
                    BasicTextField(
                        value = yeniAlanAdi,
                        onValueChange = onYeniAd,
                        singleLine = true,
                        textStyle = TextStyle(color = Ui3Colors.ink, fontSize = Ui3Type.alt.fontSize),
                        cursorBrush = SolidColor(Ui3Colors.vurguHi),
                        modifier = Modifier.fillMaxWidth().testTag("yeni_alan_adi"),
                    )
                }
            }
            uiState.coworkWorkspaces.forEach { alan ->
                AlanSatiri(
                    baslik = alan.name,
                    detay = alan.path,
                    secili = seciliAlan == alan.path,
                    onTikla = { onAlan(alan.path) },
                )
            }
        }
    }
}

@Composable
private fun AlanSatiri(baslik: String, detay: String, secili: Boolean, onTikla: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(if (secili) Ui3Colors.vurgu.copy(alpha = 0.16f) else Ui3Colors.yuzey1)
            .clickable(onClick = onTikla)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("calisma_alani"),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (secili) Icons.Filled.Star else Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = if (secili) Ui3Colors.vurguHi else Ui3Colors.ink3,
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(baslik, style = Ui3Type.govde, color = Ui3Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detay.isNotBlank()) {
                Text(
                    detay,
                    style = Ui3Type.etiket.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (secili) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Seçili",
                tint = Ui3Colors.vurguHi,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
