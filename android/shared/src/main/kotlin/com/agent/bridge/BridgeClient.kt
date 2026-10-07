package com.agent.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BridgeSettings(val baseUrl: String, val token: String)
data class PairingStartResult(val code: String, val expiresAt: String)
data class DeviceKeyResult(val deviceId: String, val key: String)

data class SystemMetrics(val cpu: Int = 0, val memory: Int = 0, val platform: String = "", val uptime: Long = 0L)
data class ModuleHealth(val status: String = "", val pid: Int? = null, val sessionCount: Int = 0)
data class ActiveStateResult(
    val running: Boolean,
    val backend: String,
    val sessionId: String,
    val title: String,
    val modules: Map<String, ModuleHealth> = emptyMap(),
    val system: SystemMetrics? = null
)
data class NotificationPollResult(
    val pending: Boolean,
    val backend: String = "",
    val sessionId: String = "",
    val summary: String = "",
    val eventId: String = "",
    val events: List<OperationEvent> = emptyList(),
    val pushEvents: List<PushEvent> = emptyList(),
)
data class HealthResult(val ok: Boolean, val protocolVersion: Int = 0, val protocolMinClient: Int = 0) {
    val compatible: Boolean get() = protocolVersion in 2..2 && protocolMinClient <= 2
}
data class ChatMessage(val role: String, val text: String, val time: String = "", val thoughtIndex: Int = -1, val rowId: String = "")

data class ConversationResult(
    val text: String,
    val messages: List<ChatMessage>,
    val running: Boolean,
    val awaitingApproval: Boolean,
    val approval: ApprovalInfo? = null,
    val awaitingFirstOutput: Boolean = false,
    val choices: List<String> = emptyList(),
    val contextTokens: Int = 0,
    val contextWindow: Int = 0,
    val permissionMode: String = "",
    val effort: String = "",
    val cost: Double = 0.0,
    val availableModels: List<BackendModel> = emptyList(),
    val permissionModes: List<PermissionMode> = emptyList(),
    val commands: List<SlashCommand> = emptyList(),
    val planDraft: String = "",
    val interruptStuck: Boolean = false,
    // Codex oturum hedefi. goalPresent: poll cevabinda "goal" alani var miydi (eski
    // koprude yok -> mevcut hedefe dokunma). goal null iken goalPresent=true = temizle.
    val goalPresent: Boolean = false,
    val goal: CodexGoal? = null,
    // GÖREV PANOSU (opencode). Soket kapalıyken panonun tek kaynağı bu yol;
    // üç-durum ayrımı akıştakiyle aynı (bkz. parseTodoArray / parseContextPct).
    val todos: List<OpencodeTodo>? = null,
    val contextPctPresent: Boolean = false,
    val contextPct: Int? = null,
    // Checkpoint geri sarma durumu (opencode). Üç-durum: alan hiç yoksa
    // (eski köprü) mevcut duruma DOKUNMA, alan var null ise şeridi kaldır.
    val revertedPresent: Boolean = false,
    val reverted: OpencodeRevertState? = null,
    // ALT AJANLAR (opencode). Soket kapalıyken kart yığınının tek kaynağı bu
    // yol; null = karede alan yok (eski köprü) -> mevcut listeye dokunma.
    val subagents: List<OpencodeSubagent>? = null,
    // Oturum paylaşım linki (opencode). null = alan yok (eski köprü) -> dokunma;
    // "" = yayında değil. Soket kapalıyken menü satırının tek kaynağı bu yol.
    val share: String? = null,
    // Oto kipte turu koşacak ajan (opencode). null = alan yok (eski köprü) ->
    // dokunma; "" = köprü henüz bilmiyor. Soket kapalıyken göstergenin tek
    // kaynağı bu yol.
    val resolvedAgent: String? = null,
)

data class ApprovalInfo(
    val requestId: String = "",
    val kind: String = "",
    val tool: String = "",
    val summary: String = "",
    val description: String = "",
    val decisionReason: String = "",
    val options: List<ApprovalOption> = emptyList(),
    val questions: List<ApprovalQuestion> = emptyList(),
)

data class ApprovalQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<ApprovalOption>,
    val multiple: Boolean = false,
    val custom: Boolean = false,
)

data class ApprovalOption(val id: String, val label: String, val description: String = "", val consequence: String = "")
data class ApprovalAnswer(val questionId: String, val optionId: String, val label: String)

