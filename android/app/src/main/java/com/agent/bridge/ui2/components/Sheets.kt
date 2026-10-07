package com.agent.bridge.ui2.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Sheet desen kuralı (anayasa 2): sheet HAFİF, TEK SEÇİMLİK seçicidir.
// Sheet üstüne sheet AÇILMAZ; derinleşen akış ekrana dönüşür.

// Tek jenerik seçici: model / effort / izin modu / hesap — hepsi bu.
// Backend'e özel seçici sheet YAZILMAZ (anayasa 3).
data class SelectorOption<T>(
    val value: T,
    val label: String,
    val detail: String? = null,
    val badge: String? = null,
    val info: SelectorInfo? = null,
)

data class SelectorInfo(
    val description: String,
    val bestFor: String,
    val profile: String,
    val sourceUrl: String? = null,
)

// Yarı açık sheet'te kabaca bu kadar satır görünüyor; bu aralıktaki seçim için
// kaydırmaya gerek yok.
private const val FIRST_VISIBLE_ROWS = 3

/**
 * Seçici açılırken hangi satıra kaydırılacağı. null = kaydırma gerekmez
 * (seçili öğe zaten görünür) ya da seçili öğe listede yok.
 */
internal fun selectorScrollTargetIndex(
    selectedIndex: Int,
    firstVisibleRows: Int = FIRST_VISIBLE_ROWS,
): Int? = when {
    selectedIndex < 0 -> null
    selectedIndex < firstVisibleRows -> null
    // Seçilinin bir üstünü tepeye alıyoruz: liste ortasında olduğu belli olsun.
    else -> (selectedIndex - 1).coerceAtLeast(0)
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun selectorSheetVisibleValue(
    currentValue: SheetValue,
    targetValue: SheetValue,
): SheetValue? = when {
    targetValue != SheetValue.Hidden -> targetValue
    currentValue != SheetValue.Hidden -> currentValue
    else -> null
}

internal fun selectorSheetShouldDismissOnBackPress(infoVisible: Boolean): Boolean =
    !infoVisible

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectorSheet(
    title: String,
    options: List<SelectorOption<T>>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    selectedValue: T? = null,
    loading: Boolean = false,
    // Skill listesi gibi açıklamalı seçicilerde detay birkaç satıra yayılır;
    // model/izin seçicilerinde tek satır kalır.
    detailMaxLines: Int = 1,
    emptyText: String? = null,
    // Doluysa satırlarda yıldız çıkar ve sabitlenenler başa alınır. Kapsam adı
    // sabitlemelerin nerede saklanacağını belirler — her backend'in model
    // listesi ayrı kapsam, yoksa biri diğerinin yıldızını görür.
    pinScope: String? = null,
    pinKey: (T) -> String = { it.toString() },
) {
    var infoOption by remember { mutableStateOf<SelectorOption<T>?>(null) }
    val context = LocalContext.current
    val pinStore = remember(pinScope) { pinScope?.let { SelectorPinStore(context) } }
    var pinnedKeys by remember(pinScope) {
        mutableStateOf(pinScope?.let { pinStore?.pinned(it) } ?: emptySet())
    }
    val orderedOptions = if (pinScope == null) options else sortByPins(options, pinnedKeys, pinKey)
    var rememberedListSheetValue by remember { mutableStateOf<SheetValue?>(null) }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()

    fun openInfo(option: SelectorOption<T>) {
        rememberedListSheetValue = selectorSheetVisibleValue(
            currentValue = sheetState.currentValue,
            targetValue = sheetState.targetValue,
        )
        infoOption = option
    }

    fun returnToList() {
        infoOption = null
    }

    // Anchor İKİ YÖNDE de korunur: listeden bilgiye geçerken ve bilgiden listeye
    // dönerken. Önceki sürümde yalnız DÖNÜŞ geri yükleniyordu; bilgiye girerken
    // sheet yeni içeriğin yüksekliğine göre kendi anchor'ını seçtiği için liste
    // tam açıkken (i)'ye basınca bilgi yarım açılabiliyordu. Kullanıcının
    // bıraktığı yükseklik korunmalı — içerik değişti diye sheet zıplamamalı.
    //
    // Kayıt yalnız listeye DÖNÜNCE temizlenir; girişte silinirse dönüş anchor'ı
    // kaybolur.
    LaunchedEffect(infoOption) {
        val restoreValue = rememberedListSheetValue ?: return@LaunchedEffect
        // Yeni içeriğin ölçülmesini bekle, yoksa anchor eski yüksekliğe göre
        // hesaplanıp yanlış yere oturuyor.
        withFrameNanos { Unit }
        when (restoreValue) {
            SheetValue.Expanded -> {
                if (sheetState.hasExpandedState) sheetState.expand() else sheetState.show()
            }
            SheetValue.PartiallyExpanded -> {
                if (sheetState.hasPartiallyExpandedState) {
                    sheetState.partialExpand()
                } else if (sheetState.hasExpandedState) {
                    sheetState.expand()
                } else {
                    sheetState.show()
                }
            }
            SheetValue.Hidden -> Unit
        }
        if (infoOption == null) rememberedListSheetValue = null
    }

    // Seçili öğe listenin dibindeyse sheet yarım açılıp en üstten başlıyordu:
    // kullanıcı hangi modelin seçili olduğunu görmek için elle kaydırmak
    // zorundaydı. Açılışta sheet'i genişletip seçiliye kaydırıyoruz.
    // İlk birkaç satır zaten görünür olduğundan orada dokunmuyoruz — yoksa kısa
    // listelerde sheet gereksiz yere tam ekran açılırdı.
    var scrolledToSelection by remember(pinScope) { mutableStateOf(false) }
    LaunchedEffect(orderedOptions.size, selectedValue, loading) {
        if (scrolledToSelection || loading || selectedValue == null) return@LaunchedEffect
        val index = orderedOptions.indexOfFirst { it.value == selectedValue }
        val target = selectorScrollTargetIndex(index)
        if (index < 0) return@LaunchedEffect
        // LazyColumn ModalBottomSheet'in ALT-KOMPOZİSYONUNDA; bu efekt ilk karede
        // koştuğunda liste henüz yerleşmemiş oluyor ve scrollToItem sessizce
        // düşüyor (ilk denemede tam bu yüzden hiçbir şey olmadı). Önce listenin
        // gerçekten ölçülmesini bekliyoruz.
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it >= orderedOptions.size }
        scrolledToSelection = true
        if (target == null) return@LaunchedEffect
        // expand() sheet daha animasyondayken iptal olabiliyor; patlarsa
        // kaydırma da yapılmadan kalmasın diye ayrı ayrı korunuyorlar.
        runCatching { if (sheetState.hasExpandedState) sheetState.expand() }
        runCatching { listState.scrollToItem(target) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // isFocusable KALKTI (Compose 1.8): sheet artık her zaman odaklanabilir,
        // ayrıca bayrak taşımıyor.
        properties = ModalBottomSheetProperties(
            securePolicy = SecureFlagPolicy.Inherit,
            shouldDismissOnBackPress = selectorSheetShouldDismissOnBackPress(
                infoVisible = infoOption != null,
            ),
        ),
        containerColor = Ui2.colors.sheetSurface,
        shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet),
    ) {
        // Bilgi görünümündeyken sistem geri tuşu bütün sheet'i kapatmasın.
        // Yalnız listeye dönünce aynı LazyListState yaşamaya devam eder ve uzun
        // model listesi tam kaldığı konumda açılır; ikinci geri sheet'i kapatır.
        BackHandler(enabled = infoOption != null) {
            returnToList()
        }
        Column(Modifier.padding(horizontal = Ui2Tokens.screenPadding)) {
            val shownOption = infoOption
            if (shownOption?.info != null) {
                SelectorInfoContent(
                    modelName = shownOption.label,
                    info = shownOption.info,
                    onBack = ::returnToList,
                )
            } else {
                SheetTitle(title, subtitle)
                if (loading) {
                    LoadingSkeleton(rows = 3, modifier = Modifier.padding(bottom = Ui2Tokens.sheetBottom))
                } else if (options.isEmpty() && emptyText != null) {
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ui2.colors.ink2,
                        modifier = Modifier.padding(bottom = Ui2Tokens.sheetBottom),
                    )
                } else {
                    LazyColumn(
                        Modifier.padding(bottom = Ui2Tokens.sheetBottom),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                    ) {
                        items(orderedOptions) { opt ->
                            val selected = selectedValue != null && opt.value == selectedValue
                            val optionKey = if (pinScope == null) null else pinKey(opt.value)
                            val pinned = optionKey != null && optionKey in pinnedKeys
                            ListRow(
                                title = opt.label,
                                detail = opt.detail,
                                detailMaxLines = detailMaxLines,
                                onClick = { onSelect(opt.value) },
                                trailing = {
                                    // Yildiz ve (i) IconButton iken her biri 48dp
                                    // yer kapliyordu; aralarinda da satirin 12dp'lik
                                    // bosluğu vardi. Ucu ucuna ~110dp: uzun model
                                    // adlari bu yuzden erken kirpiliyordu. Ikisi tek
                                    // Row'a alindi (satir boslugu bir kez uygulanir)
                                    // ve 32dp'lik daha sik dokunma alanina gecti.
                                    val pinnable = pinScope != null && optionKey != null
                                    if (pinnable || opt.info != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (pinnable && optionKey != null) {
                                                CompactIconAction(
                                                    icon = if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
                                                    description = if (pinned) {
                                                        "Sabitlemeyi kaldır: ${opt.label}"
                                                    } else {
                                                        "Başa sabitle: ${opt.label}"
                                                    },
                                                    tint = if (pinned) Ui2.colors.accent else Ui2.colors.ink3,
                                                    onClick = {
                                                        val yeni = togglePin(pinnedKeys, optionKey)
                                                        pinnedKeys = yeni
                                                        pinScope?.let { pinStore?.save(it, yeni) }
                                                    },
                                                )
                                            }
                                            if (opt.info != null) {
                                                CompactIconAction(
                                                    icon = Icons.Default.Info,
                                                    description = "Model bilgisi: ${opt.label}",
                                                    tint = Ui2.colors.ink2,
                                                    onClick = { openInfo(opt) },
                                                )
                                            }
                                        }
                                    }
                                    if (opt.badge != null) StatusBadge(opt.badge)
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            "Seçili",
                                            tint = Ui2.colors.accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Liste satırının ucundaki sıkışık ikon eylemi. IconButton yerine var: o,
 * Material'in 48dp'lik dokunma alanını yerleşim ölçüsü olarak da alıyor ve iki
 * tane yan yana gelince satır başlığından ~100dp yiyor. Burada yerleşim 32dp,
 * ikon 19dp — satırın kendisi zaten seçim için tıklanabilir olduğundan bu
 * yoğunluk güvenli.
 */
@Composable
private fun CompactIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun SelectorInfoContent(
    modelName: String,
    info: SelectorInfo,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        Modifier.padding(bottom = Ui2Tokens.sheetBottom),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Model listesine dön", tint = Ui2.colors.ink)
                }
                Text(
                    modelName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ui2.colors.ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Ui2.colors.ink,
            )
        }
        if (info.bestFor.isNotBlank()) item { SelectorInfoRow("En uygun", info.bestFor) }
        if (info.profile.isNotBlank()) item { SelectorInfoRow("Profil", info.profile) }
        if (info.sourceUrl != null) {
            item {
                TextButton(onClick = { runCatching { uriHandler.openUri(info.sourceUrl) } }) {
                    Text("Hugging Face'te aç", color = Ui2.colors.accent)
                }
            }
        }
    }
}

@Composable
private fun SelectorInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ui2.colors.ink2)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
    }
}

// Bilgi sheet'i: başlık + etiket/değer satırları (oturum bilgisi, kullanım özeti).
// Değerler mono — kod/yol/kimlik her zaman mono kutuda (anayasa 4.3).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(bottom = Ui2Tokens.s12)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
    }
}
