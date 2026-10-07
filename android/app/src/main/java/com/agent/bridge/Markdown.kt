package com.agent.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.OpenableColumns
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import com.agent.bridge.ui2.chat.chatSwipeExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.text.method.ArrowKeyMovementMethod
import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.MotionEvent
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.lines()
        var inCode = false
        val code = mutableListOf<String>()
        lines.forEach { raw ->
            if (raw.trim().startsWith("```")) {
                if (inCode) {
                    CodeBlock(code.joinToString("\n"))
                    code.clear()
                }
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                code += raw
            } else {
                MarkdownLine(raw)
            }
        }
        if (code.isNotEmpty()) CodeBlock(code.joinToString("\n"))
    }
}

@Composable
internal fun MarkdownLine(line: String) {
    val trimmed = line.trim()
    val style = when {
        trimmed.startsWith("### ") -> MaterialTheme.typography.titleSmall
        trimmed.startsWith("## ") -> MaterialTheme.typography.titleMedium
        trimmed.startsWith("# ") -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.bodySmall
    }
    val content = trimmed.removePrefix("### ").removePrefix("## ").removePrefix("# ")
    val bullet = content.startsWith("- ") || content.startsWith("* ")
    Text(
        text = inlineMarkdown(if (bullet) "• ${content.drop(2)}" else content),
        style = style,
    )
}

@Composable
internal fun CodeBlock(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "CODE",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(12.dp),
                        tint = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .chatSwipeExclusion()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

internal fun inlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else append(text[i++])
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else append(text[i++])
            }
            else -> append(text[i++])
        }
    }
}

internal object SelectableLinkMovementMethod : ArrowKeyMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            var x = event.x.toInt()
            var y = event.y.toInt()
            x -= widget.totalPaddingLeft
            y -= widget.totalPaddingTop
            x += widget.scrollX
            y += widget.scrollY

            val layout = widget.layout
            if (layout != null) {
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())
                val link = buffer.getSpans(off, off, ClickableSpan::class.java)
                if (action == MotionEvent.ACTION_UP) {
                    // Teşhis: dokunuş span'e ulaşıyor mu? (okuyucu modunda linkler
                    // sessizce ölü kalmıştı; SelectionContainer olayı yutuyordu)
                    if (com.agent.bridge.BuildConfig.DEBUG) android.util.Log.d("AgMdLink", "up off=$off links=${link.size}")
                }
                if (link.isNotEmpty()) {
                    if (action == MotionEvent.ACTION_UP) {
                        link[0].onClick(widget)
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}

/**
 * [textSizeSp]: okuma modu yazı boyutunu ayarlanabilir yapmak için var. Sohbette
 * varsayılan 13.5sp; kitap okurken kullanıcı büyütebiliyor. Boyut, metin
 * değişmediğinde de uygulanmalı — aşağıdaki "yalnız metin değişince çiz"
 * koruması SADECE markdown çizimini kapsar.
 *
 * [yazTipi]: gövde metninin typeface'i. Markdown bir `TextView` içinde
 * çiziliyor, yani Compose'un `fontFamily`si buraya ULAŞMIYOR — ui3 kendi
 * yüzünü (Mackinac) kullanacaksa elden vermek zorunda. null (ui2) sistem
 * fontunda kalır. Kod parçaları Markwon'un kendi mono span'i olduğu için
 * bundan etkilenmez.
 */
@Composable
internal fun MarkwonText(
    text: String,
    color: Color,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 13.5f,
    yazTipi: android.graphics.Typeface? = null,
    // Satır arası çarpanı. Varsayılan ui2'nin değeri; serif yüzler daha çok
    // nefes istediği için ui3 bunu büyütüyor.
    satirCarpani: Float = 1.05f,
) {
    val context = LocalContext.current
    val markwon = remember(onFileClick) { buildMarkwon(context, onFileClick) }
    // Cache the last rendered text so we don't re-inflate the same Spannable
    // on every recomposition (e.g. typing in the input field). Re-rendering a
    // block with a TablePlugin causes the native table layout to remeasure,
    // producing visible jitter.
    val lastText = remember { mutableStateOf("") }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 13.5f
                setLineSpacing(0f, 1.05f)
                setTextIsSelectable(true)
                movementMethod = SelectableLinkMovementMethod
            }
        },
        update = { tv ->
            tv.setTextColor(color.toArgb())
            tv.textSize = textSizeSp
            if (yazTipi != null && tv.typeface !== yazTipi) tv.typeface = yazTipi
            tv.setLineSpacing(0f, satirCarpani)
            tv.setTextIsSelectable(true)
            tv.linksClickable = true
            tv.isClickable = true
            tv.movementMethod = SelectableLinkMovementMethod
            // Only re-render markdown when the text actually changed.
            // When the same text is re-rendered, Markwon's TablePlugin
            // recalculates column widths, causing jitter on every
            // recomposition cycle (e.g. during typing).
            if (text != lastText.value) {
                lastText.value = text
                markwon.setMarkdown(tv, linkFileReferences(text))
                tv.movementMethod = SelectableLinkMovementMethod
            }
        },
    )
}