data class CoworkProject(val name: String, val path: String, val activeProvider: String = "", val updatedAt: String = "", val matter: String = "")
// /cowork/projects cevabı: root = CoworkSpaces kökü (cowork dosya gezgini kök kilidi için).
data class CoworkProjectsPage(val root: String, val projects: List<CoworkProject>)
data class CoworkNoteProject(val name: String, val path: String)
data class CoworkNote(
    val id: String,
    val title: String,
    val project: CoworkNoteProject? = null,
    val mdPath: String? = null,
    // Gövdenin tek satıra indirilmiş hâli: kartta başlığın altındaki gri satır.
    // Arama sonuçlarında köprü bunu eşleşmenin çevresiyle değiştirir.
    val preview: String = "",
    // Dolu ise not ekran görüntüsü boru hattından doğmuş; değeri kaynak
    // görüntünün dosya adı. Notlarım'daki "otomatik / elle" süzgeci bunu okur.
    val sourceScreenshot: String = "",
    // Hatırlatıcı (docs/ekran-goruntusu-hatirlatici-plani.md): reminderAt boşsa
    // hatırlatıcı yok. State: pending | sent | failed.
    val reminderAt: String? = null,
    val reminderState: String? = null,
    val reminderAttempts: Int = 0,
    val updatedAtMs: Long = 0,
)
data class CoworkNotesPage(val root: String, val notes: List<CoworkNote>)
data class CoworkNoteAiPreview(
    val noteId: String,
    val action: String,
    val title: String,
    val mdPath: String,
    val original: String,
    val proposed: String,
    val baseHash: String,
    val provider: String,
)
data class CoworkProviderCatalog(
    val id: String,
    val label: String,
    val defaultModel: String,
    val models: List<BackendModel>,
)
data class CoworkSessionRecord(
    val provider: String,
    val sessionId: String,
    val threadId: String = "",
    val cwd: String = "",
    val model: String = "",
    val permissionMode: String = "",
    val permissionModeExplicit: Boolean = false,
    val title: String = "",
    val lastText: String = "",
    val createdAt: String = "",
    val lastUsedAt: String = "",
)
data class CoworkStartResult(
    val provider: String,
    val apiBackend: String,
    val sessionId: String,
    val model: String,
    val project: CoworkProject?,
    val outputs: List<CoworkOutput> = emptyList(),
)
data class CoworkImportResult(val ok: Boolean, val copied: Int, val errors: List<String>)
data class CoworkArchiveResult(val ok: Boolean, val path: String, val name: String, val size: Long, val error: String = "")
data class CoworkHandoffResult(val path: String, val fromProvider: String, val toProvider: String, val messageCount: Int)
data class CoworkProjectDetails(
    val project: CoworkProject?,
    val sessions: List<CoworkSessionRecord> = emptyList(),
    val outputs: List<CoworkOutput> = emptyList(),
)
// Kurulu skill: ad + SKILL.md frontmatter'ından gelen kısa açıklama. Açıklama
// boş olabilir (CLI'ın bildirdiği ama diskte kullanıcı dizininde bulunmayan
// eklenti/yerleşik skill'ler); ad her zaman doludur. group bugün hiçbir
// dolu gelir: orada skill'ler kategori klasörlerine bölünmüştür.
data class SkillInfo(val name: String, val description: String = "", val group: String = "")

data class ClaudeAppInfo(
    val model: String = "",
    val permissionMode: String = "",
    val version: String = "",
    val tools: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val slashCommands: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val skillDetails: List<SkillInfo> = emptyList(),
    val plugins: List<String> = emptyList(),
    val memoryPaths: List<String> = emptyList(),
    val models: List<ClaudeModel> = emptyList(),
    val permissionModes: List<String> = emptyList(),
)

data class PlannerConversationResult(
    val messages: List<ChatMessage>,
    val running: Boolean,
    val planRunning: Boolean,
    val executorRunning: Boolean,
    val plan: String,
    val executorResult: String,
    val awaitingApproval: Boolean = false,
)

data class SessionInfo(val title: String, val time: String, val project: String = "")
data class SlashCommand(val name: String, val desc: String, val hint: String = "")
data class SlashResult(val commands: List<SlashCommand>)
data class UsageBucket(
    val id: String,
    val label: String,
    val window: String,
    val description: String,
    val remainingFraction: Double,
    val resetTime: String,
    val value: String = "",
    val metered: Boolean = true,
    /** Ön ödemeli bakiye kartlarında (Nano-GPT, DeepSeek, OpenRouter) USD tutar; kota kovalarında null. */
    val creditUsd: Double? = null,
)

/** Köprüyle aynı ölçek: 10 $ = dolu çubuk. Üstündeki bakiye "bol" sayılır, çubuk ayrı renkle çizilir. */
const val CREDIT_BAR_FULL_USD = 10.0

fun UsageBucket.creditAbundant(): Boolean = (creditUsd ?: 0.0) > CREDIT_BAR_FULL_USD
data class UsageGroup(val name: String, val description: String, val buckets: List<UsageBucket>, val source: String = "", val stale: Boolean = false)
data class UsageResult(val groups: List<UsageGroup>, val note: String)
data class FileResult(
    val ok: Boolean,
    val name: String,
    val content: String,
    val truncated: Boolean,
    val error: String,
    val path: String = "",
    val size: Long = 0,
    val mtime: Long = 0,
    val hash: String = "",
)
/**
 * Okuma modu çıkarımının sonucu (`GET /pdf-read`).
 *
 * [pageStarts] her sayfanın [markdown] içindeki karakter konumudur: metin akışa
 * girince sayfa sınırı görsel olarak kaybolur, ama hukuki belgede "3. sayfada
 * geçiyor" demek gerekiyor — eşleme veri olarak taşınır.
 *
 * [reason] yalnız [ok] false iken doludur; "taranmis" özel bir değer, çağıran
 * onu hata değil "bu belgede okuma modu yok" olarak gösterir.
 */
