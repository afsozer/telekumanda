package com.agent.bridge

// Onay isteğinin kimliği (`requestId`) ve bayat onayın tanınması.
//
// Köprü `/<backend>/approve` gövdesindeki `requestId`'yi o oturumda BEKLEYEN
// onayın kimliğiyle karşılaştırıyor; eşleşmezse 409 + `{ok:false, error:"stale
// approval"}` dönüyor. Amaç: ekranda ya da bildirimde duran ESKİ bir onay
// kartına basmak, arada gelmiş YENİ bir isteği onaylamasın. `allow` her zaman
// açık true/false gider; bu kimlik onun yanına eklenir, yerine geçmez.

/** Bayat onaya basıldığında kullanıcıya gösterilen metin. */
const val BAYAT_ONAY_MESAJI = "Bu onay isteği artık geçerli değil"

/**
 * Köprünün "bu onay artık bekleyen onay değil" cevabı mı?
 *
 * Yalnız 409 yetmez: köprü 409'u başka çakışmalar için de kullanıyor (cowork
 * kilidi). Gövdedeki `error` metni sözleşmenin parçası, ona da bakılıyor.
 */
fun bayatOnayHatasi(hata: Throwable): Boolean =
    hata is BridgeHttpException && hata.code == 409 &&
        hata.serverMessage.contains("stale approval", ignoreCase = true)

/** Onay gövdesine kimliği ekler; kimlik bilinmiyorsa (agy, eski köprü) alan hiç yazılmaz. */
internal fun org.json.JSONObject.onayKimligiEkle(requestId: String): org.json.JSONObject {
    if (requestId.isNotBlank()) put("requestId", requestId)
    return this
}

/**
 * Bildirimdeki onay tuşlarının biçimi.
 *
 * - [DOGRUDAN]: "İzin ver"/"Reddet" yayına (ApprovalReceiver) gider; API 31+'da
 *   `setAuthenticationRequired(true)` ile, yani kilit ekranında önce kilit açılır.
 * - [UYGULAMADA_AC]: tuş onay vermez, uygulamayı o oturumun onay kartına açar.
 *   API 31 altında eyleme kimlik doğrulaması bağlanamıyor; kilit açmayı
 *   etkinliği başlatırken Android kendisi istiyor. Kimliği bilinmeyen onayda da
 *   bu yol seçilir: neyi onayladığını bilmeyen bir tuş, bayat bildirimden yeni
 *   isteği onaylayabilirdi.
 */
enum class OnayEylemKipi { DOGRUDAN, UYGULAMADA_AC }

fun onayEylemKipi(sdkInt: Int, requestId: String): OnayEylemKipi = when {
    requestId.isBlank() -> OnayEylemKipi.UYGULAMADA_AC
    sdkInt >= 31 -> OnayEylemKipi.DOGRUDAN
    else -> OnayEylemKipi.UYGULAMADA_AC
}

/**
 * Kilit ekranında görünen içeriksiz sürümün metni. Bildirimin asıl başlığı ve
 * özeti (oturum adı, komut, not başlığı) kilit açılmadan görünmez; burada
 * yalnız türü söyleyen genel bir cümle kalır.
 */
fun kilitEkraniMetni(kind: String): String = when (kind) {
    "attention" -> "Onay bekleniyor"
    "completed", "failed" -> "Görev güncellemesi var"
    "reminder" -> "Hatırlatıcı var"
    "note" -> "Yeni not var"
    "system" -> "Sistem bildirimi var"
    else -> "Yeni bildirim var"
}

/** Ekrandaki onay kartının kimliği; kart yoksa boş. */
val RemoteUiState.bekleyenOnayKimligi: String get() = approval?.requestId.orEmpty()

/**
 * Onay gönderiminin hatası. Bayat onay çökme ya da teknik hata metni değil,
 * kısa bir açıklama olarak gösterilir ve konuşma tazelenir: ekrana o an
 * bekleyen GÜNCEL onay kartı gelsin.
 */
internal suspend fun onayHatasiniIsle(
    hata: Throwable,
    emit: suspend (String) -> Unit,
    yenile: () -> Unit,
    digerHata: suspend (Throwable) -> Unit,
) {
    if (bayatOnayHatasi(hata)) {
        emit(BAYAT_ONAY_MESAJI)
        yenile()
    } else {
        digerHata(hata)
    }
}
