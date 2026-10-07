package com.agent.bridge.ui3.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.HubProjectItem
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.hubProjects
import com.agent.bridge.hubSummary
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.nav.Ui3Area
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Tek kullanımlık ölçüler dosya başında ve NEDEN'iyle —
// Ui3ChatScreen'in RAY_GENISLIK/OLUK üslubuyla aynı.
private val KART_YARICAP = Ui3Tokens.r26   // kart yarıçapı: bento döneminden kalan aile ölçüsü
private val KART_DOLGU = Ui3Tokens.s16
private val NOKTA = 8.dp                   // durum noktası
private val DIZIN_IKON_KUTU = 34.dp        // dizin satırı ikon kutusu
private val DIZIN_MIN_Y = 52.dp            // satır asgari boyu: 48dp dokunma hedefi + nefes

// Kartta gösterilecek en çok çalışma alanı. 3 → 5 → 10 (20.08.2026) → 3
// (14.09.2026, kullanıcı isteği: liste 14 alanla kartı boydan boya kaplıyordu).
// Tavan kalıyor çünkü kart nöbet paneli, liste ekranı değil; alan sayısı tavanı
// aşınca "14 alan >" başlığı tümünü açıyor.
private const val ALAN_ONIZLEME = 3

/**
 * ui3 Merkez — ikinci yerleşim (17.08.2026, kullanıcı isteği).
 *
 * İlk yerleşim mockup'ın bento'suydu: dev sayı kartları (BAĞLAM/TESLİMATLAR/
 * NOTLARIM/PROJELER) + 2×2 hızlı erişim. Kullanıcı ekranda görünce reddetti
 * ("kartlar ve yerleşimler kötü; ui2'de daha iyiydi") — haklıydı: dev sayılar
 * gösterişli ama bilgi yoğunluğu düşüktü, "TESLİMATLAR 0" koca bir kartı
 * kaplıyordu ve ui2'nin iki gerçek yeteneği (köprü durumu + yenile) yoktu.
 *
 * Bu yerleşim ui2 Merkez'inin ("Genel görünüm" satır listesi + çalışma alanı
 * önizlemeleri) cam diline çevrilmiş hâli:
 *  - DURUM ŞERİDİ: köprü bağlı mı + yenile (ui2 başlığındaki ikili).
 *  - ÇALIŞMA ALANLARI: nöbet paneli — ilk [ALAN_ONIZLEME] alan canlı
 *    rozetleriyle; yeni teslimat toplamı başlıkta (teslimat SAYFASI yok,
 *    teslimat alanlarda olur). Başlığın sağında "+" ile yeni alan.
 *    İlk sürümde 3'tü ve altında BAĞLAM şeridi vardı; kullanıcı 18.08'de
 *    bağlam kartını kaldırttı (doluluk yüzdesi zaten adada/composer'da
 *    yaşıyor, Merkez'de tekrarı karar verdirmiyordu) ve boşalan yeri
 *    çalışma alanlarına verdirdi, 20.08'de tavanı 10'a çıkarttı.
 *  - DİZİN: ui2 `ListRow`larının karşılığı — ikon + başlık + canlı ayrıntı +
 *    rozet, tek cam kartta ince ayraçlarla. Sayılar artık afiş değil, satırın
 *    ayrıntısında (12 not · 3 bayat).
 *
 * Veri sözleşmesi değişmedi: aynı yüklemeler, aynı state, yeni köprü ucu YOK.
 */
