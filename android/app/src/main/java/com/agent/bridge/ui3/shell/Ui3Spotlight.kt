package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.GlobalSearchHit
import com.agent.bridge.ProjectSession
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import com.agent.bridge.visibleTabs

/** Spotlight'ta çalıştırılabilen bir komut. */
internal data class SpotlightEylem(
    val kimlik: String,
    val etiket: String,
    val detay: String,
    val ikon: ImageVector,
    val calistir: () -> Unit,
)

/** Sorgunun köprüye gitmeye değer sayıldığı en kısa uzunluk. */
private const val ASGARI_SORGU = 2

/**
 * Faz 5 — Spotlight. Tek palet: açık oturumlar + eylemler + köprü araması.
 *
 * PLANIN KURALI: "Spotlight mevcut arama ekranının ve oturum sheet'inin YERİNE
 * geçer, yanına eklenmez." Uygulanan hali:
 *  - dock'un **Oturumlar** öğesi artık bu paleti açıyor (eski `OturumSecici`
 *    sheet'i kalktı — o yalnız açık sekmeleri düz bir liste olarak gösteriyordu),
 *  - Projeler ekranındaki arama ikonu `hub/search` rotasına gitmek yerine yine
 *    burayı açıyor; o rota kaldırıldı.
 *
 * ÜÇ BÖLÜM, biri diğerinin yerine geçmiyor:
 *  1. **Açık oturumlar** — `visibleTabs`, YERELDE süzülür. Köprüye gitmeye gerek
 *     yok; zaten elde ve sorgu boşken palet bir başlatıcı gibi çalışsın.
 *  2. **Eylemler** — çağıranın verdiği komut listesi (yeni oturum, dosyalar,
 *     notlar, projeler, ayarlar…). Etiket ve detayda süzülür.
 *  3. **Sonuçlar** — köprünün genel araması (`/search/global`): proje, DİSKTEKİ
 *     oturum ve mesaj isabetleri. Yalnız sorgu [ASGARI_SORGU] karakteri geçince
 *     istenir; tek harfe bütün geçmişi taratmak boşuna tur.
 *
 * Disk oturumları için ayrı bir "tüm oturumlar" listesi YOK: köprünün araması
 * zaten `session` tipinde isabet döndürüyor. İkinci bir kaynak eklemek aynı
 * oturumu iki farklı satır olarak gösterirdi.
 *
 * İsabetlere tıklama davranışı ui2'nin `HubSearchScreen`'iyle BİREBİR aynı —
 * özellikle mesaj isabeti: oturum açma ve derin bağlantı tek sıralı ViewModel
 * işleminde yürür (`openGlobalSearchMessageHit`), yoksa arama yanlış/eski
 * oturumda çalışıyor.
 */
