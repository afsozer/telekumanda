package com.agent.bridge

// Yeni oturum / cekmece / proje sohbeti listelerinde gosterilecek backend'ler.
// Kullanicinin ayari (sira + gorunurluk) telefon genelidir; ama her kopru her
// backend'i sunmaz (Mac koprusu yalniz claude-app + opencode-app). Koprunun
// /backends katalogu yuklendiyse onda OLMAYAN backend gizlenir. Katalog yoksa
// (eski kopru, henuz yuklenmedi, cevrimdisi) kullanici ayari aynen gecerlidir.
// Ayarlar > Saglayicilar ekrani bunu KULLANMAZ: orada hepsi gorunmeli.
fun availableBackendIds(
    order: List<String>,
    visible: Set<String>,
    catalog: BackendCatalogInfo?,
): List<String> {
    val chosen = order.filter { it in visible }
    val offered = catalog?.takeIf { it.loaded }?.entries?.keys ?: return chosen
    return chosen.filter { it in offered }
}

fun RemoteUiState.availableBackendIds(): List<String> =
    availableBackendIds(backendOrder, visibleBackends, backendCatalog).filter(::supportsEditionBackend)