// Sohbet mesajı parçası: düz markdown ya da çitli kod bloğu. Kod blokları
// ayrı çizilir ki her birinin altına kendi "Kopyala" tuşu konabilsin (uzun
// prompt/kod bloklarını tüm mesajı kopyalamadan almak için).
internal sealed interface MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment
    data class Code(val language: String, val code: String) : MarkdownSegment
}

// GFM tablo tespiti: '|' içeren bir satırın hemen ardından ayraç satırı (yalnız
// | - : ve boşluk; en az bir '-' ve bir '|'). Kod çitleri buraya gelmez —
// segmentler önce ayrılır. Çekmece jesti muafiyeti bu tespite bakar.
internal fun containsMarkdownTable(text: String): Boolean {
    val lines = text.lines()
    for (i in 0 until lines.size - 1) {
        if (!lines[i].contains('|')) continue
        val sep = lines[i + 1].trim()
        if (sep.length >= 2 && sep.contains('-') && sep.contains('|') && sep.all { it in "|-: \t" }) return true
    }
    return false
}

// Çit (``` veya ~~~) satır başında olabilir; kapanış açılışla AYNI karakter ve
// en az o uzunlukta olmalı (iç içe örneklerde erken kapanmayı önler). Akış
// sürerken kapanmamış blok da kod sayılır: tuş anında görünür, metin sıçramaz.
internal fun splitMarkdownSegments(markdown: String): List<MarkdownSegment> {
    val out = mutableListOf<MarkdownSegment>()
    val buffer = StringBuilder()
    var fenceChar = ' '
    var fenceLen = 0
    var language = ""
    val code = StringBuilder()
    fun flushProse() {
        if (buffer.isNotBlank()) out.add(MarkdownSegment.Prose(buffer.toString().trim('\n')))
        buffer.clear()
    }
    for (line in markdown.lines()) {
        val trimmed = line.trimStart()
        val ch = trimmed.firstOrNull()
        val run = if (ch == '`' || ch == '~') trimmed.takeWhile { it == ch }.length else 0
        if (fenceLen == 0) {
            if (run >= 3) {
                flushProse()
                fenceChar = ch!!
                fenceLen = run
                language = trimmed.drop(run).trim()
                code.clear()
            } else {
                buffer.append(line).append('\n')
            }
        } else {
            // Kapanış çiti: aynı karakter, yeterli uzunluk ve arkasında metin yok.
            if (ch == fenceChar && run >= fenceLen && trimmed.drop(run).isBlank()) {
                out.add(MarkdownSegment.Code(language, code.toString().trimEnd('\n')))
                fenceLen = 0
            } else {
                code.append(line).append('\n')
            }
        }
    }
    if (fenceLen > 0) out.add(MarkdownSegment.Code(language, code.toString().trimEnd('\n')))
    flushProse()
    return out
}

