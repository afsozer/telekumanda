package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.bridge.MarkwonText
import com.agent.bridge.PageLangState
import com.agent.bridge.PdfReadState
import com.agent.bridge.READER_FONT_MAX
import com.agent.bridge.READER_FONT_MIN
import com.agent.bridge.ReaderBlock
import com.agent.bridge.ReaderPage
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.splitReaderBlocks
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * PDF okuma modu: metin katmanı köprüde markdown'a çevrilip akış olarak çizilir.
 *
 * Kararlar:
 * - **Sayfa başına bir blok.** Belge tek dizgi olarak gelir ama sayfa sayfa
 *   çizilir: 150 sayfalık bir kitap ~225 KB markdown ve bunu tek TextView'a
 *   vermek ayrıştırma+yerleşimde saniyeler yiyor. Tembel listede yalnız görünen
 *   sayfa iş yapar.
 * - **Sayfa numarası korunur.** Metin akışa girince sayfa sınırı görsel olarak
 *   kaybolur; hukuki belgede "3. sayfada geçiyor" demek gerektiği için numara
 *   etiket olarak durur ve yanındaki tuş o sayfanın metnini panoya alır.
 * - **Seçim sayfa içinde.** Her sayfa ayrı TextView olduğundan seçim sayfa
 *   sınırını geçmez; alıntı almanın tipik birimi zaten bir sayfa.
 */
@Composable
fun PdfReader(
    state: PdfReadState,
    fontSize: Float,
    lang: Map<Int, PageLangState>,
    listState: LazyListState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            // Çıkarım sayfa sayısıyla büyüyor (ölçüm: 150 sayfa ~11 sn). Sessiz
            // bir çarkta bu "donmuş" gibi görünüyordu.
            Text("Metin çıkarılıyor…", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
        state.reason.isNotBlank() -> EmptyState(
            title = if (state.reason == "taranmis") "Bu belge taranmış" else "Okuma modu açılamadı",
            description = if (state.reason == "taranmis") {
                "Sayfalar görüntü olarak saklanmış, metin katmanı yok. Sayfa görünümünde okunabilir."
            } else state.reason,
            icon = Icons.Outlined.Description,
            modifier = modifier,
        )
        state.pages.isEmpty() -> EmptyState(
            title = "Metin bulunamadı",
            description = "Belgede metin katmanı var ama okunacak yazı çıkmadı.",
            icon = Icons.Outlined.Description,
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
        ) {
            if (state.truncated || state.tablesTruncated) {
                item(key = "uyari") { ReaderNotice(state) }
            }
            items(state.pages, key = { it.number }) { page ->
                ReaderPageBlock(page, fontSize, lang[page.number], actions)
            }
        }
    }
}

/**
 * Kesme uyarısı. Sessiz kırpma "belge bu kadarmış" diye okunur — çıkarıcının
 * tavana çarptığını söylemek zorundayız.
 */
@Composable
private fun ReaderNotice(state: PdfReadState) {
    val lines = buildList {
        if (state.truncated) add("Belge sayfa tavanını aştı; sonu okuma modunda yok.")
        if (state.tablesTruncated) add("Tablo tespiti bütçeyi aştı; ileri sayfalarda tablolar düz metin olarak geçiyor.")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerInline))
            .border(1.dp, Ui2.colors.line, RoundedCornerShape(Ui2Tokens.cornerInline))
            .padding(Ui2Tokens.s12),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
    ) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2) }
    }
}

@Composable
private fun ReaderPageBlock(
    page: ReaderPage,
    fontSize: Float,
    lang: PageLangState?,
    actions: RemoteViewModel,
) {
    val blocks = remember(page.markdown) { splitReaderBlocks(page.markdown) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        ReaderPageHeader(page, lang, actions)
        if (lang != null) LangFindings(lang)
        blocks.forEach { block ->
            when (block) {
                is ReaderBlock.Prose -> MarkwonText(
                    text = block.markdown,
                    color = Ui2.colors.ink,
                    onFileClick = { actions.openFile(it) },
                    textSizeSp = fontSize,
                )
                is ReaderBlock.Table -> WideTableCards(block, fontSize)
            }
        }
    }
}

/**
 * Dil kontrolü bulguları.
 *
 * Bulgu bir ÖNERİDİR: metne hiçbir şey uygulanmaz, "kabul et" tuşu yoktur.
 * Hukuki evrakta yazım hatası belgenin parçasıdır — düzeltilmiş bir kopya
 * üretmek belgeyi bozar. Ders kitabında da kararı okuyan verir.
 */
@Composable
private fun LangFindings(lang: PageLangState) {
    when {
        lang.loading -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Ui2.colors.ink3)
            Text("Dil kontrolü sürüyor…", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink2)
        }
        lang.reason.isNotBlank() -> Text(
            "Dil kontrolü yapılamadı: ${lang.reason}",
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink3,
        )
        lang.done && lang.findings.isEmpty() -> Text(
            "Dil kontrolü: bulgu yok.",
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink3,
        )
        else -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
        ) {
            lang.findings.forEach { finding ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerInline))
                        .border(1.dp, Ui2.colors.line, RoundedCornerShape(Ui2Tokens.cornerInline))
                        .padding(Ui2Tokens.s12),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                ) {
                    Text(langLabel(finding.tur), style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    Text(finding.alinti, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
                    Text(
                        "→ ${finding.oneri}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ui2.colors.ink,
                    )
                    if (finding.not.isNotBlank()) {
                        Text(finding.not, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    }
                }
            }
            // Öneri olduğu tek tek kartlarda değil bir kez söyleniyor; kart
            // başına tekrarlamak listeyi okunmaz yapardı.
            Text(
                "Bunlar öneridir; belgeye hiçbir değişiklik uygulanmaz.",
                style = MaterialTheme.typography.labelSmall,
                color = Ui2.colors.ink3,
            )
        }
    }
}