data class PdfReadResult(
    val ok: Boolean,
    val markdown: String = "",
    val pageStarts: List<Int> = emptyList(),
    val pageCount: Int = 0,
    val truncated: Boolean = false,
    val tablesTruncated: Boolean = false,
    val reason: String = "",
)
/**
 * Köprüde tutulan "kaldığı yer" (`/pdf-position`).
 *
 * Uygulama modülündeki `PdfResumePosition` ile aynı alanları taşır ama ayrı
 * duruyor: shared modül Android'e ve masaüstüne ortak, oradaki tür ise okuyucu
 * ekranının iç modeli. Çeviri ViewModel'de, tek yerde.
 *
 * [savedAt] hakem alan: çakışmada son yazan kazanır.
 */
data class BridgePdfPosition(
    val page: Int,
    val ri: Int = 0,
    val ro: Int = 0,
    val mode: String = "p",
    val savedAt: Long = 0L,
)
/**
 * Okuma modu dil kontrolünün tek bulgusu.
 *
 * [alinti] sayfada AYNEN geçen parçadır (köprü metinde bulunmayan alıntıyı
 * eler); kullanıcı bulguyu sayfada bulabilsin diye.
 * [tur]: yazim | noktalama | anlatim | tutarsizlik.
 *
 * Bu bir ÖNERİDİR, uygulanmaz. Hukuki evrakta yazım hatası belgenin parçasıdır.
 */
data class LangFinding(
    val tur: String,
    val alinti: String,
    val oneri: String,
    val not: String = "",
)
data class LangCheckResult(
    val ok: Boolean,
    val bulgular: List<LangFinding> = emptyList(),
    val reason: String = "",
)
data class UploadedFile(val name: String, val path: String)
data class UploadResult(val ok: Boolean, val name: String, val path: String, val error: String)
data class FileSaveResult(
    val ok: Boolean,
    val name: String,
    val path: String,
    val error: String,
    val conflict: Boolean = false,
    val size: Long = 0,
    val mtime: Long = 0,
    val hash: String = "",
)
// /<provider>/models yanıtını sarmalar: model listesi + bridge'in tek kaynaktan verdiği defaultModel (madde 7).
data class ModelsResult<T>(val models: List<T>, val defaultModel: String = "")
data class ClaudeModel(val label: String, val id: String)
// forks: bu satırın temsil ettiği transcript kopyası sayısı. CLI bir oturumu devam
// ettirirken geçmişi yeni uuid'li dosyaya kopyalar; köprü bunları tek satırda
// toplar (claude-app.mjs collapseForks). 1 = çatal yok.
data class ClaudeDiskSession(val id: String, val cwd: String, val title: String, val lastText: String, val turns: Int, val mtime: Long, val pinned: Boolean = false, val archived: Boolean = false, val forks: Int = 1)
data class McpServer(val name: String, val enabled: Boolean, val type: String, val url: String, val command: String, val args: List<String>, val status: String = "", val managed: Boolean = false)
data class CodexModel(val label: String, val id: String)
data class CodexDiskSession(val id: String, val cwd: String, val title: String, val lastText: String, val turns: Int, val mtime: Long, val pinned: Boolean = false, val archived: Boolean = false)
// Codex çaba (effort) seçenekleri + "default"un config.toml'da çözüldüğü değer (boş olabilir).
// effortsByModel: model başına desteklenen küme (gpt-5.6 minimal desteklemez);
// modelDefaultEfforts: config.toml boşken modelin kendi default'u (UI "default·low" gösterir).
data class CodexAppEffortInfo(
    val levels: List<String>,
    val defaultEffort: String,
    val effortsByModel: Map<String, List<String>> = emptyMap(),
    val modelDefaultEfforts: Map<String, String> = emptyMap(),
)
// Bellekteki (canl?) backend oturumu ? /{backend}/sessions listesinden. Backend'e geri
// dönünce kaldığımız oturuma yeniden bağlanmak için kullanılır.
data class LiveSession(
    val id: String, val cwd: String, val model: String, val status: String,
    val awaitingApproval: Boolean = false, val lastText: String = "",
    val title: String = "",
    // Codex'te canlı kabuk id'si disk/thread id'sinden farklı olabilir.
    // Rename gibi disk-kimlikli işlemleri açık sekmeyle eşlemek için taşınır.
    val threadId: String = "",
)
// Feature 10: Codex App info
data class CodexAppInfo(val codexVersion: String = "", val appServer: Boolean = false, val features: Map<String, Boolean> = emptyMap(), val skills: List<String> = emptyList(), val skillDetails: List<SkillInfo> = emptyList())
data class OpencodeAppInfo(val serveAlive: Boolean = false, val skills: List<String> = emptyList(), val skillDetails: List<SkillInfo> = emptyList())
// Feature 4: dosya değişiklikleri artık ortak [BackendSessionDiff] ile taşınıyor
// (`GET /codex-app/diff`) — codex'e özel liste tipi kaldırıldı; şemayı köprü
// opencode'unkiyle aynı biçimde veriyor ve telefonda tek gövde çiziyor.
// Feature 5: Command timeline item
data class CodexCommand(val id: String = "", val command: String = "", val cwd: String = "", val status: String = "", val stdout: String = "", val stderr: String = "", val exitCode: Int = 0, val startedAt: Long = 0, val completedAt: Long = 0)
// Feature 1: Fork result
data class CodexForkResult(val ok: Boolean, val sessionId: String = "", val threadId: String = "", val cwd: String = "", val model: String = "")
// Feature 6: Permission mode item
data class PermissionModeItem(val id: String, val label: String)
data class DirEntry(val name: String, val path: String, val type: String = "dir", val size: Long = 0, val mtime: Long = 0)
data class WorkerDirs(val ok: Boolean, val base: String, val parent: String = "", val dirs: List<DirEntry>)
data class CompleteProjectDeleteResult(
    val ok: Boolean = false,
    val path: String = "",
    val projectId: String = "",
    val phase: String = "",            // "sessions" | "folder" | "registry" | "complete"
    val folderDeleted: Boolean = false,
    val registryDeleted: Boolean = false,
    val deletedSessionCount: Int = 0,
    val deletedSessionIds: Set<String> = emptySet(),
    val removedProjectIds: List<String> = emptyList(),
    val sessionResults: List<DeleteSessionResult> = emptyList(),
    val error: String = "",
    val retryable: Boolean = false,
)