// Ajan mesajı: düz parçalar Markwon ile, kod blokları kendi kartında + altında
// "Kopyala" tuşuyla çizilir. Tek parça düz metinse davranış MarkwonText ile aynı.
@Composable
internal fun MarkdownMessage(
    text: String,
    color: Color,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { splitMarkdownSegments(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                // Tablo içeren prose bloğu çekmece jestinden muaf: tabloyu yana
                // kaydırma niyeti çekmeceyi açıyordu. Tablo TextView span'i olarak
                // çizildiğinden yalnız tablonun sınırı alınamıyor; bloğun tamamı
                // muaf tutulur (tablo genelde bloğu zaten domine eder).
                is MarkdownSegment.Prose -> MarkwonText(
                    segment.text, color, onFileClick,
                    modifier = if (containsMarkdownTable(segment.text)) Modifier.chatSwipeExclusion() else Modifier,
                )
                is MarkdownSegment.Code -> CodeBlockCard(segment)
            }
        }
    }
}

@Composable
private fun CodeBlockCard(segment: MarkdownSegment.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(segment.code) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Kod yatay kaydırılır: uzun satırlar sarılmaz (girinti okunur kalsın).
        SelectionContainer {
            Text(
                segment.code,
                modifier = Modifier.fillMaxWidth().chatSwipeExclusion().horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                segment.language.ifBlank { "kod" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        clipboard.setText(AnnotatedString(segment.code))
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Kodu kopyala",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (copied) "Kopyalandı" else "Kopyala",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val FILE_EXTENSIONS =
    "kt|java|mjs|js|json|md|markdown|txt|xml|kts|gradle|properties|py|cmd|ps1|bat|yml|yaml|" +
        "html|css|scss|png|jpg|jpeg|webp|svg|pdf|docx|doc|xlsx|xls|pptx|ppt|odt|ods|csv|zip|apk|log"

// İki dal, çünkü boşuklu dosya adları (ör. "örnek çalışma\not defteri.txt") desteklenmeli
// ama boşluk her yerde serbest bırakılınca regex cümledeki ÖNCEKİ kelimeleri de yutuyordu:
// "bu rapor.docx" → link metni "bu rapor.docx", tıklanınca köprüye uydurma yol gidiyordu.
//   1) Köklü yol (sürücü harfi / eğik çizgi / ./.. ile başlar): boşluk serbest —
//      başlangıç bir ayraca çakılı olduğu için önceki kelimeyi yutamaz.
//   2) Köksüz ad: İLK bileşende boşluk yok (kelime yutmayı bu keser); sonraki
//      bileşenlerde serbest ("docs/örnek çalışma/rapor.docx" çalışır).
private val FileReferenceRegex = Regex(
    """(?<![\w:/\]\)])(""" +
        """(?:[A-Za-z]:[\\/]|[\\/]|\.{1,2}[\\/])(?:[A-Za-z0-9_. -]+[\\/])*[A-Za-z0-9_. -]+\.(?:$FILE_EXTENSIONS)""" +
        """|[A-Za-z0-9_.-]+(?:[\\/][A-Za-z0-9_. -]+)*\.(?:$FILE_EXTENSIONS)""" +
        """)(?:[:#](\d+))?""",
    setOf(RegexOption.IGNORE_CASE),
)

// Satır içi kod aralığı (`...`). Çitli blok zaten satır bazında atlanır ama
// tek backtick'li kod çipi atlanmıyordu: `notlar.md` gibi bir çip linklenince
// Markwon bağlantıyı kod olarak basıyor ve kullanıcı ham "[notlar.md](agfile://…)"
// metnini görüyordu (README'de görüldü). Kod çipleri olduğu gibi korunur.
private val InlineCodeRegex = Regex("`[^`]*`")

internal fun linkFileReferences(markdown: String): String {
    if (markdown.contains("agfile://")) return markdown
    var inFence = false
    return markdown.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            return@joinToString line
        }
        if (inFence || line.contains("](")) return@joinToString line
        linkOutsideInlineCode(line)
    }
}

private fun linkOutsideInlineCode(line: String): String {
    val out = StringBuilder()
    var cursor = 0
    for (code in InlineCodeRegex.findAll(line)) {
        out.append(linkPlainSegment(line.substring(cursor, code.range.first)))
        out.append(code.value)
        cursor = code.range.last + 1
    }
    out.append(linkPlainSegment(line.substring(cursor)))
    return out.toString()
}

// Çıplak web adresleri (https://… veya www.…). Markwon'da Linkify eklentisi yok;
// linklenmezse okuyucu modunda düz metin kalıyor ve tıklanamıyordu. Ayrıca URL
// önce ayıklanmazsa FileReferenceRegex, uzantısı bilinen adreslerin kuyruğunu
// ("example.com/blog/yazi.html" gibi) dosya sanıp sahte agfile:// linki üretiyordu.
private val WebUrlRegex = Regex("""\b(?:https?://|www\.)[^\s<>\[\]()"'`]+""", RegexOption.IGNORE_CASE)

private fun linkPlainSegment(segment: String): String {
    val out = StringBuilder()
    var cursor = 0
    for (url in WebUrlRegex.findAll(segment)) {
        out.append(linkFileRefsIn(segment.substring(cursor, url.range.first)))
        // Cümle sonu noktalaması linkin parçası değildir ("bkz https://x.com.").
        val raw = url.value.trimEnd('.', ',', ';', ':', '!', '?')
        val target = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        out.append("[$raw]($target)")
        out.append(url.value.substring(raw.length))
        cursor = url.range.last + 1
    }
    out.append(linkFileRefsIn(segment.substring(cursor)))
    return out.toString()
}

private fun linkFileRefsIn(segment: String): String =
    FileReferenceRegex.replace(segment) { match ->
        val path = match.groupValues[1]
        val suffix = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        "[$path$suffix](agfile://$encoded)"
    }

internal fun buildMarkwon(context: Context, onFileClick: (String) -> Unit): Markwon =
    Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(HtmlPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val filePath = filePathFromLink(link)
                    if (com.agent.bridge.BuildConfig.DEBUG) android.util.Log.d("AgMdLink", "resolve link=$link filePath=$filePath")
                    if (filePath != null) {
                        onFileClick(filePath)
                    } else {
                        // Yalnız http/https/mailto/tel dışarıda açılır; şemasız
                        // "www.…" https'e tamamlanır (karar: markdownBaglantiHedefi).
                        val target = markdownBaglantiHedefi(link)
                        if (target == null) {
                            Toast.makeText(view.context, ACILMAYAN_BAGLANTI_MESAJI, Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                            }
                        }
                    }
                }
            }
        })
        .build()

