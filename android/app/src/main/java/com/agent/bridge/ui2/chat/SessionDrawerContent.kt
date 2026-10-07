package com.agent.bridge.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agent.bridge.BackendDiskSessionUi
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.backendDiskSessions
import com.agent.bridge.backendSession
import com.agent.bridge.orderedVisibleBackends
import com.agent.bridge.resumeBackendDiskSession
import com.agent.bridge.userFacingProviderMonogram
import com.agent.bridge.sortedBySessionPriority
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ProviderMark
import com.agent.bridge.ui2.components.SearchField
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Oturum çekmecesinin İÇERİĞİ — kapsayıcıdan bağımsız.
 *
 * Bu gövde `ChatRootScreen`in `drawerContent` lambdasının içindeydi ve oraya
 * kilitliydi. ui3'ün Oturumlar sheet'i ilk sürümünde yalnız AÇIK SEKMELERİ
 * listeliyordu; kullanıcı haklı olarak "ui2'nin davranışı bu değildi" dedi —
 * çekmece diskteki TÜM oturumları, sabitli/arşiv segmentlerini ve içerik
 * aramasını taşıyor.
 *
 * Yeniden yazmak yerine çıkarıldı: buradaki davranışların çoğu ölçülerek
 * bulunmuş kenar durumları (birleşik listede pin önceliği, içerik araması
 * boşken segment dalına düşme, cowork kapsam yolu eşleşmesi). İkinci bir kopya
 * bunları sessizce kaybederdi.
 *
 * Kapsayıcıya bağlı şeyler parametreye çıktı: kapatma, yeni oturum ve uzun
 * basış menüsü. Yükleme efekti çağıranda kalıyor; ui2 çekmece kapalıyken
 * de besteleniyor ve efekt `drawerState.isOpen`e bağlı, ui3'te ise içerik yalnız
 * sheet açıkken var.
 */