data class DeleteSessionResult(
    val backend: String,
    val sessionId: String,
    val ok: Boolean,
    val error: String,
)

data class ProjectDeleteUiState(
    val running: Boolean = false,
    val targetName: String = "",
    val targetId: String = "",
    val result: CompleteProjectDeleteResult? = null,
)
data class RunPodStatus(
    val ok: Boolean = true,
    val enabled: Boolean = false,
    val phase: String = "unknown",
    val ready: Boolean = false,
    val action: String = "",
    val step: String = "",
    val message: String = "",
    val podStatus: String = "",
    val tunnel: Boolean = false,
    val tunnelPid: Int? = null,
    val operationActive: Boolean = false,
    val billingAvailable: Boolean = false,
    val clientBalance: Double? = null,
    val currentSpendPerHr: Double? = null,
    val spendLimit: Double? = null,
)

/**
 * OpenCode silinmis oturum kalintilarinin durumu (`purge/status`, `purge/run`).
 * Not: yol onekini yorumda yildizla yazmayin — Kotlin ic ice yorum aciyor.
 *
 * opencode oturumu silince icerik gitmiyor: SQLite freelist sayfalari ve
 * checkpoint edilmemis -wal cerceveleri mesaj metnini aynen tutuyor.
 * [deadActive] uygulamanin kullandigi veritabanlarindaki iz sayisi — imha
 * tusu bunlari aliyor. [deadArchived] eski/kopya dosyalarin icindeki
 * sohbetler; onlar yalniz masaustunden `-EskiDb` ile gidiyor, bu yuzden
 * ikisi TOPLANMAZ.
 */
data class SessionPurgeStatus(
    val ok: Boolean = true,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val message: String = "",
    val deadActive: Int = 0,
    val deadArchived: Int = 0,
    // Son imhanin dogrulama sonucu; hic kosulmadiysa null.
    val clean: Boolean? = null,
    val lastRunAt: String = "",
    val lines: List<String> = emptyList(),
)

data class ProcInfo(val pid: Int, val name: String, val startTime: String?, val elapsedSec: Int, val cmdline: String)
data class KillResult(val ok: Boolean, val killed: List<Int>, val errors: List<Any>, val cleared: Int = 0)
// Rewind (mesaja geri dön) cevabı. claude-app transcript-fork yaptığından yeni
// sessionId dönebilir.
data class RewindResult(val ok: Boolean, val sessionId: String, val partial: Boolean, val error: String)
data class ProcessListResult(val processes: List<ProcInfo>, val count: Int)
data class AgyModel(val label: String, val id: String)
data class AgyDiskSession(val id: String, val cwd: String, val title: String, val lastText: String, val turns: Int, val mtime: Long, val source: String = "", val sourceLabel: String = "")
// variants: modelin akil yurutme eforu kademeleri (opencode'da "variant").
// Modele gore degisir — deepseek-flash/pro'da high|max, digerlerinde bos.
// Sabit liste tutulmaz, kopruden gelir.
// defaultVariant: config'te zorlanmis efor (model.options.reasoningEffort).
// Bossa model kendi varsayilaniyla kosar.
data class BackendModel(
    val label: String,
    val id: String,
    val variants: List<String> = emptyList(),
    val defaultVariant: String = "",
    // Etiketin ALTINDA, saglayicinin yaninda cizilen aciklama (or. "GLM 5.3
    // Flash (NanoGPT, 1M)"). Kopru veriyorsa dolu; vermiyorsa satir yalniz
    // saglayiciyi gosterir — uydurulmaz.
    val detail: String = "",
)
data class AppDiskSession(
    val id: String,
    val cwd: String,
    val title: String,
    val lastText: String,
    val turns: Int,
    val mtime: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)
data class PermissionMode(val id: String, val name: String = "", val description: String = "")

