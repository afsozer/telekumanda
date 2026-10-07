package com.agent.bridge

// Bildirimin hangi sohbete ait olduğunu ve kullanıcının şu an ona bakıp
// bakmadığını aynı anahtarla karşılaştırırız. Bildirim tag'i de budur;
// sohbete girildiğinde tag'e göre iptal edilir.
fun notificationKey(backend: String, sessionId: String): String = "$backend:$sessionId"

// Kullanıcı zaten o sohbete bakıyorsa bildirim gürültüdür — görev sonucu da
// onay kartı da ekranda duruyor. Başka bir sohbetteyken veya uygulama arka
// plandayken normal atılır.
fun suppressesNotification(
    foreground: Boolean,
    openKey: String,
    backend: String,
    sessionId: String,
): Boolean {
    if (!foreground) return false
    if (openKey.isBlank() || sessionId.isBlank()) return false
    return openKey == notificationKey(backend, sessionId)
}
