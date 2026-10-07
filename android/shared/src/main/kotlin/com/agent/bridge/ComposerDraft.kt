package com.agent.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ek listesinin JSON karşılığı. Ek zaten köprüye YÜKLENMİŞ durumda; state'te
 * taşınan yalnız ad + PC yolu, yani kalıcılaştırmak birkaç yüz bayt.
 *
 * Kuyruk (PromptQueueJson) ve taslak aynı biçimi paylaşsın diye ayrı nesne:
 * iki ayrı kopya zamanla birbirinden sapardı.
 */
object ChatAttachmentJson {
    fun encode(items: List<ChatAttachment>): JSONArray {
        val array = JSONArray()
        items.forEach { attachment ->
            array.put(
                JSONObject()
                    .put("name", attachment.name)
                    .put("path", attachment.path)
                    .put("isImage", attachment.isImage)
                    .also { json -> attachment.localUri?.let { json.put("localUri", it) } }
            )
        }
        return array
    }

    /** Yolu olmayan kayıt atılır: yolsuz ek modele hiçbir şey ifade etmez. */
    fun decode(array: JSONArray?): List<ChatAttachment> = buildList {
        val values = array ?: return@buildList
        for (i in 0 until values.length()) {
            val attachment = values.optJSONObject(i) ?: continue
            val path = attachment.optString("path")
            if (path.isBlank()) continue
            add(
                ChatAttachment(
                    name = attachment.optString("name")
                        .ifBlank { path.substringAfterLast('\\').substringAfterLast('/') },
                    path = path,
                    localUri = attachment.optString("localUri").takeIf { it.isNotBlank() },
                    isImage = attachment.optBoolean("isImage"),
                )
            )
        }
    }
}

/**
 * Composer'a yazılmış ama HENÜZ GÖNDERİLMEMİŞ metin.
 *
 * Neden diske yazılıyor: `input` yalnız bellekteydi, yani uygulama süreci ölünce
 * (APK güncellemesi, sistem belleği toplaması, çökme) yazılmış mesaj sessizce
 * kayboluyordu — kullanıcı uzun bir mesajı yeniden yazmak zorunda kaldı
 * (03.08.2026). Kuyruğa alınmış promptlar zaten kalıcıydı; eksik olan, kuyruğa
 * girmemiş taslaktı.
 *
 * Taslak SEKMEYE bağlı: aynı metni başka bir sekmede açılan composer'a
 * doldurmak, mesajı yanlış oturuma göndermenin kolay yolu olurdu.
 */
const val COMPOSER_DRAFT_PREF_KEY = "composer_draft_v1"

/** Composer'ın gönderilmemiş içeriği: metin + ekler, ait olduğu sekmeyle. */
@androidx.compose.runtime.Immutable
data class ComposerDraft(
    val tabId: String = "",
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

object ComposerDraftJson {
    fun encode(draft: ComposerDraft): String =
        JSONObject()
            .put("version", 1)
            .put("tabId", draft.tabId)
            .put("text", draft.text)
            .put("attachments", ChatAttachmentJson.encode(draft.attachments))
            .toString()

    /** Bozuk/eski kayıtta boş taslak; taslak uğruna açılış kırılmaz. */
    fun decode(raw: String?): ComposerDraft {
        if (raw.isNullOrBlank()) return ComposerDraft()
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("version") != 1) return@runCatching ComposerDraft()
            ComposerDraft(
                tabId = obj.optString("tabId"),
                text = obj.optString("text"),
                attachments = ChatAttachmentJson.decode(obj.optJSONArray("attachments")),
            )
        }.getOrDefault(ComposerDraft())
    }
}

/**
 * Açılışta composer'a konacak taslak. Taslak BAŞKA sekmeye aitse boş döner:
 * yanlış oturuma yazmaktansa taslağı göstermemek yeğdir. Sekmesiz kayıt
 * (eski sürüm) da elenir.
 */
fun draftFor(raw: String?, activeTabId: String): ComposerDraft {
    val draft = ComposerDraftJson.decode(raw)
    if (draft.tabId.isBlank() || activeTabId.isBlank() || draft.tabId != activeTabId) return ComposerDraft()
    return draft
}

/**
 * Sekme başına taslak sözlüğü. Tek taslaklı biçimin (v1) yerini aldı.
 *
 * Neden ayrı anahtar: eski kayıt tek bir sekmenin taslağıydı. Aynı anahtara
 * farklı şekilli bir JSON yazmak, sürüm atlayan bir kullanıcının taslağını
 * sessizce çöpe atardı. Eski anahtar okunmaya devam ediyor (göç aşağıda),
 * yalnız artık yazılmıyor.
 */
const val COMPOSER_DRAFTS_PREF_KEY = "composer_drafts_v1"