// OpenCode ajani: turu kim kosacak. build/plan yerlesik (builtIn), digerleri
// ~/.config/opencode/agent/*.md dosyalarindan gelir. model: ajanin KENDI modeli
// (varsa); secilince oturum modeli de ona cekilir, cunku kopru her mesajda
// modeli gonderiyor ve o alan ajanin modelini ezer.
data class BackendAgent(
    val name: String,
    val description: String = "",
    val mode: String = "",
    val builtIn: Boolean = false,
    val model: String = "",
)
data class OperationItem(
    val id: String,
    val backend: String,
    val backendLabel: String,
    val sessionId: String,
    val cwd: String,
    val model: String,
    val summary: String,
    val status: String,
    val needsAttention: Boolean,
    val updatedAt: String,
    // Oturumun kendi basligi (ilk gercek kullanici mesaji). Bos olabilir —
    // eski bridge surumleri bu alani gondermez, kart backendLabel'a duser.
    // Konumsal cagiranlari bozmamak icin sonda ve varsayilanli.
    val title: String = "",
    // Kalici oturum kimligi (orn. opencode ses_...). sessionId kopru kabugunu
    // gosterir ve kabuk oldugunde cozulmez olur; acarken bu tercih edilir.
    val diskId: String = "",
)
data class OperationEvent(
    val id: String,
    val kind: String,
    val status: String,
    val backend: String,
    val backendLabel: String,
    val sessionId: String,
    val cwd: String,
    val model: String,
    val summary: String,
    val at: String,
    val title: String = "",
    val diskId: String = "",
)
data class OperationCounts(val running: Int = 0, val waiting: Int = 0, val failed: Int = 0)
data class OperationsResult(
    val operations: List<OperationItem> = emptyList(),
    val events: List<OperationEvent> = emptyList(),
    val counts: OperationCounts = OperationCounts(),
)
data class ProjectSummary(
    val id: String,
    val path: String,
    val name: String,
    val displayName: String,
    val exists: Boolean,
    val sessionCount: Int,
    val runningCount: Int,
    val outputCount: Int,
    val newOutputCount: Int,
    val providers: List<String>,
    val pinned: Boolean = false,
    val lastOpenedAt: String = "",
    val lastActivityAt: String = "",
    val quickStartProvider: String = "",
    val quickStartModel: String = "",
    val quickStartPermissionMode: String = "",
    val quickStartEffort: String = "",
)
data class ProjectSession(
    val backend: String,
    val backendLabel: String,
    val sessionId: String,
    val model: String,
    val status: String,
    val summary: String,
    val title: String = "",
    val nativeSessionId: String = "",
    val mtime: Long = 0L,
    val live: Boolean = true,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val container: String = "direct",
    val threadId: String = "",
)
data class ProjectArtifact(val backend: String, val title: String, val detail: String = "")
data class ProjectDelivery(val name: String, val path: String, val size: Long, val mtime: Double, val isNew: Boolean)
data class ProjectSecurity(
    val profile: String = "standard",
    val readRoots: List<String> = emptyList(),
    val writeRoots: List<String> = emptyList(),
    val customPermissions: Map<String, String> = emptyMap(),
)
data class ProjectAuditEvent(
    val id: String,
    val action: String,
    val actor: String,
    val detail: String,
    val at: String,
)
data class ProjectMcpProfile(val provider: String = "", val enabledNames: List<String> = emptyList())
data class ProjectDetail(
    val project: ProjectSummary? = null,
    val sessions: List<ProjectSession> = emptyList(),
    val outputs: List<ProjectDelivery> = emptyList(),
    val changes: List<ProjectArtifact> = emptyList(),
    val commands: List<ProjectArtifact> = emptyList(),
    val plan: List<ProjectArtifact> = emptyList(),
    val security: ProjectSecurity = ProjectSecurity(),
    val audit: List<ProjectAuditEvent> = emptyList(),
    val mcpProfile: ProjectMcpProfile = ProjectMcpProfile(),
)

