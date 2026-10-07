package com.agent.bridge

// Faz 2b: backend-basina heterojen secenek listelerinin JENERIK okuma modeli.
// UI (ui2) yalniz bunu okur; tip cesitliligi bu dosyada olur ve biter.
data class BackendOption(val id: String, val label: String, val detail: String = "")


fun RemoteUiState.backendModelOptions(id: String): List<BackendOption> = when (id) {
    "agy" -> agyModels.map { BackendOption(it.id, it.label) }
    "claude-app" -> claudeModels.map { BackendOption(it.id, it.label) }
    "codex-app" -> codexModels.map { BackendOption(it.id, it.label) }
    // v1 ve v2 AYNI dal: katalog semasi ayni, yalniz durum ailesi ayri
    // (`opencodeFamily`). Dal opencode2 icin eksikti ve liste bos donuyordu;
    // sheet bos listeyi "yukleniyor" sayiyor, yani MODEL SEC sonsuza dek
    // donuyordu (26.09.2026, kullanici bildirdi).
    "opencode2-app" -> rankOpencodeModels(opencodeFamily(id).availableModels)
        .map { BackendOption(it.id, it.label, it.detail) }
    "omp" -> omp.availableModels.map { BackendOption(it.id, it.label) }
    // listesinin kullanıcı sırasını sonradan ezmesini engeller.
    "cowork" -> backendModelOptions(normalizeCoworkProvider(coworkProvider))
    else -> emptyList()
}

fun RemoteUiState.backendPermissionModeOptions(id: String): List<BackendOption> = when (id) {
    "agy" -> emptyList()
    "claude-app" -> {
        val modes = claudeAppInfo?.permissionModes?.ifEmpty { null } ?: listOf("", "auto", "plan", "acceptEdits", "bypassPermissions")
        modes.map { BackendOption(it, permissionModeLabel(it)) }
    }
    "codex-app" -> codexAppPermissionModes.map { BackendOption(it.id, it.label) }
    "opencode2-app" -> {
        val modes = opencodeFamily(id).permissionModes.ifEmpty {
            listOf(
                PermissionMode("yolo", "YOLO", "Serbest çalış, hiçbir şey sorma"),
                PermissionMode("ask", "Ask", "Her izin için sor"),
                PermissionMode("plan", "Plan", "Plan direktifi ekle")
            )
        }
        modes.map { BackendOption(it.id, it.name.ifBlank { permissionModeLabel(it.id) }, it.description) }
    }
    "omp" -> {
        val modes = omp.permissionModes.ifEmpty {
            listOf(
                PermissionMode("yolo", "YOLO", "Onay sormadan çalışır"),
                PermissionMode("write", "Write", "Yazma işlemlerinde sorar"),
                PermissionMode("ask", "Ask", "Araç çağrılarında sorar"),
            )
        }
        modes.map { BackendOption(it.id, it.name.ifBlank { permissionModeLabel(it.id) }, it.description) }
    }
    // Bilinen modlarda tek, kararlı Android metnini kullan. Gelecekte ACP yeni
    // bir mod eklerse onu canlı adı/açıklamasıyla kaybetmeden göster.
    "cowork" -> listOf(
        BackendOption("yolo", "Yolo", "Otomatik onay"),
        BackendOption("onay", "Onay", "Her izin için sor")
    )
    else -> emptyList()
}

