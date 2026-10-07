package com.agent.bridge.ui2.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Sohbet mesajındaki görsel ekler. Kullanıcı tarafında ekler prompt'a
// "…\n\nEk dosyalar:\n- ad: yol" olarak gömülüyor (ConversationDelegate.send);
// asistan da aynı bloğu yazarak ekran görüntüsü paylaşabiliyor (masaustu skill).
// Burada o blok ayrıştırılır: görsel uzantılı ekler thumbnail olarak çizilir,
// görsel olmayan ekler düz metinde kalır.
data class MsgImage(val name: String, val path: String)

private val IMG_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private const val ATTACH_MARKER = "\n\nEk dosyalar:\n"

// Marker'ı kendi satırında yakala. Yıldızlar isteğe bağlı: asistan markdown
// yazdığı için "**Ek dosyalar:**" da gelebiliyor ve düz eşleşme bunu kaçırıp
// bloğu düz metin olarak bırakıyordu (canlı görüldü).
private val ATTACH_LINE = Regex("""(?m)^[ \t]*\*{0,2}Ek dosyalar:\*{0,2}[ \t]*$""")

// (gösterilecek metin, görsel ekler) — hiç görsel yoksa metin aynen döner.
// Rolden bağımsızdır: hem kullanıcı ekleri hem asistanın paylaştığı ekran
// görüntüleri aynı bloktan ayrıştırılır.
fun parseMessageImages(raw: String): Pair<String, List<MsgImage>> {
    val m = ATTACH_LINE.find(raw) ?: return raw to emptyList()
    val base = raw.substring(0, m.range.first).trimEnd()
    val block = raw.substring(m.range.last + 1).removePrefix("\n")
    val images = mutableListOf<MsgImage>()
    val otherLines = mutableListOf<String>()
    for (line in block.split('\n')) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val body = trimmed.removePrefix("- ")
        val sep = body.indexOf(": ")
        if (sep < 0) { otherLines.add(line); continue }
        val name = body.substring(0, sep)
        val path = body.substring(sep + 2)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in IMG_EXTS) images.add(MsgImage(name, path)) else otherLines.add(line)
    }
    if (images.isEmpty()) return raw to emptyList()
    val display = buildString {
        append(base)
        if (otherLines.isNotEmpty()) { append(ATTACH_MARKER); append(otherLines.joinToString("\n")) }
    }
    return display to images
}

private fun decodeSampled(bytes: ByteArray, reqPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val minSide = minOf(bounds.outWidth, bounds.outHeight)
    val sample = if (minSide > reqPx && reqPx > 0) maxOf(1, minSide / reqPx) else 1
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

// Bir görsel ekin thumbnail'i. Byte'lar köprüden (load) çekilir, örneklenerek çizilir.
@Composable
fun BridgeImageThumb(
    path: String,
    load: suspend (String) -> ByteArray?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 104,
) {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val bytes = load(path)
        if (bytes == null) { failed = true } else {
            val decoded = withContext(Dispatchers.Default) { decodeSampled(bytes, 320) }
            if (decoded == null) failed = true else bmp = decoded
        }
    }
    Box(
        modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(Ui2Tokens.cornerInline))
            .background(Ui2.colors.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Icon(Icons.Default.BrokenImage, null, tint = Ui2.colors.ink3, modifier = Modifier.size(24.dp))
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Ui2.colors.accent)
        }
    }
}

@Composable
fun ChatImageRow(
    images: List<MsgImage>,
    load: suspend (String) -> ByteArray?,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // Görsel şeridi yatay kayar; üstünde başlayan dokunuş çekmece jesti değildir.
        modifier = modifier.chatSwipeExclusion().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
    ) {
        images.forEach { img ->
            BridgeImageThumb(img.path, load, onClick = { onClick(img.path) })
        }
    }
}

// Tam ekran önizleme buradan KALDIRILDI: küçük resme dokunuş artık dosya
// yöneticisinin görüntüleyicisine (ImageViewer) gidiyor — zoom, kardeşler arası
// kaydırma ve paylaşım orada zaten var. "PC'de sakla" / "PC'den sil" tuşları
// ImageViewerState.attachment bayrağıyla o ekrana taşındı.