open class BridgeClient(private val clientEdition: String = "") {
    internal val jsonType = "application/json; charset=utf-8".toMediaType()
    internal val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Sessiz/yarı-açık mobil bağlantıları bir sonraki içerik mesajını beklemeden algıla.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    // Worker client with 1.5x longer timeouts for worker operations.
    internal val workerClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    // Long poll client for background notifications
    internal val longPollClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // Okuma modu çıkarımı: PDF metin katmanı köprüde ayrıştırılır ve süre SAYFA
    // SAYISIYLA büyür — 150 sayfalık bir kitap ölçümde ~11 sn, 400 sayfalık tavan
    // bunun katı. Varsayılan 15 sn'lik okuma zaman aşımı bu işi ortasında keserdi.
    // Köprü sonucu önbelleğe aldığı için bu bekleme yalnız ilk açılışta yaşanır.
    internal val docClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    // Tek seferlik not AI işlemi sağlayıcı yanıtını bekleyebilir.
    internal val aiClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(210, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Dosya yükleme: gövde MB'larca olabiliyor ve yazma yönünde akıyor.
    // 14.08.2026'da 46 MB'lık bir PDF gezginden iki kez üst üste "timeout" verdi.
    // Sebep: `client` connect/read ayarlıyor ama writeTimeout'a HİÇ dokunmuyor,
    // yani OkHttp'nin varsayılanı olan 10 sn'de kalıyordu — 46 MB'ı 10 saniyede
    // yollamak ~37 Mbit/s sürekli hız demek, telefondan Tailscale üzerinden
    // gerçekçi değil. Küçük yüklemelerin (ekran görüntüsü arşivi, birkaç yüz KB)
    // sorunsuz çalışması da bunu doğruluyordu.
    // Yazma bütçesi cömert; okuma kısa tutuldu çünkü gövde gittikten sonra köprü
    // yalnız diske yazıp JSON döndürüyor.
    internal val uploadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    open suspend fun getJson(settings: BridgeSettings, path: String): JSONObject =
        executeJson(buildRequest(settings, path).get().build())

    open suspend fun getJsonLongPoll(settings: BridgeSettings, path: String): JSONObject =
        executeJsonLongPoll(buildRequest(settings, path).get().build())

    open suspend fun postJson(settings: BridgeSettings, path: String, body: JSONObject = JSONObject()): JSONObject =
        executeJson(buildRequest(settings, path).post(body.toString().toRequestBody(jsonType)).build())

    open suspend fun postJsonAi(settings: BridgeSettings, path: String, body: JSONObject = JSONObject()): JSONObject =
        executeJsonAi(buildRequest(settings, path).post(body.toString().toRequestBody(jsonType)).build())

    open fun buildRequest(settings: BridgeSettings, path: String): Request.Builder {
        val builder = Request.Builder().url(normalizeBase(settings.baseUrl) + path)
        if (settings.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${settings.token}")
        }
        // Turu HANGİ cihazın başlattığını köprü buradan öğreniyor: iş bitince
        // bildirim yalnız o cihaza gidiyor (kullanıcı şikayeti 23.08.2026 —
        // tabletten prompt verirken yandaki telefon da ötüyordu).
        //
        // Neden `Build.MODEL` ve neden HEADER: köprü bildirim kaydında cihazın
        // modelini de tutuyor (NotificationRoute), yani model prompt isteğini
        // kayıtlı cihaza bağlayan ortak alan. Header olması da altı ayrı prompt
        // çağrı yerine tek tek eklemekten kurtarıyor. Gerekçenin tamamı
        // köprüdeki `prompt-origin.mjs` başında.
        if (cihazModeli.isNotBlank()) builder.header("X-Device-Model", cihazModeli)
        if (clientEdition.isNotBlank()) builder.header("X-AgentBridge-Edition", clientEdition)
        return builder
    }

    // NEDEN YANSIMA (reflection): bu modül SAF JVM kitaplığı — Android SDK'sına
    // derlenmiyor (`:shared:compileKotlin`, `compileDebugKotlin` değil), yani
    // `android.os.Build` derleme zamanında YOK. Çalışma zamanında ise cihazda
    // her zaman var (boot classpath). Tek bir statik alan okunuyor ve `lazy`
    // olduğu için süreç başına bir kez. JVM testlerinde sınıf bulunamaz ve boş
    // döner — köprü de boş modeli "bilmiyorum" diye okuyup eski davranışa düşer.
    protected open val cihazModeli: String by lazy {
        runCatching {
            (Class.forName("android.os.Build").getField("MODEL").get(null) as? String).orEmpty().trim()
        }.getOrDefault("")
    }

    open suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { it.readJsonOrThrow() }
    }

    open suspend fun executeJsonLongPoll(request: Request): JSONObject = withContext(Dispatchers.IO) {
        longPollClient.newCall(request).await().use { it.readJsonOrThrow() }
    }

    open suspend fun executeJsonAi(request: Request): JSONObject = withContext(Dispatchers.IO) {
        aiClient.newCall(request).await().use { it.readJsonOrThrow() }
    }

    open suspend fun executeJsonUpload(request: Request): JSONObject = withContext(Dispatchers.IO) {
        uploadClient.newCall(request).await().use { it.readJsonOrThrow() }
    }

    open suspend fun getJsonDoc(settings: BridgeSettings, path: String): JSONObject =
        withContext(Dispatchers.IO) {
            docClient.newCall(buildRequest(settings, path).get().build()).await().use { it.readJsonOrThrow() }
        }

    open suspend fun postJsonDoc(settings: BridgeSettings, path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val request = buildRequest(settings, path).post(body.toString().toRequestBody(jsonType)).build()
            docClient.newCall(request).await().use { it.readJsonOrThrow() }
        }

    // Worker variants with 1.5x longer timeouts for OpenCode operations
    open suspend fun getJsonWorker(settings: BridgeSettings, path: String): JSONObject =
        executeJsonWorker(buildRequest(settings, path).get().build())

    open suspend fun postJsonWorker(settings: BridgeSettings, path: String, body: JSONObject = JSONObject()): JSONObject =
        executeJsonWorker(buildRequest(settings, path).post(body.toString().toRequestBody(jsonType)).build())

    open suspend fun executeJsonWorker(request: Request): JSONObject = withContext(Dispatchers.IO) {
        workerClient.newCall(request).await().use { it.readJsonOrThrow() }
    }

    open fun normalizeBase(url: String): String = url.trim().trimEnd('/')
}

