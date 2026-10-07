package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.SharedFile
import com.agent.bridge.shareDialogTitle
import com.agent.bridge.shareHasWorkspaceTarget
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui3.chat.Ui3KlasorSecici
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Gezgin sheet'in tamamını kaplamasın; uzun klasör listesi kendi içinde kayar.
// Ui3Selector'ün LISTE_MAKS'ıyla aynı mantık, aynı ölçü ailesinden.
private val GEZGIN_MAKS = 400.dp

/**
 * Paylaş menüsünden gelen dosyanın hedefi — ui3'ün kendi sheet'i.
 *
 * NEDEN ui2'nin `ShareTargetDialog`'u ödünç ALINMADI: ui3'te bu diyalog HİÇ
 * çizilmiyordu (kök `pendingShare`i hiç toplamıyordu), yani ui3'te başka bir
 * uygulamadan dosya paylaşmak sessizce hiçbir şey yapmıyordu — dosya bekleyen
 * durumda kalıyor, önbellek kopyası da silinmiyordu. Boşluk doldurulurken
 * kullanıcı üçüncü bir hedef istedi ("bilgisayara yükle") ve ui2'nin diyaloğuna
 * seçenek eklemek ui2'nin DAVRANIŞINI değiştirmek olurdu (Değişmez 2: ui2'ye
 * yalnız açığa çıkarmak için dokunulur). O yüzden ui3 kendi sheet'ini çiziyor.
 *
 * ÜÇ ADIMLI, ui2'nin iki adımlısıyla aynı gerekçeyle: tipik durum ("sohbete
 * ekle") uzun bir klasör listesinin dibine gömülmesin. Önce "nereye", sonra
 * gerekiyorsa "hangisi".
 */
@Composable
internal fun ColumnScope.Ui3PaylasHedefi(
    dosyalar: List<SharedFile>,
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onKapat: () -> Unit,
) {
    // Adım: null = kök seçim, "alan" = çalışma alanı listesi, "pc" = gezgin.
    var adim by remember { mutableStateOf<String?>(null) }

    when (adim) {
        "alan" -> {
            Ui3Selector(
                baslik = "HANGİ ÇALIŞMA ALANI?",
                altBaslik = shareDialogTitle(dosyalar),
                secenekler = uiState.cowork.workspaces.map { alan ->
                    SelectorOption(
                        value = alan.path,
                        label = alan.name,
                        detail = alan.matter.ifBlank { alan.path },
                    )
                },
                onSec = { yol ->
                    actions.savePendingShareToWorkspace(yol)
                    onKapat()
                },
                detayMaksSatir = 2,
                bosMetin = "Çalışma alanı yok",
            )
        }

        "pc" -> BilgisayaraYukle(uiState, actions, dosyalar, onKapat)

        else -> Ui3Selector(
            baslik = "NEREYE?",
            altBaslik = shareDialogTitle(dosyalar),
            secenekler = buildList {
                add(
                    SelectorOption(
                        value = "sohbet",
                        label = "Sohbete ekle",
                        detail = "Dosya ek olarak yüklenir, mesajla birlikte gider",
                    ),
                )
                // Alan yoksa bu seçenek boş bir listeye götürürdü. Diğer üç
                // seçenek alan gerektirmiyor, o yüzden yalnız bu eleniyor.
                if (shareHasWorkspaceTarget(uiState.cowork.workspaces)) {
                    add(
                        SelectorOption(
                            value = "alan",
                            label = "Çalışma alanına kaydet",
                            detail = "Alanın bilgisayardaki klasörüne kalıcı olarak yazılır",
                        ),
                    )
                }
                add(
                    SelectorOption(
                        value = "pc",
                        label = "Bilgisayara yükle",
                        detail = "Köprü gezginiyle klasör seç — çalışma alanı olmak zorunda değil",
                    ),
                )
                add(
                    SelectorOption(
                        value = "not",
                        label = "Not ekle",
                        detail = "Model içeriği okur, not defterine yazar; tarih varsa hatırlatıcı kurar",
                    ),
                )
            },
            onSec = { secim ->
                when (secim) {
                    "sohbet" -> { actions.attachPendingShareToChat(); onKapat() }
                    // Sheet HEMEN kapanır: çağrı modeli bekliyor (~15 sn) ve
                    // kullanıcı bu sürede cam bir panele bakmak zorunda değil.
                    // Sonuç mesaj olarak düşer.
                    "not" -> { actions.savePendingShareAsNote(); onKapat() }
                    // Alan listesi boşsa bu seçenek zaten anlamsız; kök bu
                    // sheet'i yalnız alan varken açıyor (shareHasWorkspaceTarget).
                    "alan" -> adim = "alan"
                    else -> adim = "pc"
                }
            },
            detayMaksSatir = 2,
        )
    }
}