object ComposerDraftsJson {
    fun encode(drafts: Map<String, ComposerDraft>): String {
        val array = JSONArray()
        drafts.forEach { (tabId, draft) ->
            if (tabId.isBlank() || draft.isEmpty) return@forEach
            array.put(
                JSONObject()
                    .put("tabId", tabId)
                    .put("text", draft.text)
                    .put("attachments", ChatAttachmentJson.encode(draft.attachments))
            )
        }
        return JSONObject().put("version", 1).put("drafts", array).toString()
    }

    /** Bozuk/eski kayıtta boş sözlük; taslak uğruna açılış kırılmaz. */
    fun decode(raw: String?): Map<String, ComposerDraft> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("version") != 1) return@runCatching emptyMap()
            val array = obj.optJSONArray("drafts") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val tabId = item.optString("tabId")
                    if (tabId.isBlank()) continue
                    val draft = ComposerDraft(
                        tabId = tabId,
                        text = item.optString("text"),
                        attachments = ChatAttachmentJson.decode(item.optJSONArray("attachments")),
                    )
                    if (!draft.isEmpty) put(tabId, draft)
                }
            }
        }.getOrDefault(emptyMap())
    }
}

/**
 * Diskteki taslakları okur; v1 tek-taslak kaydından göç eder.
 *
 * Göçte sekme kimliği ŞART: kimliksiz eski kayıt hangi sekmeye ait olduğunu
 * bilmiyor demektir ve onu rastgele bir sekmeye koymak, mesajı yanlış oturuma
 * göndermenin kolay yolu olurdu.
 */
fun decodeComposerDrafts(rawDrafts: String?, rawLegacy: String?): Map<String, ComposerDraft> {
    val drafts = ComposerDraftsJson.decode(rawDrafts)
    if (drafts.isNotEmpty() || rawDrafts != null) return drafts
    val legacy = ComposerDraftJson.decode(rawLegacy)
    if (legacy.tabId.isBlank() || legacy.isEmpty) return emptyMap()
    return mapOf(legacy.tabId to legacy)
}

/**
 * SEKME DEĞİŞTİRİRKEN COMPOSER'I DA DEĞİŞTİR.
 *
 * 19.08.2026'da canlıda bildirilen hata: bir sekmede yazılan metin sekme
 * değişince silinmiyor, yeni açılan sekmede de duruyordu. Sebep bir kayıt/geri
 * yükleme hatası DEĞİLDİ — `input` ve `attachments` en baştan beri sekmeden
 * bağımsız TEK alandı, yani taslağın sekmeye ait olduğu fikri yalnız diske
 * yazma yolunda (`draftFor`) uygulanıyordu; çalışan uygulamada hiç yoktu.
 *
 * Tehlikesi kozmetik değil: "Yeni Sekme"de yazılmış yarım bir cümle, başka bir
 * oturuma geçip Gönder'e basınca o oturuma gider.
 *
 * Kural: etkin sekmenin taslağı `input`ta, diğerlerininki `composerDrafts`ta.
 * Bu fonksiyon ikisi arasında takas yapar ve boş taslağı hiç saklamaz —
 * sözlük açık sekme sayısıyla sınırlı kalsın.
 */
fun RemoteUiState.withActiveTab(tabId: String): RemoteUiState {
    if (tabId == activeTabId) return this
    val ayrilan = ComposerDraft(activeTabId, input, attachments)
    val gelen = composerDrafts[tabId] ?: ComposerDraft()
    val kalan = if (activeTabId.isNotBlank() && !ayrilan.isEmpty) {
        composerDrafts + (activeTabId to ayrilan)
    } else {
        composerDrafts - activeTabId
    }
    return copy(
        activeTabId = tabId,
        input = gelen.text,
        attachments = gelen.attachments,
        composerDrafts = kalan - tabId,
    )
}

/** Kapanan sekmelerin taslaklarını atar: sözlük açık sekmelerden büyümesin. */
fun RemoteUiState.pruneComposerDrafts(): RemoteUiState {
    if (composerDrafts.isEmpty()) return this
    val yasayan = openTabs.mapTo(mutableSetOf()) { it.id }
    val temiz = composerDrafts.filterKeys { it in yasayan }
    return if (temiz.size == composerDrafts.size) this else copy(composerDrafts = temiz)
}

/** Diske yazılacak tam tablo: sözlük + etkin sekmenin o anki içeriği. */
fun RemoteUiState.allComposerDrafts(): Map<String, ComposerDraft> {
    val aktif = ComposerDraft(activeTabId, input, attachments)
    return if (activeTabId.isBlank() || aktif.isEmpty) composerDrafts
    else composerDrafts + (activeTabId to aktif)
}