/**
 * Köprünün HTTP hatası. [serverMessage] gövdedeki `error` alanıdır.
 *
 * Neden var: istemci non-2xx'te gövdeyi ATIYOR ve yalnız "HTTP 400" fırlatıyordu.
 * Köprü hatanın SEBEBİNİ zaten gövdede yazıyor ("thread henüz başlamadı — önce
 * bir mesaj gönder" gibi) ama kullanıcı yalnız kodu görüyordu. Bu tuzak iki kez
 * ısırdı: 05.08.2026'da cowork kilidinde (409), 06.08.2026'da Codex hedefinde
 * (400) — ikisinde de asıl açıklama gövdedeydi.
 *
 * [code] alanı sayısal karşılaştırma içindir; hata metnini "HTTP 409" gibi
 * dizgelerle karşılaştırmak artık gerekmiyor (o kırılgan desen buradan doğmuştu).
 */
class BridgeHttpException(
    val code: Int,
    val serverMessage: String = "",
) : IOException(serverMessage.ifBlank { "HTTP $code" })

/** Gövdeyi okur; non-2xx'te sunucunun kendi açıklamasıyla [BridgeHttpException] fırlatır. */
internal fun Response.readJsonOrThrow(): JSONObject {
    val raw = body?.string().orEmpty()
    val parsed = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
    if (!isSuccessful) {
        // Gövde JSON değilse (proxy/HTML hata sayfası) düz metnin ilk satırı da
        // koddan iyidir; ama çok uzunsa kullanıcıya taşımayız.
        val fromJson = parsed?.optString("error").orEmpty()
        val fallback = raw.trim().lineSequence().firstOrNull().orEmpty().take(200)
        throw BridgeHttpException(code, fromJson.ifBlank { if (parsed == null) fallback else "" })
    }
    return parsed ?: JSONObject()
}

internal fun JSONObject.optNullableInt(name: String): Int? =
    if (isNull(name) || !has(name)) null else optInt(name)

fun parseMessages(json: JSONObject): List<ChatMessage> {
    val array = json.optJSONArray("messages") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("text")
            val thoughtIndex = item.optInt("thoughtIndex", -1)
            // Boş metinli satırı yalnız thought değilse at: thinking satırları text=""
            // + thoughtIndex taşır ve WS yolu (parseStreamRow) bunları hep tutar. Burada
            // atılınca sunucu limit'i ham satırla saydığı için messagesList.size hep
            // messagePageSize'ın altında kalıyor ve "Daha eskiyi göster" tuşu hiç
            // çıkmıyordu; ayrıca HTTP/WS listeleri farklı boyda oluyordu.
            if (text.isNotBlank() || thoughtIndex >= 0) {
                add(
                    ChatMessage(
                        role = item.optString("role", "agent"),
                        text = text,
                        time = item.optString("time"),
                        thoughtIndex = thoughtIndex,
                        rowId = item.optString("rowId"),
                    ),
                )
            }
        }
    }
}

/**
 * Görev panosunun todo dizisi. null = KAREDE ALAN YOK (eski köprü ya da
 * değişmemiş delta) — çağıran mevcut listeye dokunmamalı. Boş liste ise gerçek
 * bir "liste temizlendi" bilgisi; ikisini karıştırmak panoyu koşu ortasında
 * boşaltırdı.
 *
 * İçerik doğrulaması köprüde yapılıyor (durum normalize, boş madde eleme); burada
 * yalnız çözümleme var, ikinci bir kural seti kurulmuyor.
 */
internal fun parseTodoArray(arr: org.json.JSONArray?): List<OpencodeTodo>? {
    if (arr == null) return null
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val content = obj.optString("content")
            if (content.isNotBlank()) {
                add(OpencodeTodo(content, obj.optString("status", "pending"), obj.optString("priority", "")))
            }
        }
    }
}

/** contextPct'in üç durumu: alan yok / alan var ama null / sayı. */
internal fun parseContextPct(json: JSONObject): Int? =
    if (!json.has("contextPct") || json.isNull("contextPct")) null else json.optInt("contextPct")

/**
 * Alt-ajan kartları. parseTodoArray ile aynı üç-durum sözleşmesi: null = karede
 * alan yok, boş liste = gerçekten alt-ajan yok. Kimliksiz kayıt ELENİR — kart
 * kimliksizken transkript isteği kurulamaz, tıklanan ölü bir kart olurdu.
 */
internal fun parseSubagentArray(arr: org.json.JSONArray?): List<OpencodeSubagent>? {
    if (arr == null) return null
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            add(
                OpencodeSubagent(
                    id = id,
                    title = obj.optString("title").ifBlank { "Alt ajan" },
                    status = obj.optString("status", "idle"),
                    lastText = obj.optString("lastText", ""),
                    turns = obj.optInt("turns", 0),
                    agent = obj.optString("agent", ""),
                ),
            )
        }
    }
}