/**
 * Serbest klasör seçimi — yeni oturum ekranındaki gezginin AYNISI.
 *
 * Hedef seçme kuralı da aynı ve bilerek: "bu klasörü kullan" satırı YOK,
 * hedef **içinde bulunduğun klasördür**. Ayrı bir seçim satırı, klasör
 * değiştirdikten sonra eski seçimin sessizce korunmasına yol açıyordu
 * (bkz. [Ui3KlasorSecici] gerekçesi). Nereye yükleneceği tuşun üstünde yazılı.
 */
@Composable
private fun BilgisayaraYukle(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    dosyalar: List<SharedFile>,
    onKapat: () -> Unit,
) {
    val sonKlasorler = remember { actions.folderPickerRecent() }
    val favoriler = remember { actions.folderPickerFavorites() }
    val dizinler = uiState.workerDirs
    val konum = dizinler?.base.orEmpty()

    // Gezgin daha önce hiç açılmadıysa köprünün varsayılan kökünden başlat.
    // Boş kök = "sen bilirsin" (HubDataDelegate.loadWorkerDirs).
    LaunchedEffect(Unit) { if (dizinler == null) actions.loadWorkerDirs("") }

    SheetBasligi("BİLGİSAYARA YÜKLE", shareDialogTitle(dosyalar))

    Box(
        Modifier
            .heightIn(max = GEZGIN_MAKS)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s12),
    ) {
        Ui3KlasorSecici(
            dizinler = dizinler,
            aramaSonuclari = uiState.folderSearchResults,
            sonKlasorler = sonKlasorler,
            favoriler = favoriler,
            favoriMi = actions.folderPickerIsFavorite(konum),
            konum = konum,
            onGit = { actions.loadWorkerDirs(it) },
            onFavori = { actions.folderPickerToggleFavorite(it) },
            onAra = { actions.updateFolderSearchQuery(konum, it) },
        )
    }

    // Tuş, yeni oturum ekranındaki Başlat'ın birebir deseni: hedef tuşun
    // İÇİNDE yazıyor. "Bu klasörü kullan" satırı olmadığı için hedefi
    // doğrulayabileceğin tek yer burası.
    val yuklenebilir = konum.isNotBlank()
    Box(
        Modifier
            .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s12)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (yuklenebilir) Ui3Colors.birincilZemin else Ui3Colors.yuzey2)
            .clickable(enabled = yuklenebilir) {
                actions.savePendingShareToPath(konum)
                // Bir sonraki paylaşımda aynı klasör çip olarak elinin altında
                // olsun — yeni oturum ekranındaki "son kullanılan" ile aynı liste.
                actions.folderPickerAddRecent(konum)
                onKapat()
            }
            .padding(vertical = 13.dp)
            .testTag("paylas_pc_yukle"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Buraya yükle",
                style = Ui3Type.govde,
                color = if (yuklenebilir) Ui3Colors.birincilMurekkep else Ui3Colors.ink3,
                fontWeight = FontWeight.Bold,
            )
            if (konum.isNotBlank()) {
                Text(
                    konum,
                    style = Ui3Type.alt,
                    color = if (yuklenebilir) Ui3Colors.birincilMurekkep else Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