@Composable
internal fun Ui3HubScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onAlanAc: (Ui3Area) -> Unit,
    onOturumlarAc: () -> Unit,
    // Dosyalar dock'ta DEĞİL (anayasa v2 §6, ui2 mimarisi): Merkez'in içinden
    // açılan bir ekran. Rota olarak var, dock öğesi olarak yok.
    onDosyalarAc: () -> Unit,
    onNotlarAc: () -> Unit,
    onProjelerAc: () -> Unit,
    onAlanlarAc: () -> Unit,
    // Kartın sağ üstündeki "+" (20.08.2026, kullanıcı isteği). Yeni bir akış
    // uydurulmadı: zaten var olan "Yeni çalışma alanı" ekranına gidiyor —
    // Merkez'den oraya varmak eskiden iki dokunuştu (tümü → yeni).
    onYeniAlan: () -> Unit,
    // Ad çakışmasın: `onAlanAc` dock ALANI açıyor, bu CALIŞMA alanı açıyor.
    //
    // Yol DEĞİL, ÖĞENİN KENDİSİ geçiyor (18.08.2026): hangi ekranın açılacağı
    // `projectId`'nin dolu olup olmadığına bakıyor ve o karar kökte veriliyor.
    // Yalnız yol geçtiğimizde kök bu bilgiye ulaşamıyor, koşulsuz ESKİ çalışma
    // alanı ekranını açıyordu — bkz. Ui3Root'taki gerekçe.
    onCalismaAlaniAc: (HubProjectItem) -> Unit,
    modifier: Modifier = Modifier,
    // Dock + gezinme çubuğu payı. DOLGU DEĞİL İZ BOŞLUĞU: kartlar camın ve
    // jest çubuğunun arkasından geçsin, altta düz bir bant kalmasın.
    altBosluk: Dp = 0.dp,
    // Geniş ekran (tablet fazı 2): çalışma alanları solda, dizin sağda. Karar
    // kökte veriliyor, ölçü `UI3_MERKEZ_IKI_SUTUN_ESIK` — bu ekran yalnız
    // uygular. Varsayılan false = telefon yolu, tek sütun.
    ikiSutun: Boolean = false,
) {
    // ui2 Merkez'iyle AYNI yüklemeler — gösterilen her sayı bu çağrıların
    // döndüğü state'ten türer; ui3 kendi yükleme düzenini uydurursa iki
    // arayüz farklı tazelikte sayı gösterir.
    LaunchedEffect(Unit) {
        actions.loadProjects()
        actions.loadCoworkWorkspaces()
        actions.loadOperations()
        actions.loadUsage()
        actions.loadCoworkNotes()
    }

    val summary = uiState.hubSummary()
    val projects = uiState.hubProjects()
    val notesState by actions.coworkNotesState.collectAsState()
    val alanlar = projects.filter { it.isCoworkWorkspace }
    val projeSayisi = projects.size - alanlar.size

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s20)
            // YerTutucuEkran'dan devralınan kök etiketi: ölçümler bu adla
            // ekranın varlığını doğruluyordu, ad değişince kör kalmasın.
            .testTag("ekran_merkez"),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        DurumSeridi(
            bagli = uiState.healthOk && uiState.protocolCompatible,
            onYenile = {
                actions.loadProjects()
                actions.loadCoworkWorkspaces()
                actions.loadOperations()
                actions.loadUsage(force = true)
                actions.loadCoworkNotes()
            },
        )

        // İKİ SÜTUN, İKİ AYRI ÇAĞRI DEĞİL: kartların içeriği aynı, yalnız
        // kabı değişiyor. Aynı gövdeyi iki yerde yazmak (ui3'te bir kez
        // yapıldı ve ikisi ayrışmaya başladı) yerine yerel lambda.
        val alanlarKarti: @Composable () -> Unit = {
            KahramanKart(
                alanlar = alanlar.take(ALAN_ONIZLEME),
                toplam = alanlar.size,
                yeniTeslimat = summary.workspaceNewDeliveryCount,
                onTumu = onAlanlarAc,
                onAlan = onCalismaAlaniAc,
                onYeni = onYeniAlan,
            )
        }
        val dizinKarti: @Composable () -> Unit = {
            DizinKart(
            satirlar = listOf(
                DizinSatirVerisi(
                    ikon = Icons.Outlined.History,
                    baslik = "Oturumlar",
                    ayrinti = "Disk geçmişi ve arama",
                    testEtiketi = "hub_dizin_oturumlar",
                    onTikla = onOturumlarAc,
                ),
                DizinSatirVerisi(
                    ikon = Icons.Outlined.Folder,
                    baslik = "Projeler",
                    ayrinti = if (projeSayisi > 0) "$projeSayisi proje" else "Backend proje klasörleri",
                    rozet = summary.newDeliveryCount.takeIf { it > 0 }?.let { "$it yeni" },
                    rozetRenk = Ui3Colors.done,
                    testEtiketi = "hub_dizin_projeler",
                    onTikla = onProjelerAc,
                ),
                DizinSatirVerisi(
                    ikon = Icons.Outlined.StickyNote2,
                    baslik = "Notlarım",
                    ayrinti = "${notesState.notes.size} not · Genel ve proje",
                    testEtiketi = "hub_dizin_notlar",
                    onTikla = onNotlarAc,
                ),
                DizinSatirVerisi(
                    ikon = Icons.Outlined.FolderOpen,
                    baslik = "Dosyalar",
                    ayrinti = "PC gezgini ve telefon indirilenleri",
                    testEtiketi = "hub_dizin_dosyalar",
                    onTikla = onDosyalarAc,
                ),
                DizinSatirVerisi(
                    ikon = Icons.Outlined.Terminal,
                    baslik = "Operasyonlar",
                    ayrinti = "Çalışan süreçler",
                    testEtiketi = "hub_dizin_operasyon",
                    onTikla = { onAlanAc(Ui3Area.Operasyon) },
                ),
            ),
            )
        }

        if (ikiSutun) {
            // Üstten hizalı: iki kartın boyu farklı (5 alan vs 6 satır) ve
            // ortalanınca kısa olan havada duruyordu.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) { alanlarKarti() }
                Box(Modifier.weight(1f)) { dizinKarti() }
            }
        } else {
            alanlarKarti()
            dizinKarti()
        }

        // Son kartın altında dock + gezinme çubuğu kadar boşluk. NavHost artık
        // hiç inset vermiyor, bu yüzden kaydırılan gövdenin sonunda duruyor:
        // kart dibe kadar kayabiliyor ama sonuncusu dock'un altında kalmıyor.
        Box(Modifier.padding(bottom = altBosluk + Ui3Tokens.s12))
    }
}