/** Alt-ajan transkripti (salt-okunur sheet). Geçersiz cevap null döner. */
internal fun parseSubagentTranscript(json: JSONObject): OpencodeSubagentTranscript? {
    val childId = json.optString("childId")
    if (childId.isBlank()) return null
    val arr = json.optJSONArray("messages")
    val lines = buildList {
        for (i in 0 until (arr?.length() ?: 0)) {
            val obj = arr?.optJSONObject(i) ?: continue
            val text = obj.optString("text")
            if (text.isBlank()) continue
            add(OpencodeSubagentLine(obj.optString("role", "agent"), text))
        }
    }
    return OpencodeSubagentTranscript(
        childId = childId,
        title = json.optString("title").ifBlank { "Alt ajan" },
        agent = json.optString("agent", ""),
        status = json.optString("status", "idle"),
        messages = lines,
        truncated = json.optBoolean("truncated", false),
    )
}

/**
 * Snapshot/poll yükündeki `reverted` nesnesi. null = geri sarılmış nokta yok.
 * `messageID` boşsa kayıt anlamsız — şerit çizilmesin diye null'a düşülüyor.
 */
internal fun parseRevertState(json: JSONObject): OpencodeRevertState? {
    val obj = json.optJSONObject("reverted") ?: return null
    val id = obj.optString("messageID")
    if (id.isBlank()) return null
    return OpencodeRevertState(
        messageID = id,
        filesReverted = obj.optBoolean("filesReverted", false),
        files = obj.optInt("files", 0),
    )
}

internal fun parseStringArray(arr: org.json.JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            add(arr.optString(i, ""))
        }
    }
}

// skillDetails: [{name, description}] — köprü eski sürümdeyse alan hiç gelmez,
// o durumda liste boş kalır ve çağıran düz `skills` adlarına düşer.
internal fun parseSkillInfoArray(arr: org.json.JSONArray?): List<SkillInfo> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isNotBlank()) {
                add(SkillInfo(name, obj.optString("description"), obj.optString("group")))
            }
        }
    }
}

internal fun parseNamedArray(arr: org.json.JSONArray?, includeStatus: Boolean = false): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            } else {
                val name = obj.optString("name")
                    .ifBlank { obj.optString("display_name") }
                    .ifBlank { obj.optString("label") }
                    .ifBlank { obj.optString("id") }
                if (name.isNotBlank()) {
                    val status = obj.optString("status")
                    add(if (includeStatus && status.isNotBlank()) "$name: $status" else name)
                }
            }
        }
    }
}

internal fun parseApproval(obj: JSONObject?): ApprovalInfo? {
    if (obj == null) return null
    return ApprovalInfo(
        requestId = obj.optString("requestId"),
        kind = obj.optString("kind"),
        tool = obj.optString("tool"),
        summary = obj.optString("summary"),
        description = obj.optString("description"),
        decisionReason = obj.optString("decisionReason"),
        options = parseApprovalOptions(obj.optJSONArray("options")),
        questions = parseApprovalQuestions(obj.optJSONArray("questions")),
    ).takeIf {
        it.requestId.isNotBlank() || it.kind.isNotBlank() || it.tool.isNotBlank() || it.summary.isNotBlank() || it.description.isNotBlank() || it.decisionReason.isNotBlank() || it.options.isNotEmpty() || it.questions.isNotEmpty()
    }
}

private fun parseApprovalOptions(arr: org.json.JSONArray?): List<ApprovalOption> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val opt = arr.optJSONObject(i) ?: continue
            add(ApprovalOption(
                id = opt.optString("id"),
                label = opt.optString("label"),
                description = opt.optString("description", opt.optString("desc", "")),
                consequence = opt.optString("consequence", ""),
            ))
        }
    }
}

private fun parseApprovalQuestions(arr: org.json.JSONArray?): List<ApprovalQuestion> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val options = q.optJSONArray("options")?.let { optArr ->
                buildList {
                    for (j in 0 until optArr.length()) {
                        val opt = optArr.optJSONObject(j) ?: continue
                        val label = opt.optString("label")
                        if (label.isNotBlank()) {
                            add(ApprovalOption(opt.optString("id", label), label, opt.optString("description")))
                        }
                    }
                }
            }.orEmpty()
            add(
                ApprovalQuestion(
                    id = q.optString("id", "q${i + 1}"),
                    header = q.optString("header"),
                    question = q.optString("question"),
                    options = options,
                    multiple = q.optBoolean("multiple", false),
                    custom = q.optBoolean("custom", false),
                ),
            )
        }
    }
}

internal fun parseBackendModels(arr: org.json.JSONArray?): List<BackendModel> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            add(BackendModel(
                        item.optString("label"),
                        item.optString("id"),
                        item.optJSONArray("variants")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                        } ?: emptyList(),
                        item.optString("defaultVariant"),
                        item.optString("detail"),
                    ))
        }
    }
}

internal fun parsePermissionModes(arr: org.json.JSONArray?): List<PermissionMode> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            if (item != null) {
                add(PermissionMode(
                    item.optString("id"),
                    item.optString("name", item.optString("id")),
                    item.optString("description", ""),
                ))
            } else {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(PermissionMode(it, it)) }
            }
        }
    }
}

internal fun parseSlashCommands(arr: org.json.JSONArray?): List<SlashCommand> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val hintObj = item.optJSONObject("input")
            add(SlashCommand(
                name = item.optString("name", ""),
                desc = item.optString("description", ""),
                hint = hintObj?.optString("hint", "") ?: "",
            ))
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
