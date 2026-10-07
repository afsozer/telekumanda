package com.agent.bridge

/**
 * Paylaş menüsünden gelen dosya. Bayt DEĞİL yol taşır: paylaşılan içerik önce
 * telefonun önbelleğine yazılır, oradan hedefe (sohbet eki ya da çalışma alanı)
 * aktarılır. Böylece 100 MB'lık bir eki iki kez belleğe almış olmuyoruz ve
 * kullanıcı hedefi seçene kadar hiçbir şey yüklenmiyor.
 */
@androidx.compose.runtime.Immutable
data class SharedFile(
    val cachePath: String,
    val name: String,
    val mimeType: String = "",
    val size: Long = 0,
)

/** Paylaşımdan gelen ve hedefi henüz sorulmamış dosyalar. */
@androidx.compose.runtime.Immutable
data class PendingShare(val files: List<SharedFile>)

/**
 * Hedef sorusunun başlığı. Tek dosyada adı yazmak yararlı (hangisi olduğu belli
 * olur), çoklu paylaşımda ad listesi başlığı taşırdı — sayıya düşülür.
 */
fun shareDialogTitle(files: List<SharedFile>): String = when {
    files.isEmpty() -> "Paylaşılan dosya yok"
    files.size == 1 -> files.first().name
    else -> "${files.size} dosya"
}

/**
 * Çalışma alanı listesinin gösterilip gösterilmeyeceği. Alan yoksa kullanıcıya
 * boş bir liste sunmak yerine soruyu hiç sormayız (çağıran doğrudan sohbete ekler).
 */
fun shareHasWorkspaceTarget(workspaces: List<CoworkWorkspace>): Boolean = workspaces.isNotEmpty()
