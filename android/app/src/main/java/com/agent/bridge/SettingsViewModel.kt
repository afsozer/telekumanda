package com.agent.bridge

import android.content.SharedPreferences
import java.util.UUID

// Backend siralamasinin TEK KAYNAGI. Dosya duzeyinde, cunku hem persist edilmis
// tercihlerin suzgeci hem de yeni kurulumun varsayilani bu liste ve testle
// kilitleniyor (30.09.2026: v1 sokulurken "opencode-app" -> "opencode2-app"
// donusumu listeyi MUKERRER hale getirdi ve telefonda IKI "OpenCode" cipi
// cizildi; derleyici bunu goremez, test gorur).
internal val BACKEND_DEFAULT_ORDER = listOf(
    "claude-app", "agy", "codex-app", "opencode2-app", "omp", "cowork",
)
internal val BACKEND_LITE_ORDER = LITE_BACKEND_ORDER

class SettingsViewModel(
    private val prefs: SharedPreferences,
    private val defaultUrl: String,
    private val liteEdition: Boolean = false,
    private val embeddedToken: String = "",
) {
    fun loadInitialState(): RemoteUiState {
        val legacyUrl = prefs.getString("url", null)
        val legacyToken = prefs.getString("token", null).orEmpty()
        val rawProfiles = prefs.getString(BRIDGE_PROFILES_KEY, null)
        val decodedProfiles = rawProfiles
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { BridgeProfilesJson.decode(raw) }.getOrDefault(emptyList()) }
            .orEmpty()
            .distinctBy { it.id }
        val profiles = decodedProfiles.ifEmpty {
            // Eski anahtarlar yedek kaynak olarak kalır; bozuk/eksik profil JSON'u
            // kullanıcının daha önce çalışan köprü adresini kaybettirmemeli.
            listOf(
                BridgeProfile(
                    id = UUID.randomUUID().toString(),
                    name = "Köprü 1",
                    baseUrl = legacyUrl ?: defaultUrl,
                    token = legacyToken,
                ),
            )
        }
        val savedActiveId = prefs.getString(ACTIVE_BRIDGE_PROFILE_ID_KEY, null)
        val savedProfile = profiles.firstOrNull { it.id == savedActiveId } ?: profiles.first()
        // Lite ilk kurulumda ayar istemesin. Önceki Lite APK bir kez açılmış ve
        // localhost/boş token kaydetmişse de gömülü değerle tek seferde onarılır;
        // kullanıcı sonradan geçerli başka bir köprü yazdıysa üzerine çıkılmaz.
        val seedEmbedded = liteEdition && embeddedToken.isNotBlank() &&
            (savedProfile.token.isBlank() || savedProfile.baseUrl == DEFAULT_URL)
        val activeProfile = if (seedEmbedded) {
            savedProfile.copy(name = "Lite köprü", baseUrl = defaultUrl, token = embeddedToken)
        } else savedProfile
        val configuredProfiles = profiles.map { profile ->
            if (profile.id == activeProfile.id) activeProfile else profile
        }
        if (
            decodedProfiles.isEmpty() ||
            seedEmbedded ||
            activeProfile.id != savedActiveId ||
            legacyUrl != activeProfile.baseUrl ||
            legacyToken != activeProfile.token
        ) {
            // url/token aynası, profil desteğini bilmeyen receiver ve servislerin
            // aktif köprüyle aynı bağlantıyı kullanmaya devam etmesi için korunur.
            persistBridgeProfiles(configuredProfiles, activeProfile.id)
        }
        // DIKKAT: bu liste RemoteUiState'teki varsayilanlarin SUZGECI. Yeni bir
        // backend buraya eklenmezse persist edilmis tercihler onu eler ve backend
        // UI'da hic gorunmez (derleme hatasi da vermez).
        val defaultOrder = BACKEND_DEFAULT_ORDER
        val liteOrder = BACKEND_LITE_ORDER
        val persistedVisible = prefs.getStringSet("visible_backends", null)
        val visibleSet = persistedVisible?.toSet()
            // Sonradan eklenen backend'ler mevcut kurulumlarda da gorunsun diye
            // zorla eklenir; aksi halde eski visible_backends seti onlari gizler.
            ?.let { it + "opencode2-app" + "omp" + "cowork" }
            ?.filter { it in defaultOrder }
            ?.toSet()
            ?: defaultOrder.toSet()
        val savedOrderStr = prefs.getString("backend_order", null)
        val order = if (savedOrderStr.isNullOrBlank()) defaultOrder else {
            // distinct: eski kayitlarda ayni kimlik iki kez gecebiliyor
            // (v1->v2 gocu). Tekrar eden kimlik ayni cipi iki kez cizdiriyor.
            val saved = savedOrderStr.split(",").map { it.trim() }.filter { it in defaultOrder }.distinct()
            val result = saved.toMutableList()
            defaultOrder.filterNot { it in result }.forEach { result.add(it) }
            result
        }
        return RemoteUiState(
            liteEdition = liteEdition,
            settings = activeProfile.settings(),
            bridgeProfiles = configuredProfiles,
            activeBridgeProfileId = activeProfile.id,
            promptQueue = PromptQueueJson.decode(prefs.getString(PROMPT_QUEUE_PREF_KEY, null)),
            device = DeviceUiState(
                paired = prefs.getBoolean("device_paired", false),
            ),
            lastSeenOperationEventId = prefs.getString("last_seen_operation_event", "").orEmpty(),
            visibleBackends = if (liteEdition) liteOrder.toSet() else visibleSet,
            backendOrder = if (liteEdition) liteOrder else order,
            // Gizlenen kullanım kartları. visible_backends'in tersine burada
            // GİZLİ olanlar saklanır; yeni sağlayıcı kartları süzgece takılmadan
            // görünsün diye (bkz. UsageCardVisibility).
            hiddenUsageCards = prefs.getStringSet("hidden_usage_cards", null).orEmpty().toSet(),
        )
    }

    fun saveSettings(state: RemoteUiState) {
        val profiles = state.bridgeProfiles.map { profile ->
            if (profile.id == state.activeBridgeProfileId) {
                profile.copy(baseUrl = state.settings.baseUrl, token = state.settings.token)
            } else {
                profile
            }
        }
        persistBridgeProfiles(profiles, state.activeBridgeProfileId)
    }

    fun saveBridgeProfiles(profiles: List<BridgeProfile>, activeProfileId: String) {
        require(profiles.isNotEmpty())
        require(profiles.any { it.id == activeProfileId })
        persistBridgeProfiles(profiles, activeProfileId)
    }

    fun setUsageCardVisible(state: RemoteUiState, key: String, visible: Boolean): RemoteUiState {
        val next = toggleUsageCard(state.hiddenUsageCards, key, visible)
        prefs.edit().putStringSet("hidden_usage_cards", next).apply()
        return state.copy(hiddenUsageCards = next)
    }

    fun showAllUsageCards(state: RemoteUiState): RemoteUiState {
        prefs.edit().putStringSet("hidden_usage_cards", emptySet()).apply()
        return state.copy(hiddenUsageCards = emptySet())
    }

    fun toggleBackendVisibility(state: RemoteUiState, id: String): RemoteUiState {
        val newSet = if (state.visibleBackends.contains(id)) state.visibleBackends - id else state.visibleBackends + id
        prefs.edit().putStringSet("visible_backends", newSet).apply()
        return state.copy(visibleBackends = newSet)
    }

    fun moveBackend(state: RemoteUiState, from: Int, to: Int): RemoteUiState {
        val list = state.backendOrder.toMutableList()
        val target = to.coerceIn(0, list.size - 1)
        val src = from.coerceIn(0, list.size - 1)
        if (src == target) return state
        val item = list.removeAt(src)
        list.add(target, item)
        prefs.edit().putString("backend_order", list.joinToString(",")).apply()
        return state.copy(backendOrder = list)
    }

    private fun persistBridgeProfiles(profiles: List<BridgeProfile>, activeProfileId: String) {
        val activeProfile = profiles.first { it.id == activeProfileId }
        prefs.edit()
            .putString(BRIDGE_PROFILES_KEY, BridgeProfilesJson.encode(profiles))
            .putString(ACTIVE_BRIDGE_PROFILE_ID_KEY, activeProfileId)
            .putString("url", activeProfile.baseUrl)
            .putString("token", activeProfile.token)
            .apply()
    }

    private companion object {
        const val BRIDGE_PROFILES_KEY = "bridge_profiles"
        const val ACTIVE_BRIDGE_PROFILE_ID_KEY = "active_bridge_profile_id"
    }
}
