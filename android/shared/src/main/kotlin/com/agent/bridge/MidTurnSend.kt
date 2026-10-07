package com.agent.bridge

// Tur SÜRERKEN yazılan metnin iki ayrı yolu var ve ikisi ayrı köprü ucuna dayanır:
//
//   • yönlendir   → POST /<b>/steer      — süren turu keser/yönlendirir
//   • ajana bırak → POST /<b>/follow-up  — tur bitince kendi turu olarak işlenir
//
// Hangi backend'in hangisine sahip olduğu ÖLÇÜLMÜŞ bir gerçek ve tek kaynak
// backend-contract.mjs (userInputSteer / userInputQueue). Buraya backend ADI
// GÖMÜLMEZ: 25.08.2026'ya kadar iki ekran da `backend == "omp" || ...` yazıyordu
// ve OpenCode'un kuyruk desteği (köprüde vardı) telefonda hiç görünmedi.
//
// COWORK: backendCapabilities zaten seçili sağlayıcıdan türetiyor, o yüzden
// aşağıdaki iki soruda cowork'e özel dal YOK. Gönderimin hangi delege'ye
// gideceğini seçerken ise sağlayıcıyı açmak gerekiyor — midTurnSendBackend.

/** Tur içi gönderimin gerçekte hangi backend'e gideceği (cowork sağlayıcısını çözer). */
fun midTurnSendBackend(backend: String?, coworkProvider: String): String =
    if (backend == "cowork") normalizeCoworkProvider(coworkProvider) else backend.orEmpty()

/** Mesaj tur bitince işlensin diye ajana bırakılabiliyor mu? (/follow-up) */
fun RemoteUiState.midTurnQueueSupported(): Boolean =
    backendCapabilities(backend, coworkProvider, backendCatalog).userInputQueue

/** Süren tura enjeksiyon — "Yönlendir" tuşu — var mı? (/steer) */
fun RemoteUiState.midTurnSteerSupported(): Boolean =
    backendCapabilities(backend, coworkProvider, backendCatalog).userInputSteer
