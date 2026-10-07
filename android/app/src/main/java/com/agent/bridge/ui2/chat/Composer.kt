package com.agent.bridge.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.QueuedPrompt
import com.agent.bridge.ui2.components.StatusDot
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Composer üstü bağlam pill'i: durum ("çalışıyor · 2 dk"), bağlam yüzdesi, kısayol (Yenile/Kullanım).
// onClick verilirse pill clickable olur (kısayol pill'leri); verilmezse salt göstergedir.
// status verilirse onun renkli noktası çizilir; semantik duruma uymayan nötr bir nokta
// için (örn. "duruyor") dotColor ile doğrudan renk verilebilir — StatusKind genişletilmeden.
data class ComposerPillUi(
    val text: String,
    val status: StatusKind? = null,
    val icon: ImageVector? = null,
    val onClick: (() -> Unit)? = null,
    val dotColor: Color? = null,
)

// Composer üstü pill satırı: sohbet akışının ÜZERİNE biner (ChatRootScreen'de
// Alignment.BottomStart ile) — arkasında tam genişlik düz şerit yok, pill'lerin
// kaplamadığı alan şeffaftır; sohbet zemini görünür. Pill kendi surface zeminini taşır.
@Composable
fun ComposerPillRow(
    pills: List<ComposerPillUi>,
    modifier: Modifier = Modifier,
) {
    if (pills.isEmpty()) return
    // Dolu overlay pill: koyu temada beyaz zemin + menekşe içerik, açık temada
    // menekşe zemin + beyaz içerik (pillFill/pillOn tema token'ları).
    val contentColor = Ui2.colors.pillOn
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        // Ortalanır: sola yığılıp sağda ölü boşluk kalıyordu.
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8, Alignment.CenterHorizontally),
    ) {
        pills.forEach { pill ->
            Row(
                Modifier
                    .background(Ui2.colors.pillFill, Ui2Tokens.pill)
                    .let { if (pill.onClick != null) it.clickable(onClick = pill.onClick) else it }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Durum noktası anlam taşır (mavi/kehribar), dolu zeminde de korunur;
                // yalnız nötr gri nokta ("duruyor") pillOn'a döner ki zeminde görünsün.
                if (pill.status != null) StatusDot(pill.status)
                else if (pill.dotColor != null) {
                    Box(
                        Modifier
                            .size(Ui2Tokens.statusDot)
                            .background(contentColor, CircleShape)
                    )
                }
                if (pill.icon != null) {
                    Icon(
                        pill.icon,
                        contentDescription = pill.text,
                        tint = contentColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(pill.text, style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
        }
    }
}

/** ui3 kuyruk paneli de aynı özeti çiziyor — tek kopya kalsın diye internal. */
internal fun queuedPromptPreview(prompt: QueuedPrompt): String {
    val attachmentNote = when (prompt.attachments.size) {
        0 -> ""
        1 -> "📎 ${prompt.attachments.first().name}"
        else -> "📎 ${prompt.attachments.size} ek"
    }
    return listOf(prompt.text.ifBlank { "Yalnız ek" }, attachmentNote)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * Composer'ın üstündeki kalıcı prompt kuyruğu. Kapalıyken yalnız ilk prompt tek
 * satır görünür; dokununca bütün sıra açılır ve öğeler tek tek çıkarılabilir.
 */
@Composable
private fun PromptQueuePanel(
    prompts: List<QueuedPrompt>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    var expanded by remember(prompts.first().id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(Ui2.colors.surface2, shape)
            .border(1.dp, Ui2.colors.line, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Ui2Tokens.s12, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        ) {
            Text(
                "Sıra · ${prompts.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Ui2.colors.accent,
            )
            Text(
                queuedPromptPreview(prompts.first()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Ui2.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Sırayı daralt" else "Sırayı aç",
                tint = Ui2.colors.ink3,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            prompts.forEachIndexed { index, prompt ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Ui2Tokens.s12, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                ) {
                    Text(
                        "${index + 1}. ${queuedPromptPreview(prompt)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { onRemove(prompt.id) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Sıradan çıkar",
                            tint = Ui2.colors.ink3,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

// Composer (Mockup v1): ek butonu + pill giriş + gönder. Bağlam pill'leri artık
// ComposerPillRow ile sohbet üzerine bindirilir (burada çizilmez).
// Tur sürerken gönder butonu durdur'a döner (soluk zemin, kare ikon).
// Onay beklerken composer KİLİTLENMEZ — yönlendirme mesajı yazılabilir.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    placeholder: String = "Mesaj yaz —  /  komutlar",
    sendEnabled: Boolean = value.isNotBlank(),
    queuedPrompts: List<QueuedPrompt> = emptyList(),
    onRemoveQueued: ((String) -> Unit)? = null,
    // Tur sürerken canlı gönderim (yalnız OMP: native steer/follow_up RPC'si).
    // null ise yalnız istemci kuyruğu görünür — eski davranış.
    onSteer: (() -> Unit)? = null,
    onFollowUp: (() -> Unit)? = null,
    attachments: (@Composable RowScope.() -> Unit)? = null,
) {
    val line = Ui2.colors.line
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(horizontal = 14.dp, vertical = Ui2Tokens.s8),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        if (queuedPrompts.isNotEmpty() && onRemoveQueued != null) {
            // Kuyruk paneli metin alanıyla birebir hizalanır: ek düğmesinin
            // ardından başlar, gönder/durdur düğmelerinden önce biter.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onAttach != null) Spacer(Modifier.width(49.dp))
                PromptQueuePanel(
                    prompts = queuedPrompts,
                    onRemove = onRemoveQueued,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(if (running && sendEnabled) 98.dp else 49.dp))
            }
        }
        if (attachments != null) {
            // Yatay kaydırma şart: kaydırmasız Row'da 2. ve sonraki rozetler
            // sıfır genişlik alıp harf harf sarılıyor ve composer'ı ekrandan taşırıyordu.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
            ) { attachments() }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (onAttach != null) {
                IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, "Ek ekle", tint = Ui2.colors.ink3)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .background(Ui2.colors.surface, Ui2Tokens.field)
                    .border(1.dp, Ui2.colors.line, Ui2Tokens.field)
                    .padding(horizontal = Ui2Tokens.s16, vertical = 10.dp)
                    .heightIn(min = 20.dp, max = 120.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink3)
                }
                // Dış sözleşme String kalır; içeride TextFieldValue tutulur ki sol/sağ
                // ok tuşlarında imleci KENDİMİZ taşıyabilelim. Fiziksel klavyede
                // (pogo pin) ok tuşları cihaza göre field'ın iç işleyişine hiç
                // uğramayabiliyor (tablette gözlendi); preview aşamasında yakalayıp
                // seçimi elle kaydırmak her durumda deterministik çalışır.
                var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
                if (fieldValue.text != value) {
                    // Dış değişim (gönderim sonrası temizleme, slash komutu ekleme):
                    // metni al, imleci sona koy.
                    fieldValue = TextFieldValue(value, TextRange(value.length))
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        if (it.text != value) onValueChange(it.text)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                    cursorBrush = SolidColor(Ui2.colors.accent),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            val arrow = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                            if (!arrow) return@onPreviewKeyEvent false
                            // Teşhis: fiziksel klavye olayı uygulamaya ulaşıyor mu?
                            android.util.Log.d(
                                "AgKey",
                                "arrow ${event.key} type=${event.type} dev=${event.nativeKeyEvent.deviceId} sel=${fieldValue.selection}",
                            )
                            if (event.type == KeyEventType.KeyDown) {
                                val sel = fieldValue.selection
                                val pos = when {
                                    event.key == Key.DirectionLeft && !sel.collapsed -> sel.min
                                    event.key == Key.DirectionLeft -> (sel.start - 1).coerceAtLeast(0)
                                    !sel.collapsed -> sel.max
                                    else -> (sel.start + 1).coerceAtMost(fieldValue.text.length)
                                }
                                fieldValue = fieldValue.copy(selection = TextRange(pos))
                            }
                            true // up/down-stroke dahil tüket: odak kaçışı imkânsız
                        }
                        // Yukarı/aşağı: satır içi gezinmeyi field'a bırak; tüketilmeden
                        // kabarcıklanırsa (metin sınırı) odak kaçmasın diye yut.
                        .onKeyEvent { event ->
                            event.key == Key.DirectionUp || event.key == Key.DirectionDown
                        },
                )
            }
            // Gönder / Durdur: tur sürerken durdur (soluk), değilken vurgu.
            if (running) {
                // Tur sürerken metin yazıldıysa "kuyruğa ekle" butonu görünür; onSend
                // running iken prompt'u kuyruğa alır (RemoteViewModel.sendPrompt).
                // Yönlendir (steer): süren turu KESER, model o an yaptığını bırakıp
                // yeni yönergeye uyar. Yalnız bunu destekleyen sağlayıcıda görünür.
                if (sendEnabled && onSteer != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Ui2.colors.surface2, CircleShape)
                            .clickable(onClick = onSteer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Bolt, "Yönlendir (turu kes)", tint = Ui2.colors.accent, modifier = Modifier.size(20.dp))
                    }
                }
                if (sendEnabled) {
                    // Dokun: istemci kuyruğu (kalıcı, iptal edilebilir, her backend).
                    // Uzun bas: native follow_up — ajan mesajı kendi tutar, telefon
                    // bağlantısı kopsa bile tur bitince işler. İkisi de "sonra"
                    // anlamında olduğu için aynı düğmede toplandı.
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(Ui2.colors.accent, CircleShape)
                            .combinedClickable(
                                onClick = onSend,
                                onLongClick = onFollowUp ?: onSend,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Kuyruğa ekle", tint = Ui2.colors.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
                // Durdur butonu + tur aktif olduğu sürece etrafında dönen halka efekti.
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 2.dp,
                        color = Ui2.colors.accent,
                    )
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(Ui2.colors.surface2, CircleShape)
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Stop, "Durdur", tint = Ui2.colors.ink2, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                val bg = if (sendEnabled) Ui2.colors.accent else Ui2.colors.surface2
                val fg = if (sendEnabled) Ui2.colors.onAccent else Ui2.colors.ink3
                Box(
                    Modifier
                        .size(40.dp)
                        .background(bg, CircleShape)
                        .clickable(enabled = sendEnabled, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Gönder", tint = fg, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
