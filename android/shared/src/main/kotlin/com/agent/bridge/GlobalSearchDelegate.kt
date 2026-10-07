package com.agent.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Global arama: debounce + bridge çağrısı + state yönetimi (Phase 7). */
class GlobalSearchDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
) {
    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            update { it.copy(search = SearchUiState(query = query)) }
            return
        }
        update { it.copy(search = it.search.copy(query = query, loading = true, error = "", warnings = emptyList())) }
        searchJob = scope.launch {
            delay(350)
            // İPTAL HATA DEĞİLDİR.
            //
            // Burada `runCatching` vardı ve `CancellationException`'ı da yakalıyordu:
            // her tuş vuruşu önceki işi `cancel()` ediyor, iptal edilen iş uyanıp
            // "StandaloneCoroutine was cancelled" metnini `error`a yazıyordu — üstelik
            // YENİ aramanın taze durumunun üstüne, çünkü iptal `update`ten sonra
            // işliyor. `onSuccess` de `error`u temizlemediği için o metin kalıcıydı ve
            // Spotlight'ta sonuç dalından ÖNCE gelen `error` dalı sonuçları hiç
            // göstermiyordu: arama tamamen çalışmaz görünüyordu (kullanıcı bildirdi
            // 20.08.2026, ekran görüntüsüyle).
            //
            // Yeniden fırlatmak yapısal eşzamanlılığın da şartı: iptal sinyalini
            // yutan bir coroutine iptal edilemez hâle gelir.
            val sonuc = try {
                client.searchGlobal(state().settings, query)
            } catch (iptal: CancellationException) {
                throw iptal
            } catch (e: Throwable) {
                update { it.copy(search = it.search.copy(loading = false, error = e.message ?: "unknown")) }
                reportError("Arama başarısız", e)
                return@launch
            }
            update {
                it.copy(
                    search = it.search.copy(
                        loading = false,
                        hits = sonuc.hits,
                        warnings = sonuc.warnings,
                        // Savunma amaçlı: `updateQuery` girişte zaten temizliyor, ama
                        // "sonuç var" ile "hata var" aynı anda doğru olamaz ve bu
                        // hatanın bedeli ağır — Spotlight hata dalını önce çiziyor,
                        // yani kalıntı bir metin bütün sonuçları gizler.
                        error = "",
                    ),
                )
            }
        }
    }

    fun dismiss() {
        searchJob?.cancel()
        update { it.copy(search = SearchUiState()) }
    }
}