/**
 * Köprü durumu + yenile — ui2 başlığının iki gerçek yeteneği, ilk bento bunları
 * DÜŞÜRMÜŞTÜ. İnce bir cam şerit: kart değil, ekranın nabız satırı.
 */
@Composable
private fun DurumSeridi(bagli: Boolean, onYenile: () -> Unit) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth().testTag("hub_durum"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier.padding(horizontal = KART_DOLGU, vertical = Ui3Tokens.s8),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(NOKTA)
                    .clip(CircleShape)
                    .background(if (bagli) Ui3Colors.done else Ui3Colors.danger),
            )
            Text(
                if (bagli) "Köprü bağlı" else "Köprü bağlantısı yok",
                style = Ui3Type.alt,
                color = Ui3Colors.ink2,
                modifier = Modifier.weight(1f),
            )
            // 40dp dokunma kutusu: satır ince, ikonun hedefi ince kalmasın.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onYenile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Merkezi yenile", tint = Ui3Colors.ink2)
            }
        }
    }
}

/**
 * Kahraman kart: çalışma alanları canlı durumlarıyla. hubProjects sıralaması
 * (sabitli → yeni teslimat → çalışıyor → etkinlik) zaten "önemli olan üstte"
 * veriyor, o yüzden kesme her zaman en az önemliyi kesiyor.
 *
 * Tavan 3 → 5 (18.08, bağlam kartı kalkınca boşalan yer) → 10 (20.08) →
 * [ALAN_ONIZLEME] = 3 (14.09): 14 alanda liste kartı boydan boya kaplıyor ve
 * Merkez'in altındaki dizin satırları ekrandan düşüyordu.
 *
 * Yeni teslimat toplamı BAŞLIKTA: eski bento'nun "TESLİMATLAR" kartındaki tek
 * gerçek bilgi buydu ve teslimatlar zaten çalışma alanlarında yaşıyor — koca
 * bir "0" kartı yerine ait olduğu başlığa rozet.
 */
