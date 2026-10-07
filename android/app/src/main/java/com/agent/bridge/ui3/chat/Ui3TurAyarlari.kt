package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
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
import com.agent.bridge.RemoteUiState
import com.agent.bridge.backendAgentLabel
import com.agent.bridge.backendAgentSupported
import com.agent.bridge.opencodeFamily
import com.agent.bridge.backendAgentsInitSupported
import com.agent.bridge.backendCapabilities
import com.agent.bridge.backendDiffSupported
import com.agent.bridge.backendRevertSupported
import com.agent.bridge.backendShareSupported
import com.agent.bridge.backendEffortLabel
import com.agent.bridge.backendEffortSupported
import com.agent.bridge.backendPermissionModeOptions
import com.agent.bridge.backendSession
import com.agent.bridge.backendSkillOptions
import com.agent.bridge.backendSkillsSupported
import com.agent.bridge.coworkProvider
import com.agent.bridge.coworkProviderLabel
import com.agent.bridge.permissionModeIsPermissive
import com.agent.bridge.permissionModeLabel
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/** Composer şeridindeki ⋯ ile açılan sheet'lerin kimlikleri. */
internal object Ui3Sheet {
    const val MENU = "menu"
    const val MODEL = "model"
    const val IZIN = "izin"
    const val AJAN = "ajan"
    const val EFFORT = "effort"
    const val SKILL = "skill"
    const val SAGLAYICI = "saglayici"
    const val KULLANIM = "kullanim"
    // OTURUMLAR SHEET KİMLİĞİ SİLİNDİ (18.08.2026): oturum çekmecesi bir
    // sheet değil, kendi kabında (GlassYanPanel) yaşayan ayrı bir katman ve
    // durumu `Ui3Root.oturumlarAcik`. Kimlik burada dururken tek sheet
    // yuvasını paylaşıyordu; uzun basış menüyü yuvaya yazınca liste
    // kapanıyordu. Adı geri eklenirse o hata da geri gelir.
    /**
     * Faz 5 Spotlight — dock'ta AYRI bir tuş (kullanıcı kararı 17.08.2026).
     * Oturumlar'ın yerine geçmiyor: o açık sekmeleri gösterir, bu oturum +
     * eylem + köprü aramasını tek palette toplar.
     */
    const val SPOTLIGHT = "spotlight"
    /** Çekmecede oturuma uzun basış — sabitle/arşivle/kimlik/sil. */
    const val OTURUM_MENU = "oturum_menu"
    /** Oturumu yeniden adlandırma — tek satırlık metin alanı. */
    const val OTURUM_AD = "oturum_ad"
    const val SEKME_MENU = "sekme_menu"
    /**
     * Başka bir uygulamadan paylaşılan dosyanın hedefi: sohbet eki mi,
     * çalışma alanı mı, bilgisayarda serbest bir klasör mü?
     *
     * Diğer sheet'lerden farkı KULLANICI AÇMAZ: paylaşım geldiğinde kök
     * kendiliğinden açar (`pendingShare`).
     */
    const val PAYLAS_HEDEF = "paylas_hedef"
    const val ARAC = "arac"
    /**
     * "Bu oturum neyi değiştirdi" — uzun otonom koşu sonrası dosya incelemesi.
     * Yalnız değişiklik ucu olan backend'de görünür (bkz. backendDiffSupported).
     */
    const val DEGISIKLIKLER = "degisiklikler"
    /**
     * "Geri sar" — uzun otonom koşu yanlış yöne saptığında bir kullanıcı
     * mesajına dönüp konuşmayı VE dosyaları o ana geri sarma. Yalnız geri
     * sarma ucu olan backend'de görünür (bkz. backendRevertSupported).
     */
    const val GERI_SAR = "geri_sar"
    /**
     * "Paylaş" — oturumu herkese açık bir linkte yayınlama. Yalnız paylaşım
     * ucu olan backend'de görünür (bkz. backendShareSupported). İki kademeli:
     * onay → link. Onay ATLANAMAZ, çünkü bu uygulamada dış dünyaya veri açan
     * tek eylem bu.
     */
    const val OTURUM_PAYLAS = "oturum_paylas"
    /**
     * "AGENTS.md oluştur" — projeyi analiz edip dosyayı yazan tur. Onaylı,
     * çünkü dokunuş bir tur (ve para) harcıyor.
     */
    const val AGENTS_INIT = "agents_init"
    /**
     * "Oturum Kuralları" (yalnız v2) — oturum talimatları + kayıtlı izin
     * kuralları tek sheet'te. Talimat: AGENTS.md'ye dokunmadan bu oturuma
     * kalıcı kural; izin kuralı: "always" birikiminin envanteri.
     */
    const val KURALLAR = "kurallar"
    /**
     * Bir alt-ajanın salt-okunur konuşması. MENÜDE SATIRI YOK — kart yığınından
     * açılıyor (hangi ajan olduğu ancak orada seçilebilir), bu yüzden
     * `acikSheetAc`'ta da yükleme dalı yok: veriyi karta dokunan çağırıyor.
     */
    const val ALT_AJAN = "alt_ajan"
    /** "＋" → ek dosya nereden: telefon (bayt yükle) ya da bilgisayar (yol ver). */
    const val EK_KAYNAK = "ek_kaynak"
    /**
     * Bağlam ayrıntısı — kullanılan token / pencere / doluluk + Sıkıştır.
     * MENÜDE SATIRI YOK: adaya dokununca açılıyor ve ada yalnız geniş
     * yerleşimde dokunulabilir (telefonda durum çubuğu dokunuşu yutuyor,
     * bkz. AppIsland.YedekAda).
     */
    const val BAGLAM = "baglam"
}