fun RemoteUiState.backendEffortOptions(id: String): List<BackendOption> = when (id) {
    "claude-app" -> listOf(BackendOption("", "default")) +
        claudeAppEfforts.ifEmpty { listOf("low", "medium", "high", "xhigh", "max") }
            .map { BackendOption(it, it) }
    "codex-app" -> {
        val model = codexAppModel
        val levels = codexAppEffortsByModel[model]?.takeIf { it.isNotEmpty() }
            ?: codexAppEfforts.ifEmpty { listOf("low", "medium", "high", "xhigh") }
        val resolvedDefault = codexAppDefaultEffort.ifBlank {
            codexAppModelDefaultEfforts[model].orEmpty()
        }
        listOf(
            BackendOption(
                id = "",
                label = if (resolvedDefault.isBlank()) "default" else "default · $resolvedDefault",
            )
        ) + levels.map { BackendOption(it, it) }
    }
    // v2 katalogu da variants tasiyor (kopruye 26.09.2026'da eklendi); kademe
    // YOKSA cip zaten cizilmiyor, o yuzden dal ortak.
    "opencode2-app" -> {
        val forced = opencodeDefaultVariantFor(id, opencodeFamily(id).model)
        listOf(
            BackendOption(
                id = "",
                label = if (forced.isBlank()) "varsayılan" else "varsayılan · $forced",
                detail = if (forced.isBlank()) "" else "config bu modeli $forced ile çalıştırıyor",
            )
        ) + opencodeVariantsFor(id, opencodeFamily(id).model).map { BackendOption(it, it) }
    }
    // OMP'de de efor MODELE BAGLI (deepseek-flash low/high/max, v4-pro
    // high/max, qwen3-max hic). Statik 7 kademelik liste modelin desteklemedigi
    // seviyeleri gosteriyordu — kullanici bildirdi.
    "omp" -> {
        val modelDefault = ompDefaultVariantFor(ompModel)
        listOf(
            BackendOption(
                id = "",
                label = if (modelDefault.isBlank()) "varsayılan" else "varsayılan · $modelDefault",
                detail = if (modelDefault.isBlank()) "" else "bu model varsayılanda $modelDefault ile koşar",
            )
        ) + ompVariantsFor(ompModel).map { BackendOption(it, it) }
    }
    "cowork" -> backendEffortOptions(normalizeCoworkProvider(coworkProvider))
    else -> emptyList()
}

// Ajan pill'i: turu KIM kosacak. Ilk secenek her zaman "otomatik" — kopru o
// durumda modele gore karar verir (yerel/RunPod modellerinde yalin ajan).
// Bugun yalniz opencode'da var; digerlerinde bos liste doner ve cip cizilmez.
fun RemoteUiState.backendAgentOptions(id: String): List<BackendOption> = when (id) {
    // "otomatik" satırının açıklaması köprünün O ANKİ kararını da söyler
    // ("şu an: yerel"); karar modele bağlı ve başka türlü görünmüyor.
    "opencode2-app" -> listOf(BackendOption("", "otomatik", otomatikAjanDetayi(id))) +
        opencodeFamily(id).agents.map {
            BackendOption(
                id = it.name,
                label = it.name,
                // Ajanin kendi modeli varsa aciklamaya ekle: secilince oturum
                // modeli de degisecek, kullanici bunu ONCEDEN gorsun.
                detail = listOfNotNull(
                    it.description.takeIf { d -> d.isNotBlank() },
                    it.model.takeIf { m -> m.isNotBlank() }?.let { m -> "model: $m" },
                ).joinToString(" · "),
            )
        }
    "cowork" -> if (isOpencodeFamily(normalizeCoworkProvider(coworkProvider))) backendAgentOptions(normalizeCoworkProvider(coworkProvider)) else emptyList()
    else -> emptyList()
}