@Composable
private fun KahramanKart(
    alanlar: List<HubProjectItem>,
    toplam: Int,
    yeniTeslimat: Int,
    onTumu: () -> Unit,
    onAlan: (HubProjectItem) -> Unit,
    onYeni: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth().testTag("hub_kahraman"),
        shape = RoundedCornerShape(KART_YARICAP),
    ) {
        Column(Modifier.padding(KART_DOLGU), verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12)) {
            // Başlık satırı listenin TAMAMINI açar, satırlar tek alanı açar.
            // Kartın tamamını tıklanabilir yapmak iki hedefi çakıştırırdı.
            //
            // "+" TIKLANABİLİRİN DIŞINDA: iç içe iki `clickable` yazılsaydı
            // dıştaki (tümü) alttaki dokunuşu da yakalar, artıya basmak listeyi
            // açardı. Bu yüzden başlık kendi Row'unda `weight(1f)` ile duruyor,
            // artı onun KARDEŞİ.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Ui3Tokens.r12))
                        .clickable(onClick = onTumu)
                        .testTag("hub_alanlar_tumu"),
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ÇALIŞMA ALANLARI",
                        style = Ui3Type.etiket,
                        color = Ui3Colors.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    if (yeniTeslimat > 0) DurumRozeti("$yeniTeslimat yeni teslimat", Ui3Colors.done)
                    Text("$toplam alan", style = Ui3Type.alt, color = Ui3Colors.ink3)
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = Ui3Colors.ink3,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // Görünen kutu 30dp; dokunma hedefini Compose `clickable`ın
                // asgari 48dp kuralı büyütüyor ve burada kardeş bir kaydırma
                // düğümü yok, yani genişleme gerçekten çalışıyor (akıştaki
                // "dibe in" tuşunda çalışmıyordu, orası liste ÜSTÜNDEydi).
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Ui3Colors.yuzey2)
                        .border(1.dp, Ui3Colors.cizgi, CircleShape)
                        .clickable(onClick = onYeni)
                        .testTag("hub_alan_yeni"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Yeni çalışma alanı",
                        tint = Ui3Colors.vurguHi,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (alanlar.isEmpty()) {
                Text("Henüz çalışma alanı yok.", style = Ui3Type.govde, color = Ui3Colors.ink2)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8)) {
                    alanlar.forEach { alan ->
                        // Yol yedeği (workspacePath null → path) artık kökte:
                        // hedef ekran kararıyla aynı yerde durması gerekiyor.
                        AlanSatiri(alan, onTikla = { onAlan(alan) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlanSatiri(alan: HubProjectItem, onTikla: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .clickable(onClick = onTikla),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Durum noktası: cyan yalnız GERÇEKTEN çalışan tur için (anlam renkten
        // önce gelir, anayasa v2 §2); çalışmayan alan nötr — "bitti" uydurma
        // bilgi olurdu, çalışma alanı bitmez, bekler.
        Box(
            Modifier
                .size(NOKTA)
                .clip(CircleShape)
                .background(
                    if (alan.runningCount > 0) Ui3Colors.running
                    else Ui3Colors.ink3.copy(alpha = 0.5f)
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                alan.title,
                style = Ui3Type.alt.copy(fontWeight = FontWeight.SemiBold),
                color = Ui3Colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${alan.sessionCount} oturum · ${alan.outputCount} teslimat",
                style = Ui3Type.alt,
                color = Ui3Colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            alan.runningCount > 0 -> DurumRozeti("${alan.runningCount} çalışıyor", Ui3Colors.running)
            alan.newOutputCount > 0 -> DurumRozeti("${alan.newOutputCount} yeni", Ui3Colors.done)
        }
    }
}

@Composable
private fun DurumRozeti(metin: String, renk: Color) {
    Text(
        metin,
        style = Ui3Type.rozet,
        color = renk,
        modifier = Modifier
            .clip(Ui3Tokens.pill)
            .background(renk.copy(alpha = 0.10f))
            .border(1.dp, renk.copy(alpha = 0.30f), Ui3Tokens.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Dizin satırının verisi — satır çizimi tek yerde kalsın diye ayrık. */
private data class DizinSatirVerisi(
    val ikon: ImageVector,
    val baslik: String,
    val ayrinti: String,
    val testEtiketi: String,
    val onTikla: () -> Unit,
    val rozet: String? = null,
    val rozetRenk: Color = Color.Unspecified,
)

/**
 * Dizin — ui2 "Genel görünüm" kartının cam karşılığı: tüm alt ekranlar tek
 * kartta, ikon + başlık + CANLI ayrıntı + rozet. Eski 2×2 "hızlı erişim"
 * hapları ve dev sayı kartları bunun içinde eridi: sayı artık satırın
 * ayrıntısında ve yanında ne olduğu yazıyor.
 */
@Composable
private fun DizinKart(satirlar: List<DizinSatirVerisi>) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth().testTag("hub_dizin"),
        shape = RoundedCornerShape(KART_YARICAP),
    ) {
        Column(Modifier.padding(vertical = Ui3Tokens.s4)) {
            satirlar.forEachIndexed { i, satir ->
                DizinSatiri(satir)
                if (i != satirlar.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // Ayraç ikon kutusunun sağından başlar (ui2 ListRow
                            // ayracı gibi): tam genişlik ayraç sıra sıra çizgi
                            // görünümü veriyor, içerikten hizalı ayraç vermiyor.
                            .padding(start = KART_DOLGU + DIZIN_IKON_KUTU + Ui3Tokens.s12)
                            .height(1.dp)
                            .background(Ui3Colors.cizgiInce),
                    )
                }
            }
        }
    }
}

@Composable
private fun DizinSatiri(satir: DizinSatirVerisi) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DIZIN_MIN_Y)
            .clickable(onClick = satir.onTikla)
            .padding(horizontal = KART_DOLGU, vertical = Ui3Tokens.s8)
            .testTag(satir.testEtiketi),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DIZIN_IKON_KUTU)
                .clip(RoundedCornerShape(Ui3Tokens.r12))
                .background(Ui3Colors.yuzey2)
                .border(1.dp, Ui3Colors.cizgi, RoundedCornerShape(Ui3Tokens.r12)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(satir.ikon, contentDescription = null, tint = Ui3Colors.ink2, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                satir.baslik,
                style = Ui3Type.alt.copy(fontWeight = FontWeight.SemiBold),
                color = Ui3Colors.ink,
            )
            Text(
                satir.ayrinti,
                style = Ui3Type.alt,
                color = Ui3Colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (satir.rozet != null) DurumRozeti(satir.rozet, satir.rozetRenk)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Ui3Colors.ink3,
            modifier = Modifier.size(16.dp),
        )
    }
}
