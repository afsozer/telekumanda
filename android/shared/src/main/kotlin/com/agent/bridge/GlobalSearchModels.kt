package com.agent.bridge

/** Global arama sonuç öğesi. Bridge /search/global yanıtıyla birebir. */
data class GlobalSearchHit(
    val id: String,
    val type: String,          // "project" | "session" | "message"
    val projectId: String,
    val projectPath: String,
    val projectName: String,
    val backend: String,
    val backendLabel: String,
    val sessionId: String,
    val container: String,     // "direct" | "cowork"
    val title: String,
    val role: String,          // "user" | "agent" | ""
    val snippet: String,
    val rowId: String,
    val matchOrdinal: Int,
    val mtime: Long,
)

/**
 * Köprünün arama cevabı — isabetler VE eksiklik uyarıları.
 *
 * Uyarılar 18.08.2026'ya kadar istemcide tamamen düşüyordu: köprü
 * `warnings: ["codex-app: arama süre bütçesini aştı…"]` gönderiyor, istemci
 * yalnız `hits` dizisini okuyup gerisini atıyordu. Sonuç: yarısı taranmış bir
 * arama, tam taranmış bir aramadan AYIRT EDİLEMİYORDU — "bulunamadı" ile
 * "bakılamadı" aynı görünüyordu.
 */
data class GlobalSearchResult(
    val hits: List<GlobalSearchHit> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** İç içe arama state'i. */
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<GlobalSearchHit> = emptyList(),
    val error: String = "",
    // Sonuç geldi ama EKSİK: bir sağlayıcı süre bütçesini aştı ya da hata verdi.
    // `error`dan ayrı tutuluyor — o "hiç sonuç yok", bu "sonuç var ama tamam değil".
    val warnings: List<String> = emptyList(),
)

/** Sohbet içi arama state'i (Phase 7 — Section 16). */
data class ChatSearchState(
    val open: Boolean = false,
    val query: String = "",
    val loadingHistory: Boolean = false,
    val matchRowIds: List<String> = emptyList(),
    val selectedIndex: Int = -1,
    val truncated: Boolean = false,
    // Tam geçmiş yüklenirken hata oluştu; arama eldeki (eksik) mesajlar üzerinde
    // çalışıyor. UI kullanıcıyı sonucun bütün geçmişi kapsamayabileceği konusunda
    // uyarabilir. truncated (kapasite sınırı) ile ayrı tutulur.
    val historyLoadError: Boolean = false,
    val pendingTargetRowId: String = "",
)