internal fun filePathFromLink(link: String): String? {
    val decoded = runCatching { URLDecoder.decode(link, "UTF-8") }.getOrDefault(link).trim()
    if (decoded.startsWith("agfile://")) {
        return decoded.removePrefix("agfile://").takeIf { it.isNotBlank() }
    }
    if (decoded.startsWith("file://")) {
        return Uri.parse(decoded).path?.takeIf { it.isNotBlank() }
    }
    val withoutAnchor = decoded.substringBefore('#')
    if (withoutAnchor.startsWith("http://", ignoreCase = true) ||
        withoutAnchor.startsWith("https://", ignoreCase = true) ||
        withoutAnchor.startsWith("mailto:", ignoreCase = true) ||
        withoutAnchor.startsWith("tel:", ignoreCase = true) ||
        // "www.x.com/yol" eğik çizgi yüzünden dosya sanılıyordu → viewer'a düşüyordu.
        withoutAnchor.startsWith("www.", ignoreCase = true)
    ) {
        return null
    }
    val withoutLine = withoutAnchor.replace(Regex(""":\d+$"""), "")
    val looksLikeFile = FileReferenceRegex.containsMatchIn(withoutLine) ||
        withoutLine.contains('/') ||
        withoutLine.contains('\\') ||
        withoutLine.startsWith(".")
    return withoutLine.takeIf { looksLikeFile && it.isNotBlank() }
}

internal fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "upload.bin"
}
