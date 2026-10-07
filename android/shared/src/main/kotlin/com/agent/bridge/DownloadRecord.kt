package com.agent.bridge

/**
 * İndirme kaydı; DownloadRepo (Android) ve DownloadsDelegate (shared) tarafından
 * paylaşılır. Gerçekleştirme Android'de (MediaStore/SharedPreferences) kalır; burada
 * yalnız taşınabilir veri modeli vardır.
 */
data class DownloadRecord(
    val name: String,
    val sourcePath: String,
    val size: Long,
    val mimeType: String,
    val downloadedAt: Long,
    val localUri: String,
    // Klasör kaydı: workspace klasör olarak indirildiğinde true; localUri boş kalır,
    // içerik relativePath (MediaStore RELATIVE_PATH, örn "Download/AgentBridge/dava-x/")
    // üzerinden listelenir. Normal dosya kayıtlarında relativePath kökü gösterir.
    val folder: Boolean = false,
    val relativePath: String = "",
)
