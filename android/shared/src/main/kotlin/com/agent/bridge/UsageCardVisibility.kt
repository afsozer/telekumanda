package com.agent.bridge

/**
 * Kullanım ekranındaki kartların göster/gizle mantığı (saf; Compose'suz).
 *
 * DİKKAT — neden GİZLENENLER saklanıyor, görünenler değil:
 * `visible_backends` tercihinde tam tersi yapılmış ve SettingsViewModel'de şu not
 * düşülmüş: yeni bir backend eklenince persist edilmiş "görünür" seti onu eliyor
 * ve backend arayüzde hiç görünmüyor (derleme hatası da vermiyor), bu yüzden
 * sonradan gelenler elle `it + "omp" + "cowork"` diye zorlanmak zorunda kalınmış.
 * Burada gizlenen anahtarları sakladığımız için yeni bir sağlayıcı kartı (ör.
 * DeepSeek) hiçbir ek koda gerek kalmadan görünür gelir; kullanıcı istemedikçe
 * kaybolmaz.
 */

/** RunPod kartı UsageGroup değil, ayrı çizilen bir kart — sabit anahtarı var. */
const val RUNPOD_USAGE_CARD_KEY = "runpod"

/**
 * Kart kimliği. HubUsageScreen'deki LazyColumn `key`'iyle AYNI olmak zorunda;
 * ayrıştıkları anda gizleme yanlış kartı tutar.
 */
fun usageCardKey(group: UsageGroup): String = "${group.source}:${group.name}"

/** Ayar sayfasındaki tek satır: kart adı + görünürlük. */
data class UsageCardToggle(
    val key: String,
    val label: String,
    val visible: Boolean,
)

fun visibleUsageGroups(groups: List<UsageGroup>, hidden: Set<String>): List<UsageGroup> =
    if (hidden.isEmpty()) groups else groups.filterNot { usageCardKey(it) in hidden }

fun isUsageCardVisible(key: String, hidden: Set<String>): Boolean = key !in hidden

/**
 * Göster/gizle sayfasının satırları. Kaynak listesi CANLI kart listesidir:
 * köprüden gelmeyen bir sağlayıcı (ör. anahtarı silinmiş OpenRouter) ayar
 * ekranında hayalet satır bırakmaz.
 */
fun usageCardToggles(
    groups: List<UsageGroup>,
    runpodEnabled: Boolean,
    hidden: Set<String>,
): List<UsageCardToggle> {
    val rows = groups.map { group ->
        UsageCardToggle(
            key = usageCardKey(group),
            label = group.name.ifBlank { group.source.ifBlank { "Adsız kart" } },
            visible = isUsageCardVisible(usageCardKey(group), hidden),
        )
    }
    if (!runpodEnabled) return rows
    return rows + UsageCardToggle(
        key = RUNPOD_USAGE_CARD_KEY,
        label = "RunPod",
        visible = isUsageCardVisible(RUNPOD_USAGE_CARD_KEY, hidden),
    )
}

/** Tek kartın görünürlüğünü değiştirip yeni gizli kümesini döndürür. */
fun toggleUsageCard(hidden: Set<String>, key: String, visible: Boolean): Set<String> =
    if (visible) hidden - key else hidden + key

/**
 * Gizli kart kalmadığında sayaç 0 döner; başlıkta rozet çizilip çizilmeyeceğini
 * belirler. Yalnız CANLI kartları sayar: köprüden artık gelmeyen bir kartın eski
 * gizleme kaydı "3 kart gizli" diye yanıltıcı bir rozet üretmemeli.
 */
fun hiddenUsageCardCount(
    groups: List<UsageGroup>,
    runpodEnabled: Boolean,
    hidden: Set<String>,
): Int = usageCardToggles(groups, runpodEnabled, hidden).count { !it.visible }
