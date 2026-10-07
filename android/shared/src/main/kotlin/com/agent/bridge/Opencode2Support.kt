package com.agent.bridge

// OpenCode durum ailesi seçicileri.
//
// TARİH: 24-26 Eyl 2026'da v1 ve v2 telefonda YAN YANA duruyordu ve bu dosya
// hangi durum ailesinin okunacağını seçiyordu (iki ayrı `OpencodeUiState`).
// 30.09.2026'da v1 tümüyle söküldü (npm paketi + köprü backend'i); geride TEK
// aile kaldı (`RemoteUiState.opencode`) ve tek kimlik: "opencode2-app".
//
// Fonksiyonlar İSİMLERİYLE KALDI, gövdeleri sadeleşti: çağrı yerleri onlarca
// dosyada ve hepsini dokunmak sessiz kapsam boşluğu riski (bu depoda ölçülmüş
// hata sınıfı). Kimlik parametresi de duruyor — cowork sağlayıcı kimliğiyle
// çağırıyor ve OpenCode dışı bir kimlikte aile okunmamalı.

/** Verilen backend kimliği OpenCode mu? (v1 söküldü, tek kimlik kaldı.) */
fun isOpencodeFamily(id: String?): Boolean = id == Backend.OPENCODE2_APP.id

/** OpenCode durum ailesi — tek aile; kimlik yalnız doğrulama için. */
fun RemoteUiState.opencodeFamily(id: String?): OpencodeUiState = opencode

/** OpenCode durum ailesini günceller. */
fun RemoteUiState.withOpencodeFamily(id: String?, block: (OpencodeUiState) -> OpencodeUiState): RemoteUiState =
    copy(opencode = block(opencode))

/**
 * AKTİF OpenCode backend kimliği; OpenCode aktif değilse null.
 *
 * Snapshot indirgemesi bunu kullanır: doğrudan sekme ya da cowork sağlayıcısı
 * olarak OpenCode koşuyorsa ajan/geri-sarma/bağlam alanları yazılmalı.
 */
fun RemoteUiState.aktifOpencodeBackendId(): String? = when {
    backend == Backend.OPENCODE2_APP.id -> Backend.OPENCODE2_APP.id
    backend == Backend.COWORK.id &&
        normalizeCoworkProvider(coworkProvider) == Backend.OPENCODE2_APP.id -> Backend.OPENCODE2_APP.id
    else -> null
}
