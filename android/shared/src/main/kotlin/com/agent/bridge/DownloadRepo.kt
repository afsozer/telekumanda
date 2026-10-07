package com.agent.bridge

/**
 * İndirme deposu için platform arayüzü (Faz 1 platform-arayüz deseni,
 * [ConversationCache] ile aynı yaklaşım).
 *
 * - Android gerçeklemesi: AndroidDownloadRepo (MediaStore/Downloads — app modülü)
 * - Desktop gerçeklemesi (Faz 2): java.nio + kullanıcı indirilenler klasörü
 *
 * Delegate'ler (DownloadsDelegate, CoworkDelegate) yalnız bu arayüzü bilir;
 * gerçekleştirme RemoteStore/RemoteViewModel oluşturulurken enjekte edilir.
 * Not: exportBytes (DOCX dışa aktarım) yalnız app tarafında çağrılır ve
 * arayüzde değildir — somut AndroidDownloadRepo üzerinde kalır.
 */
interface DownloadRepo {
    suspend fun download(
        client: BridgeClient,
        settings: BridgeSettings,
        sourcePath: String,
        displayName: String,
        // AgentBridge kökünün altındaki alt klasör ("Pictures" gibi). Boş =
        // doğrudan kök. Görseller Galeri'de düzgün görünsün diye ayrıldı.
        subDir: String = "",
        onProgress: (Float) -> Unit,
    ): DownloadRecord

    suspend fun downloadAndExtract(
        client: BridgeClient,
        settings: BridgeSettings,
        sourcePath: String,
        folderName: String,
        onProgress: (Float) -> Unit,
    ): DownloadRecord

    fun history(): List<DownloadRecord>
    fun listFolder(relativePath: String): List<DownloadRecord>
    fun moveIntoFolder(localUri: String, folderRelativePath: String): Boolean
    fun deleteRecord(record: DownloadRecord): Boolean
    fun clearHistory()
}
