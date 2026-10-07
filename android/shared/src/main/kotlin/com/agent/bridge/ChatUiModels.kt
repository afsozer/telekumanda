package com.agent.bridge

private val TOOL_SUMMARY_PREFIXES = listOf(
    "$ ", "Read", "Write", "Edit", "Bash", "PowerShell", "Search", "Glob",
    "Grep", "List", "Delete", "Move", "Copy", "Run ", "Use tool",
    // "🧠" DEĞİL: o düşünce satırı, araç değil (aşağıda ayrıca ele alınır).
    // Bazı backendler araç satırlarını "🔧 <ad>" diye üretiyor.
    // Bu önek listede olmadığı için araç sayılmıyor, thoughtIndex'i de dolu
    // olduğundan thinking kuralına takılıp TAMAMEN gizleniyordu — o backend'de hiç
    // araç adımı görünmemesinin sebebi buydu. Detayı toolDetails'te duruyor,
    // yani araç sayılınca kart açılabilir hale de geliyor.
    "🔧",
)

// ACP sağlayıcılarının düşünce satırı işareti (🧠). Kaynakta kaçış dizisiyle üretiliyor;
// burada da öyle yazılır ki dosya kodlaması karışsa bile eşleşme bozulmasın.
private const val THINKING_MARKER = "🧠"

private val TASK_NOTIFICATION_ENVELOPE = Regex(
    """^\s*<task-notification>\s*([\s\S]*?)\s*</task-notification>\s*$""",
    RegexOption.IGNORE_CASE,
)
private val TASK_NOTIFICATION_FIELD = Regex(
    """<([a-z][a-z0-9-]*)>([\s\S]*?)</\1>""",
    RegexOption.IGNORE_CASE,
)
private val FINISHED_AGENT_SUMMARY = Regex(
    """^Agent\s+["“](.+?)["”]\s+finished\.?$""",
    RegexOption.IGNORE_CASE,
)

data class TaskNotificationUi(
    val taskId: String = "",
    val toolUseId: String = "",
    val outputFile: String = "",
    val status: String = "",
    val summary: String = "",
    val note: String = "",
) {
    fun compactSummary(): String {
        val clean = summary.trim()
        return FINISHED_AGENT_SUMMARY.matchEntire(clean)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: clean.ifBlank { taskId.take(8).ifBlank { "Arka plan görevi" } }
    }

    fun statusLabel(): String = when (status.trim().lowercase()) {
        "completed", "complete", "done", "succeeded", "success" -> "Tamamlandı"
        "failed", "error" -> "Başarısız"
        "cancelled", "canceled" -> "İptal edildi"
        "running", "in_progress", "in-progress" -> "Çalışıyor"
        else -> status.trim().ifBlank { "Bildirim" }
    }
}

/**
 * Claude'un arka plan ajanları tamamlanınca konuşmaya kullanıcı mesajı gibi
 * eklediği XML-benzeri zarfı tanır. Ham metin saklanmaya devam eder; bu model
 * yalnız UI'ın onu kompakt bir durum kartı olarak çizebilmesi içindir.
 */
fun parseTaskNotification(text: String): TaskNotificationUi? {
    val body = TASK_NOTIFICATION_ENVELOPE.matchEntire(text)?.groupValues?.get(1) ?: return null
    val fields = TASK_NOTIFICATION_FIELD.findAll(body).associate { match ->
        match.groupValues[1].lowercase() to decodeTaskNotificationText(match.groupValues[2].trim())
    }
    if (fields.keys.none { it in setOf("task-id", "status", "summary", "output-file") }) return null
    return TaskNotificationUi(
        taskId = fields["task-id"].orEmpty(),
        toolUseId = fields["tool-use-id"].orEmpty(),
        outputFile = fields["output-file"].orEmpty(),
        status = fields["status"].orEmpty(),
        summary = fields["summary"].orEmpty(),
        note = fields["note"].orEmpty(),
    )
}

private fun decodeTaskNotificationText(value: String): String =
    value.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

fun isToolSummary(text: String): Boolean {
    val clean = text.trim()
    return clean.isNotBlank() && TOOL_SUMMARY_PREFIXES.any { clean.startsWith(it, ignoreCase = true) }
}

/**
 * Sohbet görünüm satırı: aynı kullanıcı turundaki düşünce ve araç adımları,
 * tür başına tek katlanabilir grupta toplanır. `index`ler messagesList'in
 * orijinal indeksleridir; ayrıntı yükleme sözleşmesi değişmez.
 */
