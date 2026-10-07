package com.agent.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Composer üstündeki ek chip'i: resimse küçük önizleme, her durumda ✕ ile kaldırma.
// Küçük resme dokunulunca [onPreview] çağrılır — mesajı göndermeden neyi
// eklediğini görmek için; gönderilmiş eklerdeki görüntüleyicinin aynısı açılır.
@Composable
internal fun AttachmentChip(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(36.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            val localUri = attachment.localUri
            if (attachment.isImage && localUri != null) {
                AttachmentThumb(localUri, onPreview)
            } else {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Kaldır", modifier = Modifier.size(14.dp))
            }
        }
    }
}

// Telefondaki kaynak URI'den örneklenmiş (küçültülmüş) bitmap üretir; Coil
// bağımlılığı eklememek için elle decode edilir.
@Composable
private fun AttachmentThumb(uriString: String, onPreview: (() -> Unit)? = null) {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uriString) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                val minSide = minOf(bounds.outWidth, bounds.outHeight)
                val sample = if (minSide > 0) maxOf(1, minSide / 64) else 1
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = if (onPreview != null) "Eki önizle" else null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(28.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .then(if (onPreview != null) Modifier.clickable(onClick = onPreview) else Modifier),
        )
    } else {
        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
    }
}
