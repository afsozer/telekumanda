package com.agent.bridge.ui2.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Mesaj düzeni (Mockup v1 onaylı): ajan çıktısı BALONSUZ sola yaslı;
// kullanıcı mesajı vurgu tonlu balon, sağa yaslı.

// Kullanıcı mesajı balonu. Genişlik en fazla %82; köşe 16/16/4/16.
// Sağ altta minik eylemler: kopyala + (varsa) mesaja dön + (varsa) buradan çatalla.
@Composable
fun MessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    time: String = "",
    onReturnToMessage: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp),
            color = Ui2.colors.accent.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, Ui2.colors.accent.copy(alpha = 0.28f)),
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .wrapContentWidth(Alignment.End),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ui2.colors.ink,
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (time.isNotBlank()) {
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = Ui2.colors.ink3,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    MiniAction(Icons.Default.ContentCopy, "Mesajı kopyala") { clipboard.setText(AnnotatedString(text)) }
                    if (onReturnToMessage != null) MiniAction(Icons.AutoMirrored.Filled.Undo, "Bu mesaja dön", onReturnToMessage)
                    if (onFork != null) MiniAction(Icons.AutoMirrored.Filled.CallSplit, "Buradan çatalla", onFork)
                }
            }
        }
    }
}

// Minik, sönük eylem tuşu (mesaj altı satırı için).
@Composable
private fun MiniAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(22.dp)) {
        Icon(icon, label, modifier = Modifier.size(13.dp), tint = Ui2.colors.ink3)
    }
}

// Ajan mesajı sonu eylem satırı: tümünü kopyala. Sola yaslı, sönük.
@Composable
fun AgentCopyAllRow(text: String, modifier: Modifier = Modifier, time: String = "") {
    val clipboard = LocalClipboardManager.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MiniAction(Icons.Default.ContentCopy, "Tümünü kopyala") { clipboard.setText(AnnotatedString(text)) }
        if (time.isNotBlank()) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = Ui2.colors.ink3,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

// Ajan turu bloğu: balonsuz, sola yaslı; içine metin + araç kartları +
// düşünce şeridi dizilir. İçerik slotu — 2b markdown'ı buraya bağlar.
@Composable
fun AgentBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth(0.96f),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        content = content,
    )
}

// Düz ajan metni (markdown'suz kısa parçalar / durum cümleleri için).
@Composable
fun AgentText(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (dim) Ui2.colors.ink2 else Ui2.colors.ink,
    )
}

// Düşünce şeridi: küçük, soluk, sol kenar çizgili (mockup .thought).
@Composable
fun ThoughtStrip(text: String, modifier: Modifier = Modifier) {
    val line = Ui2.colors.line
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(color = line, size = Size(2.dp.toPx(), size.height))
            }
            .padding(start = 10.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
    }
}