sealed interface ChatDisplayRow {
    data class Single(val index: Int) : ChatDisplayRow
    data class ToolGroup(val indices: List<Int>, val kind: GroupKind = GroupKind.TOOL) : ChatDisplayRow
}

/** Grup başlığı hangi tür adımı topluyor: araç çağrıları mı, düşünce adımları mı. */
enum class GroupKind { TOOL, THOUGHT }

/**
 * Görünüm kuralları:
 * - Kullanıcı mesajı yeni tur sınırıdır. O turdaki bütün faaliyet satırları,
 *   Araç/Düşünce birbirine karışsa ve araya model durum metni girse bile iki
 *   gruba iner. Gruplar ilk faaliyetin yerinde Düşünce → Araç sırasıyla görünür;
 *   normal model metinleri kendi aralarındaki sırayı korur.
 * - Tek adım da grup olarak çizilir. Böylece aynı veri bazen tekil kart, bazen
 *   grup kartı görünümüne geçip akışı zıplatmaz.
 * - `🧠` işaretli satır ile boş Claude thinking satırı Düşünce'dir. Ayrıntı
 *   `thoughtIndex` üzerinden yüklenir; boş özet artık içeriği gizleme gerekçesi
 *   değildir çünkü akışta yalnız tek kapalı grup yer kaplar.
 * - `thoughtIndex` taşıyan, boş olmayan ve `🧠` olmayan satırlar Araç'tır.
 *   Bu kural sağlayıcıya özgü araç adlarını da kapsar; salt önek listesine
 *   dayanmak OpenCode MCP ve Codex dinamik araçlarını görünmez bırakıyordu.
 * - Kısa, indexsiz thought satırları ("süreç kapandı", "Conversation compacted")
 *   faaliyet değildir ve tekil durum şeridi olarak kalır. Uzun indexsiz eski
 *   reasoning satırları Düşünce grubuna girer.
 */
fun buildChatDisplayRows(messages: List<ChatMessage>): List<ChatDisplayRow> {
    fun activityKind(m: ChatMessage): GroupKind? {
        val thought = m.role.equals("thought", ignoreCase = true)
        if (!thought) return null
        val markerThinking = thought && m.text.trimStart().startsWith(THINKING_MARKER)
        return when {
            markerThinking -> GroupKind.THOUGHT
            m.thoughtIndex >= 0 && m.text.isBlank() -> GroupKind.THOUGHT
            m.thoughtIndex >= 0 -> GroupKind.TOOL
            isToolSummary(m.text) -> GroupKind.TOOL
            m.text.length > 200 -> GroupKind.THOUGHT
            else -> null
        }
    }

    val rows = ArrayList<ChatDisplayRow>(messages.size)
    var cursor = 0
    while (cursor < messages.size) {
        // Kullanıcı satırı hem görünür kalır hem de kendisinden sonraki turu açar.
        if (messages[cursor].role.equals("user", ignoreCase = true)) {
            rows.add(ChatDisplayRow.Single(cursor))
            cursor++
        }

        val turnStart = cursor
        while (cursor < messages.size && !messages[cursor].role.equals("user", ignoreCase = true)) {
            cursor++
        }
        val turnEnd = cursor
        val firstActivity = (turnStart until turnEnd).firstOrNull {
            activityKind(messages[it]) != null
        }
        if (firstActivity == null) {
            (turnStart until turnEnd).forEach { rows.add(ChatDisplayRow.Single(it)) }
            continue
        }

        // İlk faaliyet öncesindeki model metinleri yerinde kalır. Faaliyetler
        // türlerine ayrılıp burada bir kez gösterilir; sonraki normal model
        // metinleri de kendi kronolojik sırasını koruyarak grupların altına gelir.
        (turnStart until firstActivity).forEach { rows.add(ChatDisplayRow.Single(it)) }
        val thoughts = (firstActivity until turnEnd).filter {
            activityKind(messages[it]) == GroupKind.THOUGHT
        }
        val tools = (firstActivity until turnEnd).filter {
            activityKind(messages[it]) == GroupKind.TOOL
        }
        if (thoughts.isNotEmpty()) rows.add(ChatDisplayRow.ToolGroup(thoughts, GroupKind.THOUGHT))
        if (tools.isNotEmpty()) rows.add(ChatDisplayRow.ToolGroup(tools, GroupKind.TOOL))
        (firstActivity until turnEnd).filter {
            activityKind(messages[it]) == null
        }.forEach { rows.add(ChatDisplayRow.Single(it)) }
    }
    return rows
}

fun applySlashCommandToInput(current: String, command: String): String {
    val clean = command.trim().trimStart('/')
    return if (clean.isBlank()) current else "/$clean "
}