private fun langLabel(tur: String): String = when (tur) {
    "yazim" -> "Yazım"
    "noktalama" -> "Noktalama"
    "anlatim" -> "Anlatım"
    "tutarsizlik" -> "Tutarsızlık"
    else -> tur
}

/** Sayfa şeridi: numara + dil kontrolü + o sayfanın metnini panoya alan tuş. */
@Composable
private fun ReaderPageHeader(page: ReaderPage, lang: PageLangState?, actions: RemoteViewModel) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(page.markdown) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(2000); copied = false }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        Text(
            "s. ${page.number}",
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink3,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Ui2.colors.line))
        // Dil kontrolü VARSAYILAN OLARAK KAPALI ve sayfa başına: her sayfa
        // modele gidiyor (~14 sn, ~28 bin token). Kendiliğinden çalışsaydı
        // kitabı okumak imkânsızlaşırdı. Bir kez çalışan sayfada tuş kaybolur.
        if (lang == null) {
            Row(
                Modifier
                    .background(Ui2.colors.surface, Ui2Tokens.pill)
                    .clickable { actions.checkPageLanguage(page.number, page.markdown) }
                    .padding(horizontal = Ui2Tokens.s8, vertical = Ui2Tokens.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dil kontrolü", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink2)
            }
        }
        Row(
            Modifier
                .background(Ui2.colors.surface, Ui2Tokens.pill)
                .clickable {
                    clipboard.setText(AnnotatedString(page.markdown))
                    copied = true
                }
                .padding(horizontal = Ui2Tokens.s8, vertical = Ui2Tokens.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
        ) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "Sayfayı kopyala",
                tint = Ui2.colors.ink2,
                modifier = Modifier.size(12.dp),
            )
            Text(
                if (copied) "Kopyalandı" else "Kopyala",
                style = MaterialTheme.typography.labelSmall,
                color = Ui2.colors.ink2,
            )
        }
    }
}

/**
 * Geniş tablo (4+ sütun) satır satır kart olarak çizilir.
 *
 * Markwon dar tabloyu düzgün çiziyor ama 6 sütunlu bir resmî formu telefon
 * genişliğine sıkıştırınca hücre başına iki karakter kalıyor. Kartta her satır
 * "başlık: değer" listesi olur; boş hücreler atlanır (resmî formların çoğu
 * hücresi boş).
 */
@Composable
private fun WideTableCards(table: ReaderBlock.Table, fontSize: Float) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        table.rows.forEach { row ->
            val cells = table.header.indices.mapNotNull { i ->
                val value = row.getOrNull(i)?.replace("<br>", " ")?.trim().orEmpty()
                if (value.isEmpty()) null else (table.header[i].ifBlank { "—" } to value)
            }
            if (cells.isEmpty()) return@forEach
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerInline))
                    .border(1.dp, Ui2.colors.line, RoundedCornerShape(Ui2Tokens.cornerInline))
                    .padding(Ui2Tokens.s12),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            ) {
                cells.forEach { (label, value) ->
                    Text(label, style = labelStyle, color = Ui2.colors.ink3)
                    Text(value, style = valueStyle, color = Ui2.colors.ink)
                }
            }
        }
        // Başlık satırının kendisi de veri taşıyabilir (köprü ilk satırı başlık
        // sayar; resmî formda o satır çoğu zaman gerçek başlık ama her zaman
        // değil). Hiç veri satırı yoksa başlığı kaybetmemek için basılır.
        if (table.rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerInline))
                    .padding(Ui2Tokens.s12),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            ) {
                table.header.filter { it.isNotBlank() }.forEach {
                    Text(it, style = valueStyle, color = Ui2.colors.ink)
                }
            }
        }
    }
    Spacer(Modifier.height(Ui2Tokens.s4))
}

/** Yazı boyutu tuşları: okuma modunun tek görsel ayarı. */
@Composable
internal fun ReaderFontButtons(fontSize: Float, onChange: (Float) -> Unit) {
    // BURAYA `horizontalScroll` KOYMA. Bu satır, kendisi yatay kaydırılan bir
    // Row'un (PdfActions) içinde çiziliyor; iç içe iki yatay kaydırıcı
    // Compose'un ölçüm sözleşmesini bozuyor ve uygulama ÇÖKÜYOR:
    // "Horizontally scrollable component was measured with an infinity maximum
    // width constraints". 11.58'de canlıda yaşandı — okuma moduna basınca.
    Row(
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FontStepButton("A−", enabled = fontSize > READER_FONT_MIN) { onChange(fontSize - 1f) }
        FontStepButton("A+", enabled = fontSize < READER_FONT_MAX) { onChange(fontSize + 1f) }
    }
}

@Composable
private fun FontStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(34.dp)
            .width(40.dp)
            .background(Ui2.colors.surface, Ui2Tokens.pill)
            .border(1.dp, Ui2.colors.line, Ui2Tokens.pill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) Ui2.colors.ink else Ui2.colors.ink3,
        )
    }
}
