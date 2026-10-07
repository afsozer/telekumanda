package com.agent.bridge.ui2.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Geniş ekranda içeriği sınırlar ve ORTALAR.
 *
 * Neden gerekli: tablet yatayda (Tab S10+ ~1400dp) tek sütunlu kart listesi
 * ekranı baştan sona geçiyor, ikonla metin sol uçta minik bir şeride sıkışıyor
 * ve sağda yüzlerce dp boş kalıyordu. Telefon dikeyde hiçbir şey değişmez:
 * [Ui2Tokens.contentMaxWidth] zaten ekran genişliğinden büyük.
 *
 * Başlıkla listeyi AYRI AYRI sarma — ikisini birlikte sar. Yalnız listeyi
 * sarınca ScreenHeader'ın sağdaki aksiyonu (yenile ikonu gibi) içerikten kopup
 * ekranın uzak köşesinde kalıyor.
 */
@Composable
fun ContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Ui2Tokens.contentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth)) { content() }
    }
}

/**
 * Hücreleri genişliğe göre 1..N sütuna dizer.
 *
 * FlowRow KULLANILMIYOR: Compose BOM 2024.06'da (foundation 1.6.8) hâlâ
 * `@ExperimentalLayoutApi` ve satır başına eşit genişlik vermiyor. Buradaki
 * chunk'lı Row deterministik: her hücre `weight(1f)` ile eşit pay alıyor, son
 * satır eksik kalırsa boş paylarla dolduruluyor — yoksa tek kalan hücre satırı
 * kaplayıp ızgara hizası bozuluyor.
 *
 * Hücre listesi `@Composable` lambda listesi olarak alınır; çağıran taraf
 * mevcut `ListRow(...)` gövdelerini süslü parantez içine almaktan başka bir şey
 * yapmaz, içerik tipine bağlı bir veri modeline çevirmek gerekmez.
 */
@Composable
fun AdaptiveCells(
    cells: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = Ui2Tokens.adaptiveMinCell,
    gap: Dp = Ui2Tokens.s8,
) {
    if (cells.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth / minCellWidth).toInt()).coerceIn(1, cells.size)
        if (columns <= 1) {
            // Tek sütunda Row/Spacer sarmalayıcısına gerek yok: telefon dikeyde
            // yerleşim eskisiyle BIREBIR aynı kalsın.
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                cells.forEach { it() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                cells.chunked(columns).forEach { rowCells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowCells.forEach { cell ->
                            Box(Modifier.weight(1f)) { cell() }
                        }
                        repeat(columns - rowCells.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * LazyColumn içindeki DİNAMİK listeler için AdaptiveCells'in karşılığı.
 *
 * AdaptiveCells sabit hücre listesi alır ve tek item olarak çizilir; yüzlerce
 * kayıtlı bir listeyi ona vermek lazy geri dönüşümü öldürür. Burada liste
 * satırlara bölünür (chunk) ve her SATIR bir lazy item olur: kaydırma
 * performansı korunur, geniş ekranda yan yana `columns` hücre çizilir.
 *
 * columns=1 çağrısı düz items(...) ile BİREBİR aynı yerleşimi verir (telefon
 * dikeyde hiçbir şey değişmez); anahtar satırın ilk elemanından türetilir.
 * Satırdaki hücreler doğal yüksekliğinde, üstten hizalı — AdaptiveCells ile
 * aynı davranış.
 */
fun <T> LazyListScope.adaptiveItems(
    items: List<T>,
    columns: Int,
    key: ((T) -> Any)? = null,
    gap: Dp = Ui2Tokens.s12,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val cols = columns.coerceAtLeast(1)
    if (cols == 1) {
        items(items.size, key = if (key != null) ({ i: Int -> key(items[i]) }) else null) { i ->
            itemContent(items[i])
        }
        return
    }
    val rows = items.chunked(cols)
    items(rows.size, key = if (key != null) ({ i: Int -> key(rows[i].first()) }) else null) { i ->
        val rowItems = rows[i]
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            rowItems.forEach { item ->
                Box(Modifier.weight(1f)) { itemContent(item) }
            }
            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Verilen genişliğe sığan sütun sayısı — AdaptiveCells ile aynı eşik. */
fun adaptiveColumnCount(available: Dp, minCellWidth: Dp = Ui2Tokens.adaptiveMinCell): Int =
    (available / minCellWidth).toInt().coerceAtLeast(1)