fun RemoteUiState.backendAgentSupported(id: String): Boolean = when (id) {
    "opencode2-app" -> true
    "cowork" -> isOpencodeFamily(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/**
 * "Degisiklikler" gorunumu var mi — GET /<b>/diff kayitli mi.
 *
 * opencode-app ve codex-app: kaynaklari ayri (opencode'da `serve`un tur basina
 * diff'i, codex'te akan fileChange item'lari) ama KOPRU IKISINI DE AYNI semaya
 * ceviriyor, o yuzden ekran tek. claude-app/omp/agy'de karsiligi yok. Cowork
 * dali agent/skill dallariyla AYNI: cowork bir sunum katmani, ucu secili
 * saglayicinin ucu.
 */
fun RemoteUiState.backendDiffSupported(id: String): Boolean = when (id) {
    // opencode2: v2'nin diff'i SON TURUN dosyalarini verir (aralik diff'i
    // 2.0.16'da HTTP 400, kopruden olculdu) — sema v1'le ayni, ekran tek.
    "opencode2-app", "codex-app" -> true
    "cowork" -> normalizeCoworkProvider(coworkProvider) in setOf("opencode2-app", "codex-app")
    else -> false
}

/**
 * Ekranin cizecegi diff kaydi — hangi backend'in kutusundan okunacagi BURADA
 * karara baglaniyor.
 *
 * Ayri bir yardimci SART: kok, sheet govdesine `uiState.opencode.diff` yazsaydi
 * codex sekmesinde satir gorunur, sheet acilir ve HEP bos kalirdi. Gorunurluk
 * ([backendDiffSupported]), yonlendirme (`loadBackendDiff`) ve okuma ayni
 * kimlik kumesine bakmak zorunda.
 */
fun RemoteUiState.backendDiff(id: String): BackendSessionDiff? = when (id) {
    "opencode2-app" -> opencodeFamily(id).diff
    "codex-app" -> codex.diff
    // Ozyineleme guvenli: normalizeCoworkProvider asla "cowork" dondurmuyor,
    // her zaman somut bir saglayici kimligine indiriyor.
    "cowork" -> backendDiff(normalizeCoworkProvider(coworkProvider))
    else -> null
}

fun RemoteUiState.backendDiffLoading(id: String): Boolean = when (id) {
    "opencode2-app" -> opencodeFamily(id).diffLoading
    "codex-app" -> codex.diffLoading
    "cowork" -> backendDiffLoading(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/**
 * Checkpoint geri sarma var mi — GET /<b>/checkpoints + POST /<b>/revert.
 *
 * "Bu mesaja don" (rewind) HER backend'de var ve gorunumu geri sariyor; bu
 * AYRI bir sey: kimlikli bir noktaya donuluyor, dosyalar da sarilabiliyor ve
 * donus GERI ALINABILIYOR. Bugun yalniz opencode-app (kopru kontratinda da
 * `sessionRevert` yalniz orada true). Cowork dali diff/agent dallariyla AYNI.
 */
fun RemoteUiState.backendRevertSupported(id: String): Boolean = when (id) {
    // opencode2'de liste + revert + unrevert VAR. 26.09.2026'da "v2 unrevert
    // edemiyor" diye not dusulmustu; o olcum COMMIT EDILMIS bir geri sarma
    // uzerindeydi. Kopru artik commit etmiyor (stage'de birakiyor), boylece
    // DELETE /revert konusmayi da dosyalari da geri getiriyor (30.09.2026).
    "opencode2-app" -> true
    "cowork" -> isOpencodeFamily(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/**
 * Alt-ajan kartlari var mi — snapshot'taki `subagents` + GET
 * /<b>/subagent-conversation.
 *
 * 30.09.2026'dan beri HICBIR backend'de yok: tek kaynagi v1'in `opencode serve`
 * cocuk oturumlariydi ve v1 sokuldu; v2 API'sinde alt-ajan konusmasi yok
 * (kopru kontratinda da `subagents` her yerde false). Fonksiyon DURUYOR cunku
 * arayuz kart yiginini bu kapiyla ciziyor; ileride bir backend getirirse tek
 * satir eklenir.
 */
fun RemoteUiState.backendSubagentsSupported(id: String): Boolean = false

/**
 * Oturum paylasimi var mi — POST /<b>/share + /<b>/unshare.
 *
 * 30.09.2026'dan beri HICBIR backend'de yok: paylasim ucu yalniz v1'de vardi
 * (serve'un opncd.ai yayini) ve v1 sokuldu; v2 API'sinde karsiligi yok.
 * Bu satir INTERNETE yayin yapan TEK eylemi aciyordu — dokununca hicbir sey
 * yapmayan bir onay diyalogu cizmemek icin kapali kaliyor.
 */
fun RemoteUiState.backendShareSupported(id: String): Boolean = false

/**
 * Saglayicinin KENDI ozel komutlari var mi — GET /<b>/commands + POST /<b>/command.
 *
 * Statik `slashCommands`tan ayri bir kavram: o liste composer'a metin yaziyor,
 * bu liste komutu calistiriyor. Bugun yalniz opencode-app.
 */
fun RemoteUiState.backendCommandsSupported(id: String): Boolean = when (id) {
    "opencode2-app" -> true
    "cowork" -> isOpencodeFamily(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/** AGENTS.md init turu var mi — POST /<b>/init. Bugun yalniz opencode-app. */
fun RemoteUiState.backendAgentsInitSupported(id: String): Boolean = when (id) {
    // v2'de ozel "init" ucu yok; kopru bunu gercek init komutuyla kosuyor.
    "opencode2-app" -> true
    "cowork" -> isOpencodeFamily(normalizeCoworkProvider(coworkProvider))
    else -> false
}

/**
 * "/" yazilinca cizilecek oneri listesi.
 *
 * opencode'da GERCEK komut katalogu gosteriliyor (secilince CALISIYOR); diger
 * backend'lerde statik istem sablonlari (secilince composer'a YAZILIYOR). Iki
 * liste ayni seride ciziliyor ("ui2 govde odunc deseni": govde ortak, malzeme
 * degisiyor) ama davranislari ayri — bu yuzden secim eylemini de cagiran
 * [backendCommandsSupported] ile ayirmali.
 *
 * opencode'da liste bossa BOS doner: statik tabloya DUSMUYOR, cunku o tablodaki
 * satirlar calistirilamaz ve "secilen komut kosar" sozunu bozardi.
 */
fun RemoteUiState.backendSlashSuggestions(id: String): List<SlashCommand> =
    if (backendCommandsSupported(id)) opencodeFamily(komutSaglayicisi(id)).commands else slashCommands

/** Komut katalogunu kim sagliyor — cowork'te alttaki saglayici, yoksa kendisi. */
private fun RemoteUiState.komutSaglayicisi(id: String): String =
    if (id == "cowork") normalizeCoworkProvider(coworkProvider) else id

/**
 * Cipte gorunen ad. Acik secimde secilen ajan; oto kipte "otomatik · <ajan>".
 *
 * Oto kipte ajani KOPRU seciyor (modele gore: yerel/RunPod modellerinde yalin
 * ajan, digerlerinde opencode varsayilani) ve bu karar telefonda baska hicbir
 * yerde gorunmuyordu — cip yalnizca "otomatik" yazip susuyordu. Kopru karari
 * `resolvedAgent` alaninda bildiriyor; bos olmasi "kopru henuz bilmiyor" demek
 * (ajan listesi okunmadi) ve o durumda eski davranisa donuluyor: tahmin
 * uydurmaktansa yalniz "otomatik".
 */
fun RemoteUiState.backendAgentLabel(id: String): String {
    if (!backendAgentSupported(id)) return ""
    // Aile secimi SART: v2 sekmesindeyken v1'in ajani yazilirdi.
    val aile = opencodeFamily(id)
    return when {
        aile.agent.isNotBlank() -> aile.agent
        aile.resolvedAgent.isNotBlank() -> "otomatik · ${aile.resolvedAgent}"
        else -> "otomatik"
    }
}

/**
 * Ajan sheet'indeki "otomatik" satirinin aciklamasi.
 *
 * "Su an" YALNIZ oto kip acikken yazilir: acik secimde `resolvedAgent` secimin
 * kendisini tasiyor ve o satirda gosterilirse "otomatige donersen bu kosar"
 * diye okunurdu — koprunun oto kipte ne sececegini ise o an bilmiyoruz.
 */
internal fun RemoteUiState.otomatikAjanDetayi(backendId: String? = null): String {
    val aile = opencodeFamily(backendId)
    return if (aile.agent.isBlank() && aile.resolvedAgent.isNotBlank()) {
        "modele göre köprü seçer · şu an: ${aile.resolvedAgent}"
    } else "modele göre köprü seçer"
}

// Skill pill'i: backend'in KENDI kurulu skill'leri (ad + kısa açıklama).
// Köprü açıklamaları vermiyorsa (eski sürüm) düz ad listesine düşülür, böylece
// pill boş görünmez. Cowork'te aktif sağlayıcının listesi gösterilir.
fun RemoteUiState.backendSkillOptions(id: String): List<BackendOption> {
    fun merge(details: List<SkillInfo>, names: List<String>): List<BackendOption> =
        if (details.isNotEmpty()) details.map { BackendOption(it.name, it.name, it.description) }
        else names.map { BackendOption(it, it) }
    return when (id) {
        "claude-app" -> merge(claudeAppInfo?.skillDetails.orEmpty(), claudeAppInfo?.skills.orEmpty())
        "codex-app" -> merge(codexAppInfo?.skillDetails.orEmpty(), codexAppInfo?.skills.orEmpty())
        // v2 kendi IZOLE XDG kokundeki skill'leri gosterir (kopru /api/skill'i
        // okuyor); v1'in ~/.config/opencode + ~/.claude/skills listesiyle ayni
        // olmak ZORUNDA DEGIL, o yuzden aile ayri okunuyor.
        "opencode2-app" -> opencodeFamily(id).info
            .let { merge(it?.skillDetails.orEmpty(), it?.skills.orEmpty()) }
        "omp" -> merge(omp.info?.skillDetails.orEmpty(), omp.info?.skills.orEmpty())
        "cowork" -> backendSkillOptions(normalizeCoworkProvider(coworkProvider))
        else -> emptyList()
    }
}

// Skill'i KATEGORI klasorlerine bolen tek backend kaldirilinca
// (1 Agu 2026) gruplu skill kalmadi. Fonksiyon duruyor cunku arayuz rozet
// cizerken cagiriyor ve ileride baska bir backend gruplama getirirse tek
// dokunulacak yer burasi olsun — bugun her zaman bos doner.
fun RemoteUiState.backendSkillGroups(id: String): Map<String, String> = emptyMap()

// agy'de Agent Skills yok; pill hiç çizilmez.
fun RemoteUiState.backendSkillsSupported(id: String): Boolean = when (id) {
    // omp: skill envanteri RPC'nin get_available_commands çıktısından geliyor
    // (12 Ağu 2026); liste dolduğu hâlde çip gate'e takılıp görünmez kalıyordu.
    "claude-app", "codex-app", "opencode2-app", "omp" -> true
    "cowork" -> normalizeCoworkProvider(coworkProvider) != "agy"
    else -> false
}

fun RemoteUiState.backendEffortSupported(id: String): Boolean = when (id) {
    "claude-app", "codex-app" -> true
    // OMP'de reasoning'i olmayan model var (ör. qwen3-max): kademe yoksa cip cizilmez.
    "omp" -> ompVariantsFor(ompModel).isNotEmpty()
    // OpenCode'da efor MODELE BAGLI: deepseek-flash/pro'da var (high|max),
    // deepseek-reasoner ve yerel modellerde hic yok. Bu yuzden sabit true
    // donmuyoruz — secili modelin kendi variants listesine bakiyoruz, yoksa
    // cip hic cizilmiyor.
    "opencode2-app" -> opencodeVariantsFor(id, opencodeFamily(id).model).isNotEmpty()
    "cowork" -> when (normalizeCoworkProvider(coworkProvider)) {
        "claude-app", "codex-app" -> true
        "opencode2-app" -> opencodeVariantsFor("opencode2-app", opencode.model).isNotEmpty()
        // OMP'de de kademe modele bağlı; doğrudan backend dalıyla aynı ölçüt.
        "omp" -> ompVariantsFor(ompModel).isNotEmpty()
        else -> false
    }
    else -> false
}

/** Secili opencode modelinin efor kademeleri; kopruden gelen katalogtan okunur. */
fun RemoteUiState.opencodeVariantsFor(modelId: String): List<String> =
    opencodeVariantsFor(Backend.OPENCODE2_APP.id, modelId)

/** Aynisinin backend ailesine gore okuyan bicimi (v1/v2 ayri katalog). */
fun RemoteUiState.opencodeVariantsFor(backendId: String?, modelId: String): List<String> =
    opencodeFamily(backendId).availableModels.firstOrNull { it.id == modelId }?.variants.orEmpty()

/** Config'te zorlanmis efor (varsa). Bos = model kendi varsayilaniyla kosar. */
fun RemoteUiState.opencodeDefaultVariantFor(modelId: String): String =
    opencodeDefaultVariantFor(Backend.OPENCODE2_APP.id, modelId)

/** Aynisinin backend ailesine gore okuyan bicimi (v1/v2 ayri katalog). */
fun RemoteUiState.opencodeDefaultVariantFor(backendId: String?, modelId: String): String =
    opencodeFamily(backendId).availableModels.firstOrNull { it.id == modelId }?.defaultVariant.orEmpty()

/** Secili OMP modelinin efor kademeleri; kopru RPC katalogundan okuyor. */
fun RemoteUiState.ompVariantsFor(modelId: String): List<String> =
    omp.availableModels.firstOrNull { it.id == modelId }?.variants.orEmpty()

/** Modelin kendi varsayilan kademesi (OMP `thinking.defaultLevel`). Bos = OMP karar verir. */
fun RemoteUiState.ompDefaultVariantFor(modelId: String): String =
    omp.availableModels.firstOrNull { it.id == modelId }?.defaultVariant.orEmpty()

fun RemoteUiState.backendEffortLabel(id: String): String {
    val session = backendSession(id)
    if (session.effort.isNotBlank()) return session.effort
    val provider = if (id == "cowork") normalizeCoworkProvider(coworkProvider) else id
    // Config'te bir efor ZORLANMISSA cipte onu goster. "varsayilan" yazmak
    // yaniltici olurdu: kullanici max'a zorladigi halde cip varsayilan der,
    // ayar tutmadi sanir (kullanici bildirdi).
    if (provider == "opencode2-app") {
        return opencodeDefaultVariantFor(provider, opencodeFamily(provider).model)
            .ifBlank { "varsayılan" }
    }
    // OMP'de "varsayılan" seçiliyken modelin hangi kademeyle koştuğunu çipte
    // göster — codex'teki "default·high" ile aynı gerekçe (kullanıcı isteği).
    if (provider == "omp") {
        val modelDefault = ompDefaultVariantFor(ompModel)
        return if (modelDefault.isBlank()) "varsayılan" else "varsayılan·$modelDefault"
    }
    if (provider != "codex-app") return "default"
    val resolved = codexAppDefaultEffort.ifBlank {
        codexAppModelDefaultEfforts[codexAppModel].orEmpty()
    }
    return if (resolved.isBlank()) "default" else "default·$resolved"
}

fun RemoteUiState.activeProviderMonogramId(): String = when (backend) {
    // Ham provider degeri normalize edilir; aksi halde bos/eski degerde
    // monogram "?" olurdu (denetim duzeltmesi, 12.07.2026).
    "cowork" -> normalizeCoworkProvider(coworkProvider)
    null -> ""
    else -> backend
}