@Composable
fun ColumnScope.SessionDrawerContent(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onKapat: () -> Unit,
    onNewSession: () -> Unit,
    onSessionLongClick: (backend: String, sessionId: String) -> Unit,
    onSettings: (() -> Unit)? = null,
    // Listenin ilk öğesi. LazyColumn'un İÇİNDE çiziliyor ki oturumlarla birlikte
    // kaysın — üstte sabit dururken uzun bir bölüm listeyi bir şeride indiriyordu
    // (kullanıcı 21.08.2026). Liste hiç çizilmeyen dallarda (boş/yükleniyor)
    // doğrudan yukarıda çiziliyor, orada kaydırılacak bir şey zaten yok.
) {
        // Tek görünüm: Oturumlar tuşu hangi backend açık olursa olsun tüm
        // sağlayıcıların kayıtlarını gösterir. Geri ok / "Bu repo" alt kipi yok.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tüm oturumlar",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = Ui2.colors.ink,
            )
            if (onSettings != null) {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.testTag("oturum_ayarlar"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Ayarlar",
                        tint = Ui2.colors.ink3,
                    )
                }
            }
        }
        SearchField(
            value = uiState.drawerSearchQuery,
            onValueChange = {
                actions.updateDrawerSearchQuery(it)
                // Aynı sorguyla köprü tarafında mesaj içeriğinde de ara
                // (350ms debounce GlobalSearchDelegate'te).
                if (!uiState.liteEdition) actions.updateGlobalSearchQuery(it)
            }
        )
        var selectedSegmentIndex by remember { mutableStateOf(0) }
        // Lite'ta tek, yalın oturum listesi var. Tümü/Sabitli/Arşiv segmentleri
        // hem gereksiz bir karar katmanıydı hem de bu sürümün "kendi oturumları"
        // akışını kalabalıklaştırıyordu. Tam sürümün filtreleri korunur.
        val currentSegmentIndex = if (uiState.liteEdition) 0 else if (uiState.drawerShowArchived) 2 else {
            if (selectedSegmentIndex == 2) 0 else selectedSegmentIndex
        }
        if (!uiState.liteEdition) {
            SegmentedTabs(
                options = listOf("Tümü", "Sabitli", "Arşiv"),
                selectedIndex = currentSegmentIndex,
                onSelect = { idx ->
                    if (idx == 2) {
                        if (!uiState.drawerShowArchived) {
                            actions.toggleDrawerArchived()
                        }
                    } else {
                        if (uiState.drawerShowArchived) {
                            actions.toggleDrawerArchived()
                        }
                    }
                    selectedSegmentIndex = idx
                }
            )
        }

        // Tüm görünür backend'lerin oturumları (cowork dahil) tek listede.
        val flatBackends = orderedVisibleBackends(uiState).map { it.id }
        val rawTagged: List<Pair<String, BackendDiskSessionUi>> =
            flatBackends.flatMap { b -> uiState.backendDiskSessions(b).map { b to it } }
        // Sabitli oturumlar her zaman en üstte (birleşik listede mtime sıralaması
        // pin önceliğini eziyordu); pin içinde ve pinsizler kendi aralarında mtime desc.
        val taggedSessions = rawTagged.sortedBySessionPriority(
            pinned = { it.second.pinned },
            mtime = { it.second.mtime },
        )
        // Arama sunucu tarafı reload'a bağlıydı ama yalnız aktif claude/codex
        // backend'ini yeniliyordu; birleşik (global) listede diğer backend'ler hiç
        // filtrelenmiyor, dolayısıyla arama "hiçbir şey yapmıyor" görünüyordu.
        // Metni burada istemci tarafında da süzerek tüm backend'ler için çalıştır.
        val query = uiState.drawerSearchQuery.trim()
        val segmentFiltered = when (currentSegmentIndex) {
            1 -> taggedSessions.filter { it.second.pinned }
            else -> taggedSessions
        }

        val filteredSessions = if (query.isEmpty()) segmentFiltered else segmentFiltered.filter {
            val s = it.second
            s.title.contains(query, ignoreCase = true) ||
                s.lastText.contains(query, ignoreCase = true) ||
                s.cwd.contains(query, ignoreCase = true)
        }
        val diskLoading = flatBackends.any { uiState.backendSession(it).diskLoading }

        // DİKKAT: sorgu varken liste dalına GİRİLMELİ — içerik (mesaj) eşleşmeleri
        // LazyColumn'un içinde listeleniyor. Eskiden başlık süzgeci 0 oturum bulunca
        // "Eşleşen oturum yok" dalı seçiliyor ve köprüden gelen mesaj eşleşmeleri
        // hiç çizilmiyordu: başlıkta geçmeyen ama içerikte geçen sorgular ("outer
        // wilds" vakası) sonuç geldiği halde boş ekran gösteriyordu.
        val contentSearchActive = !uiState.liteEdition && query.length >= 2
        if (diskLoading && filteredSessions.isEmpty() && !contentSearchActive) {
            Box(modifier = Modifier.weight(1f)) {
                LoadingSkeleton(rows = 4)
            }
        } else if (filteredSessions.isEmpty() && query.isNotEmpty() && !contentSearchActive) {
            Box(modifier = Modifier.weight(1f)) {
                EmptyState(title = "Eşleşen oturum yok")
            }
        } else if (filteredSessions.isEmpty() && query.isEmpty()) {
            Box(modifier = Modifier.weight(1f)) {
                EmptyState(
                    title = "Kayıtlı oturum yok",
                    actionLabel = "Yeni oturum",
                    onAction = {
                        onKapat()
                        // Yeni oturum daima YENİ sekmede açılır: önce boş+aktif sekme
                        // ekle, sonra yeni-oturum ekranına git. Böylece başlatınca aktif
                        // Claude/… sekmesini ezmeden ikinci sekme olarak açılır.
                        actions.tabsDelegate.newTab()
                        onNewSession()
                    }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)
            ) {
                items(filteredSessions) { (sessionBackend, s) ->
                    val folder = lastFolder(s.cwd)
                    val textSnippet = truncateText(s.lastText)
                    // Köprü aynı sohbetin devam kopyalarını tek satırda topluyor
                    // (claude-app.mjs collapseForks). Kaç kopya olduğu yazılmazsa
                    // liste sessizce kısalır ve "oturumum kayboldu" hissi verir.
                    val forkNote = if (s.forks > 1) "${s.forks} kopya" else ""
                    val detailText = listOf(folder, forkNote, textSnippet)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · ")
                    // Şu an açık olan oturum mavi (running token) tonla vurgulanır;
                    // aktif tint cowork altın tonunun önüne geçer (tek bakışta "buradayım").
                    val isActiveSession = uiState.backendSession(sessionBackend).sessionId == s.id
                    ListRow(
                        title = s.title.ifBlank { s.id.take(8) },
                        // Cowork kayıtları hafif altın tonla ayrışır: çalışma alanına
                        // bağlı oturum olduklarını tek bakışta belli eder.
                        modifier = when {
                            isActiveSession -> Modifier.background(
                                Ui2.colors.running.copy(alpha = 0.12f),
                                RoundedCornerShape(Ui2Tokens.cornerInline)
                            )
                            sessionBackend == "cowork" -> Modifier.background(
                                CoworkGold.copy(alpha = 0.10f),
                                RoundedCornerShape(Ui2Tokens.cornerInline)
                            )
                            else -> Modifier
                        },
                        detail = detailText.ifBlank { null },
                        leading = { ProviderMark(uiState.userFacingProviderMonogram(sessionBackend)) },
                        // Sabitli oturumlar başlık başında pin ikonuyla işaretlenir.
                        titleIcon = if (s.pinned) {
                            { Icon(Icons.Filled.PushPin, contentDescription = "Sabitli", tint = Ui2.colors.ink3, modifier = Modifier.size(14.dp)) }
                        } else null,
                        onClick = {
                            onKapat()
                            actions.resumeBackendDiskSession(sessionBackend, s.id)
                        },
                        onLongClick = {
                            onSessionLongClick(sessionBackend, s.id)
                        }
                    )
                }
                // İçerik araması: başlık/son-mesaj süzgeci mesaj GEÇMİŞİNİ görmez;
                // aynı sorgu köprünün global aramasında (mesaj kapsamı) da koşturulur
                // ve eşleşen mesajlar ayrı bölümde listelenir. Dokununca ilgili
                // oturum açılır ve sohbet-içi arama eşleşen mesaja gider.
                if (contentSearchActive) {
                    val messageHits = uiState.search.hits
                        .filter { it.type == "message" }
                        .take(25)
                    if (uiState.search.loading) {
                        item(key = "__content_loading__") {
                            Text(
                                "İçerikte aranıyor…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ui2.colors.ink3,
                                modifier = Modifier.padding(vertical = Ui2Tokens.s4),
                            )
                        }
                    }
                    // İki kapsam da boşsa boş-durum LISTENIN İÇİNDE gösterilir
                    // (arama sürerken değil — "aranıyor" satırı zaten görünür).
                    if (filteredSessions.isEmpty() && messageHits.isEmpty() && !uiState.search.loading) {
                        item(key = "__content_empty__") {
                            EmptyState(title = "Eşleşen oturum yok")
                        }
                    }
                    if (messageHits.isNotEmpty()) {
                        item(key = "__content_header__") {
                            Text(
                                "İçerikte eşleşenler",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ui2.colors.ink3,
                                modifier = Modifier.padding(top = Ui2Tokens.s8, bottom = Ui2Tokens.s4),
                            )
                        }
                        items(messageHits, key = { "hit_${it.id}" }) { hit ->
                            ListRow(
                                title = hit.title.ifBlank { hit.projectName }.ifBlank { hit.sessionId.take(8) },
                                detail = hit.snippet.ifBlank { null },
                                leading = { ProviderMark(uiState.userFacingProviderMonogram(hit.backend)) },
                                onClick = {
                                    onKapat()
                                    actions.openGlobalSearchMessageHit(hit, query)
                                },
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                onKapat()
                // Çekmecedeki "＋ Yeni oturum" da yeni sekmede açar (mevcut sekmeyi ezmez).
                actions.tabsDelegate.newTab()
                onNewSession()
            },
            modifier = Modifier.fillMaxWidth().padding(top = Ui2Tokens.s8),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ui2.colors.accent,
                contentColor = Ui2.colors.onAccent,
            )
        ) {
            Text("＋ Yeni oturum")
        }
}
