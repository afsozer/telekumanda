package com.agent.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Köprü kuyruğundan (pushEvents) gelen olayı bildirime çevirir; makbuz tekrar göstermeyi önler. */
object PushNotifications {
    fun post(context: Context, event: PushEvent): Boolean = post(context, Intent().apply {
        putExtra("deliveryId", event.deliveryId)
        putExtra("kind", event.kind)
        putExtra("title", event.title)
        putExtra("summary", event.summary)
        putExtra("noteId", event.noteId)
        putExtra("backend", event.backend)
        putExtra("backendLabel", event.backendLabel)
        putExtra("sessionId", event.sessionId)
        putExtra("startedAt", event.startedAt)
        putExtra("requestId", event.requestId)
    })

    // Intent yalnız alan taşıyıcısı (eski broadcast yolundan kalma okuma kodu).
    @Synchronized
    private fun post(context: Context, intent: Intent): Boolean {
        val deliveryId = intent.getStringExtra("deliveryId").orEmpty()
        val receiptPrefs = context.getSharedPreferences("notification_receipts", Context.MODE_PRIVATE)
        val receipts = runCatching {
            val array = org.json.JSONArray(receiptPrefs.getString("ids", "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        if (deliveryId.isNotBlank() && deliveryId in receipts) return true
        // Aynı teslimat iki kez işlenmesin: kapsül de makbuzun ARDINDA, çünkü
        // tekrar oynatılan bir `started` kronometreyi sıfırlardı.
        fun makbuzKaydet() {
            if (deliveryId.isBlank()) return
            val recent = (receipts + deliveryId).takeLast(2048)
            receiptPrefs.edit().putString("ids", org.json.JSONArray(recent).toString()).commit()
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val kind = intent.getStringExtra("kind").orEmpty()
        val backend = intent.getStringExtra("backend").orEmpty()
        val backendLabel = intent.getStringExtra("backendLabel").orEmpty().ifBlank { backend }
        val sessionId = intent.getStringExtra("sessionId").orEmpty()
        val summary = intent.getStringExtra("summary").orEmpty()
        // Hatırlatıcı: köprüdeki zamanlayıcıdan gelir, bir oturuma bağlı değil,
        // bir NOTA bağlıdır (docs/ekran-goruntusu-hatirlatici-plani.md, E5.6).
        val noteId = intent.getStringExtra("noteId").orEmpty()
        val hamBaslik = intent.getStringExtra("title").orEmpty()
        val requestId = intent.getStringExtra("requestId").orEmpty()

        // Kapsül (Android 16 Live Updates, docs/kapsul-live-updates-plani.md):
        // makbuz kontrolünden SONRA — tekrar oynatma kapsülü yanlış duruma
        // sokmasın — ve `AppForeground` süzgecinden ÖNCE: uygulama önde olsa da
        // kapsül güncel kalmalı, gizleme kararı sistemin (Honor kendi uygulaması
        // öndeyken kapsülü kendisi gizliyor, SONUC2 §4). Cihaz terfi
        // edemiyorsa (API < 36 / Tab S9) denetleyici hiç bildirim atmaz.
        KapsulDenetleyici.isle(
            context, kind = kind, backend = backend, backendLabel = backendLabel,
            sessionId = sessionId, baslik = hamBaslik, ozet = summary,
            startedAt = intent.getStringExtra("startedAt").orEmpty(),
            requestId = requestId,
        )
        // `started` SESSİZ: kapsülü günceller, gölgeye satır ATMAZ. Makbuzu
        // yine de yazıyoruz ki köprünün yeniden denemesi kapsülü tekrar
        // kurcalamasın.
        if (kind == "started") {
            makbuzKaydet()
            return true
        }

        // Kullanıcı zaten o sohbete bakıyorsa bildirime gerek yok: sonuç da onay
        // kartı da ekranda. Başka sohbetteyse veya uygulama arkadaysa atılır.
        // "system" bunun dışında: makinenin kendini yeniden başlatması gibi olaylar
        // hangi sohbete bakıldığından bağımsız olarak görünmeli.
        // Hatırlatıcı da "system" gibi bastırılmaz: hangi ekranda olunduğundan
        // bağımsız görünmeli (kaçan hatırlatıcı = kaçan duruşma).
        if (kind != "system" && kind != "reminder" && kind != "note" &&
            AppForeground.suppresses(backend, sessionId)
        ) return true

        PushChannels.ensure(context)
        val title = when (kind) {
            "attention" -> "Yanıt bekliyor · $backendLabel"
            "completed" -> "Görev tamamlandı · $backendLabel"
            "failed" -> "Görev başarısız · $backendLabel"
            // Bilgisayardaki bağlantı izleyicisinin kendi başlığını taşır
            // (scripts/baglanti-izleyici.ps1); köprüden bağımsız gönderilir.
            "system" -> hamBaslik.ifBlank { "AgentBridge · Sistem" }
            // Başlık notun kendi başlığı: "Hatırlatıcı · Duruşma" gibi bir önek
            // eklemek dar bildirim satırında asıl bilgiyi kırpıyordu.
            "reminder" -> hamBaslik.ifBlank { "Hatırlatıcı" }
            // Ekran görüntüsünden not: köprü boru hattının "oldu bitti" haberi.
            "note" -> hamBaslik.ifBlank { "Ekran görüntüsünden not" }
            else -> return false
        }
        // Aciliyet: onay isteği, sistem uyarısı ve hatırlatıcı sesli/yüksek kanaldan gider.
        val urgent = kind == "attention" || kind == "system" || kind == "reminder"
        // Not bildirimi sessiz ama GÖRÜNÜR: arka planda olan bir şeyin kaydı,
        // bir uyarı değil — ses çalması gün içinde rahatsız ederdi. Sessizliği
        // kanalın sesi null olduğu için var; öncelik düşürülMEZ, düşürülünce
        // gölgenin "Sessiz" bölümüne düşüp gözden kaçıyordu (kullanıcı 07.08).
        val notKaydi = kind == "note"
        val channel = when {
            notKaydi -> PushChannels.NOTES
            urgent -> PushChannels.APPROVALS
            else -> PushChannels.EVENTS
        }
        val channelState = context.getSystemService(android.app.NotificationManager::class.java)
            .getNotificationChannel(channel)
        if (channelState?.importance == android.app.NotificationManager.IMPORTANCE_NONE) return false

        // Dokununca ilgili oturuma gider (BridgeMonitorService ile aynı extras
        // sözleşmesi; MainActivity approvalBackend/approvalSessionId'yi işler).
        // Sistem uyarısının bağlı olduğu bir oturum yok; sadece uygulamayı açar.
        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Hatırlatıcı ve not bildirimi bir NOTA bağlı: dokununca o not açılır.
        val notBagli = kind == "reminder" || kind == "note"
        if (notBagli) {
            // Dokununca ilgili not açılır; oturum yok.
            if (noteId.isNotBlank()) tapIntent.putExtra("reminderNoteId", noteId)
        } else if (kind != "system") {
            tapIntent.putExtra("approvalBackend", backend).putExtra("approvalSessionId", sessionId)
        }
        val tap = PendingIntent.getActivity(
            context,
            // Hatırlatıcıda oturum yok: istek kodu not kimliğinden türetilir,
            // yoksa hepsi 0'a düşüp birbirinin extra'sını eziyordu.
            if (notBagli) noteId.hashCode() else sessionId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Kimlik oturum+tür bazlı: aynı olayın poller kopyası öncekini değiştirir,
        // çift bildirim görünmez. Hatırlatıcıda backend/sessionId BOŞ: kimliği
        // onlardan türetirsek tüm hatırlatıcılar aynı id'ye düşer ve her yenisi
        // bir öncekini siler (iki duruşma → tek bildirim). Kimlik nota göre ayrışır.
        val tag = if (notBagli) "$kind:$noteId" else notificationKey(backend, sessionId)
        val id = if (notBagli) "$kind:$noteId".hashCode()
        else ("$backend:$sessionId:$kind").hashCode()

        // Simge türü ayırt etsin: not bildirimi kendi belge simgesini taşır.
        // Görev bildirimiyle aynı senkron simgesini kullanırken kullanıcı ikisini
        // karıştırdı (08.08.2026) — nottaki "Sil" tuşu görevde yok, yanlış
        // dokunuş pahalıya patlıyor.
        val icon = when {
            notKaydi -> R.drawable.ic_stat_note
            urgent -> android.R.drawable.ic_dialog_alert
            else -> android.R.drawable.stat_notify_sync
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            // Başlık ve özet (oturum, komut, not başlığı) kilit ekranında görünmez;
            // orada yalnız türü söyleyen içeriksiz sürüm kalır.
            .kilitEkranindaGizle(context, channel, icon, kind)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            // Otomatik üretilen notların çoğu çöp; silmek için uygulamayı açıp
            // listede satırı aramak fazla yol. YALNIZ "note"ta var: hatırlatıcı
            // bildiriminde aynı tuş, kaçırılmaması gereken bir işi tek yanlış
            // dokunuşla yok ederdi.
            .apply {
                if (kind == "attention") {
                    OnayBildirimi.eylemleriEkle(
                        this, context,
                        backend = backend,
                        sessionId = sessionId,
                        requestId = requestId,
                        istekAnahtari = "$backend:$sessionId",
                        bildirimEtiketi = tag,
                        bildirimId = id,
                    )
                }
                if (kind == "note" && noteId.isNotBlank()) {
                    val silIntent = Intent(context, NoteActionReceiver::class.java)
                        .setAction(NoteActionReceiver.ACTION_DELETE)
                        .putExtra("noteId", noteId)
                        .putExtra("title", title)
                        .putExtra("notificationTag", tag)
                        .putExtra("notificationId", id)
                    addAction(
                        android.R.drawable.ic_menu_delete,
                        "Sil",
                        PendingIntent.getBroadcast(
                            context,
                            "sil:$noteId".hashCode(),
                            silIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            .build()
        // Tag oturum bazlı: sohbete girilince o oturumun bildirimleri tag'e göre
        // toplu iptal edilir (AppForeground.clearFor).
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        }.isSuccess
        if (posted) makbuzKaydet()
        return posted
    }
}
