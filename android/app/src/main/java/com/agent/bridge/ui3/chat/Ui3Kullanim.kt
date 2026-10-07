package com.agent.bridge.ui3.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.agent.bridge.RUNPOD_USAGE_CARD_KEY
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.isUsageCardVisible
import com.agent.bridge.usageCardToggles
import com.agent.bridge.visibleUsageGroups
import com.agent.bridge.ui2.hub.RunPodUsageCard
import com.agent.bridge.ui2.hub.UsageCardVisibilityList
import com.agent.bridge.ui2.hub.UsageGroupCard
import com.agent.bridge.ui2.hub.splitUsageGroupsAfterClaude
import com.agent.bridge.ui3.material.Ui3OduncKap
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Kullanım sheet'i — Merkez > Kullanım ile AYNI kart kümesi (anayasa 2).
 *
 * ui3'ün ilk sürümü yalnız grupları listeliyordu; ui2'de olan **force yenile**
 * yoktu (kullanıcı bildirdi) ve onunla birlikte kart göster/gizle, RunPod
 * kartı, yükleme iskeleti ve "hepsi gizli" çıkışı da eksikti.
 *
 * `force = true`: köprüdeki 5 dakikalık limit önbelleğini de atlar. Normal
 * yenileme önbellekten dönebildiği için "yenilemedim mi?" hissi veriyordu —
 * ui2'de bu yüzden ayrı bir tuş.
 *
 * Kartların kendisi ui2'den ([UsageGroupCard], [RunPodUsageCard],
 * [UsageCardVisibilityList]) ve **[Ui2Theme] ile sarılı**: ui2 bileşenleri
 * `LocalUi2Colors`tan okuyor, ui3 ağacında o local yok ve varsayılanına
 * (koyu palet) düşüyor — açık temada beyaz üstüne beyaz yazı çıkıyordu.
 */
@Composable
internal fun ColumnScope.Ui3Kullanim(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onGeri: (() -> Unit)? = null,
) {
    var kartlariDuzenle by remember { mutableStateOf(false) }

    // YÜKLEME BURADA, SHEET'İ AÇAN YERDE DEĞİL.
    //
    // Sheet yalnız açıkken besteleniyor, yani bu efekt tam "kullanım açıldı"
    // anında çalışıyor — oturum çekmecesinin yaptığının aynısı.
    //
    // Önce yükleme çağıranın işiydi (`Ui3Root.acikSheetAc`) ve sheet'i açan ÜÇ
    // yoldan biri onu atlıyordu: Spotlight'taki "Kalan kullanım" eylemi
    // `acikSheet`i doğrudan yazıyor, kapıdan geçmiyor. O yoldan açınca RunPod
    // durumu HİÇ sorulmuyor, `runpod.enabled` false kalıyor ve RunPod kartı hiç
    // çizilmiyordu — üstelik eylemin kendi altyazısı "Limitler ve RunPod"
    // diyor (kullanıcı bildirdi 20.08.2026). Kullanım grupları da aynı sebeple
    // bayat kalıyordu.
    //
    // Ekran kendi verisini kendi isteyince bu boşluk yapısal olarak kapanıyor:
    // yeni bir açılış yolu eklendiğinde kimsenin bir şey hatırlaması gerekmiyor.
    LaunchedEffect(Unit) {
        actions.loadUsage()
        actions.refreshRunPodStatus(showErrors = false)
    }

    val runpodAcik = uiState.opencode.runpod.enabled
    val gorunenler = visibleUsageGroups(uiState.usage.groups, uiState.hiddenUsageCards)
    val (claudeyeKadar, claudedenSonra) = splitUsageGroupsAfterClaude(gorunenler)
    val runpodGoster = runpodAcik && isUsageCardVisible(RUNPOD_USAGE_CARD_KEY, uiState.hiddenUsageCards)

    SheetBasligi("KALAN KULLANIM", uiState.usage.note.ifBlank { null }, onGeri)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.usage.groups.isNotEmpty() || runpodAcik) {
            SheetTusu(
                metin = if (kartlariDuzenle) "Bitti" else "Kartlar",
                testEtiketi = "kullanim_kartlar",
                onTikla = { kartlariDuzenle = !kartlariDuzenle },
            )
        }
        Spacer(Modifier.weight(1f))
        SheetTusu(
            metin = if (uiState.usageLoading) "Yenileniyor…" else "Yenile",
            testEtiketi = "kullanim_yenile",
            etkin = !uiState.usageLoading,
            vurgulu = true,
            onTikla = {
                // force = true: köprünün 5 dakikalık önbelleğini de atla.
                actions.loadUsage(force = true)
                actions.refreshRunPodStatus(showErrors = false)
            },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s16),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Ui3OduncKap {
            if (kartlariDuzenle) {
                UsageCardVisibilityList(
                    rows = usageCardToggles(uiState.usage.groups, runpodAcik, uiState.hiddenUsageCards),
                    onToggle = actions::setUsageCardVisible,
                    onShowAll = actions::showAllUsageCards,
                )
            }
            claudeyeKadar.forEach { UsageGroupCard(it) }
            if (runpodGoster) {
                RunPodUsageCard(
                    status = uiState.opencode.runpod,
                    onStart = actions::startRunPod,
                    onStop = actions::stopRunPod,
                )
            }
            claudedenSonra.forEach { UsageGroupCard(it) }
        }
        if (uiState.usage.groups.isEmpty()) {
            Text(
                if (uiState.usageLoading) "Yükleniyor…" else "Kullanım verisi yok.",
                style = Ui3Type.govde,
                color = Ui3Colors.ink2,
            )
        } else if (claudeyeKadar.isEmpty() && claudedenSonra.isEmpty() && !runpodGoster && !kartlariDuzenle) {
            // Hepsi gizliyken sheet bomboş görünmesin; çıkış yolu yazılı olsun.
            Text(
                "Tüm kullanım kartları gizli — \"Kartlar\" ile geri getirebilirsin.",
                style = Ui3Type.alt,
                color = Ui3Colors.ink3,
            )
        }
    }
}

@Composable
private fun SheetTusu(
    metin: String,
    testEtiketi: String,
    onTikla: () -> Unit,
    etkin: Boolean = true,
    vurgulu: Boolean = false,
) {
    Text(
        metin,
        style = Ui3Type.alt,
        color = when {
            !etkin -> Ui3Colors.ink3
            vurgulu -> Ui3Colors.vurguHi
            else -> Ui3Colors.ink2
        },
        fontWeight = if (vurgulu) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(Ui3Tokens.pill)
            .clickable(enabled = etkin, onClick = onTikla)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testEtiketi),
    )
}