@Composable
internal fun ColumnScope.Ui3Spotlight(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    eylemler: List<SpotlightEylem>,
    onOturum: (String) -> Unit,
    onSohbeteGec: () -> Unit,
    onProjeAc: () -> Unit,
    onKapat: () -> Unit,
) {
    var sorgu by remember { mutableStateOf("") }
    val odak = remember { FocusRequester() }

    // Sorgu köprüye AKTARILIR ama yalnız anlamlı uzunlukta. Boşalınca da
    // gönderiliyor: aksi halde eski sonuçlar palette asılı kalır.
    LaunchedEffect(sorgu) {
        actions.updateGlobalSearchQuery(if (sorgu.length >= ASGARI_SORGU) sorgu else "")
    }
    // Palet kapanınca köprü araması da bırakılır — ui2'de `dismissGlobalSearch`
    // aynı işi yapıyor; yapılmazsa bir sonraki açılışta eski isabetler görünür.
    androidx.compose.runtime.DisposableEffect(Unit) {
        odak.requestFocus()
        onDispose { actions.dismissGlobalSearch() }
    }

    SheetBasligi("SPOTLIGHT", "Oturum, komut ve mesaj — tek yerde")

    // ---- arama alanı
    Row(
        Modifier
            .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .background(Ui3Colors.yuzey2)
            .border(1.dp, Ui3Colors.cizgi, RoundedCornerShape(Ui3Tokens.r18))
            .padding(horizontal = Ui3Tokens.s12, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Ui3Colors.ink3, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (sorgu.isEmpty()) {
                Text("Oturum, komut, mesaj…", style = Ui3Type.govde, color = Ui3Colors.ink3)
            }
            BasicTextField(
                value = sorgu,
                onValueChange = { sorgu = it },
                singleLine = true,
                textStyle = Ui3Type.govde.copy(color = Ui3Colors.ink),
                cursorBrush = SolidColor(Ui3Colors.vurguHi),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(odak)
                    .testTag("spotlight_sorgu"),
            )
        }
        if (sorgu.isNotEmpty()) {
            Text(
                "Temizle",
                style = Ui3Type.alt,
                color = Ui3Colors.vurguHi,
                modifier = Modifier.clip(Ui3Tokens.pill).clickable { sorgu = "" }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }

    // ---- süzülmüş bölümler
    val sekmeler = uiState.visibleTabs.filter { sekme ->
        val etiket = sekme.title.ifBlank { BACKEND_LABELS[sekme.backend] ?: sekme.backend }
        sorgu.isBlank() || etiket.contains(sorgu, true) || sekme.backend.contains(sorgu, true)
    }
    val secilenEylemler = eylemler.filter {
        sorgu.isBlank() || it.etiket.contains(sorgu, true) || it.detay.contains(sorgu, true)
    }
    val arama = uiState.search
    val isabetler = if (sorgu.length >= ASGARI_SORGU) arama.hits else emptyList()

    Column(
        Modifier
            .fillMaxWidth()
            // Sabit yükseklik YOK, tavan var: palet klavye açıkken ekranı
            // taşırmasın ama az sonuçta boşuna yer kaplamasın.
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = Ui3Tokens.s8),
    ) {
        if (sekmeler.isNotEmpty()) {
            Baslik("AÇIK OTURUMLAR")
            sekmeler.forEach { sekme ->
                val etiket = sekme.title.ifBlank { BACKEND_LABELS[sekme.backend] ?: sekme.backend }
                val durum = uiState.tabStatuses[sekme.id]
                Satir(
                    ikon = Icons.Outlined.ChatBubbleOutline,
                    baslik = etiket,
                    detay = BACKEND_LABELS[sekme.backend] ?: sekme.backend,
                    seciliMi = sekme.id == uiState.activeTabId,
                    // Nokta yalnız ANLAM taşıdığında: çalışan ya da onay bekleyen
                    // oturum palette de ayırt edilsin (anayasa v2 §2).
                    nokta = when {
                        durum?.awaitingApproval == true -> Ui3Colors.attention
                        durum?.running == true -> Ui3Colors.running
                        durum?.finishedUnseen == true -> Ui3Colors.done
                        else -> null
                    },
                    testEtiketi = "spotlight_oturum",
                ) {
                    onOturum(sekme.id)
                    onKapat()
                }
            }
        }

        if (secilenEylemler.isNotEmpty()) {
            Baslik("EYLEMLER")
            secilenEylemler.forEach { eylem ->
                Satir(
                    ikon = eylem.ikon,
                    baslik = eylem.etiket,
                    detay = eylem.detay,
                    testEtiketi = "spotlight_eylem",
                ) {
                    onKapat()
                    eylem.calistir()
                }
            }
        }

        when {
            sorgu.length < ASGARI_SORGU -> Unit
            arama.loading && isabetler.isEmpty() -> Bilgi("Aranıyor…")
            arama.error.isNotBlank() -> Bilgi(arama.error)
            // Sonuç YOK ama uyarı VAR: "bulunamadı" demek yanlış olur, çünkü
            // bakılamamış olabilir. İkisi ayrı cümle.
            isabetler.isEmpty() -> {
                Bilgi(
                    if (arama.warnings.isEmpty()) "Köprüde sonuç yok."
                    else "Köprüde sonuç yok — ama arama tamamlanamadı:",
                )
                arama.warnings.forEach { Bilgi(it) }
            }
            else -> {
                Baslik("SONUÇLAR")
                // EKSİK ARAMA UYARISI: köprü bir sağlayıcıyı süre bütçesinde
                // bitiremediyse sonuç listesi yarımdır. Uyarı gösterilmezse
                // "bulunamadı" ile "bakılamadı" aynı görünür — bu ayrımı
                // yapamamak aramaya duyulan güveni sessizce yiyor.
                arama.warnings.forEach { Bilgi(it) }
                isabetler.forEach { isabet ->
                    Satir(
                        ikon = when (isabet.type) {
                            "project" -> Icons.Outlined.FolderOpen
                            "session" -> Icons.Outlined.ChatBubbleOutline
                            else -> Icons.Outlined.Description
                        },
                        baslik = isabet.title.ifBlank { isabet.projectName },
                        detay = isabet.snippet.ifBlank { isabet.projectName },
                        detayMaksSatir = 2,
                        testEtiketi = "spotlight_isabet",
                    ) {
                        onKapat()
                        isabetiAc(isabet, sorgu, actions, onSohbeteGec, onProjeAc)
                    }
                }
            }
        }

        if (sekmeler.isEmpty() && secilenEylemler.isEmpty() && sorgu.length < ASGARI_SORGU) {
            Bilgi("Açık oturum yok — aramak için yazmaya başla.")
        }
    }
}

/**
 * İsabet türüne göre gezinme. ui2'nin `HubSearchScreen`'indeki `when` ile aynı;
 * özellikle `session` dalındaki [ProjectSession] alanları birebir kopyalandı —
 * eksik alan verilince köprü oturumu farklı kimlikle açıyor.
 */
private fun isabetiAc(
    isabet: GlobalSearchHit,
    sorgu: String,
    actions: RemoteViewModel,
    onSohbeteGec: () -> Unit,
    onProjeAc: () -> Unit,
) {
    when (isabet.type) {
        "project" -> {
            actions.loadProjectDetail(isabet.projectId)
            onProjeAc()
        }
        "session" -> {
            val oturum = ProjectSession(
                backend = isabet.backend,
                backendLabel = isabet.backendLabel,
                sessionId = isabet.sessionId,
                model = "",
                status = "",
                summary = "",
                title = isabet.title,
                nativeSessionId = "",
                mtime = isabet.mtime,
                live = false,
                pinned = false,
                archived = false,
                container = isabet.container,
                threadId = "",
            )
            actions.openProjectSession(oturum, isabet.projectPath)
            onSohbeteGec()
        }
        else -> {
            actions.openGlobalSearchMessageHit(isabet, sorgu)
            onSohbeteGec()
        }
    }
}

@Composable
private fun Baslik(metin: String) {
    Text(
        metin,
        style = Ui3Type.etiket,
        color = Ui3Colors.ink3,
        modifier = Modifier.padding(start = Ui3Tokens.s20, top = Ui3Tokens.s12, bottom = Ui3Tokens.s4),
    )
}

@Composable
private fun Bilgi(metin: String) {
    Text(
        metin,
        style = Ui3Type.alt,
        color = Ui3Colors.ink3,
        modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
    )
}

@Composable
private fun Satir(
    ikon: ImageVector,
    baslik: String,
    detay: String,
    testEtiketi: String,
    seciliMi: Boolean = false,
    nokta: androidx.compose.ui.graphics.Color? = null,
    detayMaksSatir: Int = 1,
    onTikla: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ui3Tokens.s12)
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(if (seciliMi) Ui3Colors.yuzey2 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onTikla)
            .padding(horizontal = Ui3Tokens.s8, vertical = 10.dp)
            .testTag(testEtiketi),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ikon, contentDescription = null, tint = Ui3Colors.ink3, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                baslik,
                style = Ui3Type.govde,
                color = if (seciliMi) Ui3Colors.ink else Ui3Colors.ink2,
                fontWeight = if (seciliMi) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detay.isNotBlank()) {
                Text(
                    detay,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink3,
                    maxLines = detayMaksSatir,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (nokta != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(nokta))
        }
    }
}