/**
 * ⋯ menüsü — turun composer şeridinde GÖRÜNMEYEN ayarları.
 *
 * Kullanıcı kararı: şeritte yalnız model durur, gerisi buraya iner. ui2'de bu
 * ayarların hepsi sohbet akışının üstüne binen ayrı birer çipti (yedi çip) ve
 * ui3'e hiç taşınmamıştı — bu menü o regresyonu kapatan yer.
 *
 * Satırlar KOŞULLU: bir backend ajanı desteklemiyorsa satır hiç çizilmez.
 * Görünürlük kuralları ui2'nin çip görünürlük kurallarıyla birebir aynı, aynı
 * `backend*Supported` yardımcılarından geliyor — iki arayüz aynı backend'de
 * farklı ayar göstermesin.
 *
 * İzin modu satırı gevşek bir değerdeyse kehribar yazılır; aynı uyarı composer
 * şeridindeki ⋯ tuşunun üstünde nokta olarak da yanar.
 */
@Composable
internal fun ColumnScope.Ui3TurAyarlariMenusu(
    uiState: RemoteUiState,
    backendId: String,
    // Tur sürerken composer şeridi Yenile ve Kullanım'ı gizler (yer darlığı,
    // Ui3Composer başındaki ölçüm). Kullanıcı tam o sırada ikisine de
    // erişebilmek istedi (18.08.2026): tur sürerken bu menüde satır olarak
    // görünürler. Boştayken çizilmezler — şeritteki asıllarının kopyası olurlar.
    calisiyor: Boolean,
    yenileniyor: Boolean,
    onYenile: () -> Unit,
    // TUR SÜRERKEN YAZILAN METNİN GİDECEĞİ İKİ YOL (22.08.2026). Eskiden
    // composer şeridinde tek bir tuştu: dokun = kuyruk, uzun bas = follow_up.
    // Şeritten çıkarıldı (yer, bkz. `ui3SeritYerlesimi`) ve buraya İKİ AYRI
    // satır olarak indi — uzun basış zaten keşfedilmeyen gizli bir jestti.
    // null = o yol şu an yok (tur sürmüyor, metin yazılmamış ya da sağlayıcı
    // desteklemiyor) → satır hiç çizilmez.
    onKuyruk: (() -> Unit)?,
    onAjanaBirak: (() -> Unit)?,
    // Oturumun çalışma klasörünü dosya yöneticisinde açar (cowork'te çalışma
    // alanı, diğerlerinde proje cwd'si). null = cwd yok → satır çizilmez.
    onCalismaKlasoru: (() -> Unit)?,
    onAc: (String) -> Unit,
) {
    val yetenekler = backendCapabilities(backendId, uiState.coworkProvider, uiState.backendCatalog)
    val oturum = uiState.backendSession(backendId)

    SheetBasligi("TUR AYARLARI", null)

    // EN ÜSTTE: bunlar ayar değil, YAZDIĞIN METNE dair eylemler ve yalnız tur
    // sürerken görünürler. Menü uzun olabildiği için (sağlayıcı, izin, ajan,
    // effort, skill, hesap, arama, klasör) aşağıda kalsalar kaydırma isterdi.
    if (onKuyruk != null) {
        AyarSatiri(
            ikon = Icons.Filled.Schedule,
            etiket = "Sonra gönder",
            // Kuyruk kalıcı ve iptal edilebilir; ekleri de taşır (tur ortası
            // uçları yalnız metin taşıyor, bkz. Ui3ChatScreen).
            deger = "kuyruğa al",
            testEtiketi = "menu_kuyruk",
            onTikla = onKuyruk,
        )
    }
    if (onAjanaBirak != null) {
        AyarSatiri(
            ikon = Icons.Filled.SmartToy,
            etiket = "Ajana bırak",
            // native follow_up: mesajı ajan kendi tutar, telefon bağlantısı
            // kopsa bile tur bitince işler.
            deger = "tur bitince işle",
            testEtiketi = "menu_ajana_birak",
            onTikla = onAjanaBirak,
        )
    }
    if (onKuyruk != null || onAjanaBirak != null) {
        Box(
            Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s8)
                .fillMaxWidth()
                .height(1.dp)
                .background(Ui3Colors.yuzey2),
        )
    }

    if (backendId == "cowork") {
        AyarSatiri(
            ikon = Icons.Filled.Hub,
            etiket = "Sağlayıcı",
            deger = coworkProviderLabel(uiState.coworkProvider),
            testEtiketi = "menu_saglayici",
        ) { onAc(Ui3Sheet.SAGLAYICI) }
    }

    if (yetenekler.permissionModes && uiState.backendPermissionModeOptions(backendId).isNotEmpty()) {
        val gevsek = permissionModeIsPermissive(oturum.permissionMode)
        AyarSatiri(
            ikon = Icons.Filled.Shield,
            etiket = "İzin modu",
            deger = permissionModeLabel(oturum.permissionMode),
            // Gevşek mod kehribar: menüyü açtığında da göze çarpsın, "normal"
            // sanıp geçme.
            degerRengi = if (gevsek) Ui3Colors.attention else Ui3Colors.ink,
            testEtiketi = "menu_izin",
        ) { onAc(Ui3Sheet.IZIN) }
    }

    if (uiState.backendAgentSupported(backendId)) {
        AyarSatiri(
            ikon = Icons.Filled.SmartToy,
            etiket = "Ajan",
            deger = uiState.backendAgentLabel(backendId),
            testEtiketi = "menu_ajan",
        ) { onAc(Ui3Sheet.AJAN) }
    }

    if (uiState.backendEffortSupported(backendId)) {
        AyarSatiri(
            ikon = Icons.Filled.Bolt,
            etiket = "Effort",
            deger = uiState.backendEffortLabel(backendId),
            testEtiketi = "menu_effort",
        ) { onAc(Ui3Sheet.EFFORT) }
    }

    if (uiState.backendSkillsSupported(backendId)) {
        val adet = uiState.backendSkillOptions(backendId).size
        AyarSatiri(
            ikon = Icons.Filled.AutoAwesome,
            etiket = "Skill'ler",
            // Salt görüntü: skill seçilmez, model gerektiğinde yükler. Sayı
            // yüklenmeden 0 görünmesin diye boş bırakılıyor.
            deger = if (adet > 0) "$adet kurulu" else "",
            testEtiketi = "menu_skill",
        ) { onAc(Ui3Sheet.SKILL) }
    }

    Box(
        Modifier
            .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s8)
            .fillMaxWidth()
            .height(1.dp)
            .background(Ui3Colors.yuzey2),
    )

    // DEĞİŞİKLİKLER — "Sohbette ara" ile aynı gerekçeyle burada: tura değil
    // OTURUMA ait ve şeritte yeri yok. Ayarlardan SONRA, aramadan ÖNCE:
    // ikisi de "oturumu incele" işi ama uzun koşu bitince ilk sorulan bu.
    //
    // Sayı ÖNCEDEN çekilmiyor: rozete "3 dosya" yazmak için görünüm kapalıyken
    // de köprüye sormak gerekirdi ve o istek oturumun bütün mesaj listesini
    // okuyor. Satır sayısız duruyor, liste açılınca doluyor.
    if (uiState.backendDiffSupported(backendId)) {
        AyarSatiri(
            ikon = Icons.Filled.Difference,
            etiket = "Değişiklikler",
            deger = "",
            testEtiketi = "menu_degisiklikler",
        ) { onAc(Ui3Sheet.DEGISIKLIKLER) }
    }

    // GERİ SAR — "Değişiklikler"in hemen altında, bilerek: ikisi de uzun otonom
    // koşunun sonunda sorulan sorular ve sıraları da öyle ("ne değişti" →
    // "beğenmedim, geri al"). Sayı ÖNCEDEN çekilmiyor (aynı gerekçe: liste
    // isteği oturumun bütün mesaj listesini köprüye okutuyor).
    //
    // Geri sarılmış durumda değer sütunu bunu söylüyor: menüyü açan kullanıcı
    // şeridi kaçırmış olabilir ve ikinci bir geri sarma yapmadan önce bilmeli.
    if (uiState.backendRevertSupported(backendId)) {
        val sarilmis = uiState.opencodeFamily(backendId).reverted != null
        AyarSatiri(
            ikon = Icons.AutoMirrored.Filled.Undo,
            etiket = "Geri sar",
            deger = if (sarilmis) "geri sarıldı" else "",
            degerRengi = if (sarilmis) Ui3Colors.attention else Ui3Colors.ink,
            testEtiketi = "menu_geri_sar",
        ) { onAc(Ui3Sheet.GERI_SAR) }
    }

    // AGENTS.md — "Değişiklikler / Geri sar" ikilisinin ardından, bilerek:
    // onlar koşu SONRASI soruları, bu koşu ÖNCESİ ("ajan bu projeyi tanısın").
    // Sayı/durum sütunu yok: dosyanın var olup olmadığını bilmek için köprüye
    // ayrı bir dosya sorusu gerekirdi ve cevabı satırın davranışını değiştirmiyor
    // (init var olanı da yerinde iyileştiriyor).
    if (uiState.backendAgentsInitSupported(backendId)) {
        AyarSatiri(
            ikon = Icons.Filled.Description,
            etiket = "AGENTS.md oluştur",
            deger = "",
            testEtiketi = "menu_agents_init",
        ) { onAc(Ui3Sheet.AGENTS_INIT) }
    }

    // OTURUM KURALLARI (yalnız v2) — talimat + izin. AGENTS.md'nin hemen
    // altında: ikisi de "ajan bu oturumda nasıl davransın" işi. Sayı rozeti
    // çekilmiyor (görünüm kapalıyken köprüye sormamak için — diff ile aynı
    // kural); liste açılınca doluyor.
    if (backendId == com.agent.bridge.Backend.OPENCODE2_APP.id) {
        AyarSatiri(
            ikon = Icons.Filled.Gavel,
            etiket = "Oturum Kuralları",
            deger = "",
            testEtiketi = "menu_kurallar",
        ) { onAc(Ui3Sheet.KURALLAR) }
    }

    // PAYLAŞ — menünün bu bölümünde, çünkü tura değil OTURUMA ait
    // ("Değişiklikler"le aynı gerekçe). Değer sütunu paylaşılmış hâli SÖYLÜYOR
    // ve kehribar yazılıyor: oturum İNTERNETE açıkken kullanıcı menüyü her
    // açtığında bunu görmeli, gevşek izin kipiyle aynı mantık.
    if (uiState.backendShareSupported(backendId)) {
        val paylasildi = uiState.opencode.share.isNotBlank()
        AyarSatiri(
            ikon = if (paylasildi) Icons.Filled.Public else Icons.Filled.IosShare,
            etiket = if (paylasildi) "Paylaşımı kaldır" else "Paylaş",
            deger = if (paylasildi) "yayında" else "",
            degerRengi = if (paylasildi) Ui3Colors.attention else Ui3Colors.ink,
            testEtiketi = "menu_paylas",
        ) { onAc(Ui3Sheet.OTURUM_PAYLAS) }
    }

    // Lite'ta arama şeritte ikon oldu; onun eski yerini çalışma klasörü aldı.
    // Tam sürümde mevcut yerleşim değişmez.
    if (uiState.liteEdition) {
        if (onCalismaKlasoru != null) {
            AyarSatiri(
                ikon = Icons.Filled.FolderOpen,
                etiket = "Çalışma klasörü",
                deger = "",
                testEtiketi = "menu_calisma_klasoru",
                onTikla = onCalismaKlasoru,
            )
        }
    } else {
        AyarSatiri(
            ikon = Icons.Filled.Search,
            etiket = "Sohbette ara",
            deger = "",
            testEtiketi = "menu_ara",
            onTikla = { onAc("ara") },
        )
    }

    if (calisiyor) {
        AyarSatiri(
            ikon = Icons.Filled.Refresh,
            etiket = if (yenileniyor) "Yenileniyor…" else "Yenile",
            deger = "",
            testEtiketi = "menu_yenile",
            // Yenileme sürerken ikinci tetikleme yok — şeritteki tuşla aynı kural.
            onTikla = { if (!yenileniyor) onYenile() },
        )
        AyarSatiri(
            ikon = Icons.Filled.DataUsage,
            etiket = "Kalan kullanım",
            deger = "",
            testEtiketi = "menu_kullanim",
            onTikla = { onAc(Ui3Sheet.KULLANIM) },
        )
        // ÇALIŞMA KLASÖRÜ ARTIK ŞERİTTE (25.08.2026, kullanıcı: "sık
        // kullanmam gerekiyor, menüye saklanmasın") — 19.08'de buraya konan
        // satır oradan taşındı. Bu satır Yenile/Kullanım'la aynı yedek: tur
        // sürerken şerit sıkışıp tuşu düşürebilir, o an buradan erişilir.
        if (!uiState.liteEdition && onCalismaKlasoru != null) {
            AyarSatiri(
                ikon = Icons.Filled.FolderOpen,
                etiket = "Çalışma klasörü",
                deger = "",
                testEtiketi = "menu_calisma_klasoru",
                onTikla = onCalismaKlasoru,
            )
        }
    }
}

@Composable
private fun AyarSatiri(
    ikon: ImageVector,
    etiket: String,
    deger: String,
    testEtiketi: String,
    degerRengi: Color = Ui3Colors.ink,
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
                .size(30.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Ui3Colors.yuzey2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ikon, contentDescription = null, tint = Ui3Colors.ink2, modifier = Modifier.size(16.dp))
        }
        Text(
            etiket,
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            maxLines = 1,
            // weight: etiket solda kalır, değer sağa itilir. Uzun değer
            // kırpılsın diye ağırlık etikette, değerde değil.
            modifier = Modifier.weight(1f),
        )
        if (deger.isNotBlank()) {
            Text(
                deger,
                style = Ui3Type.alt,
                color = degerRengi,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Ui3Colors.ink3,
            modifier = Modifier.size(17.dp),
        )
    }
}
