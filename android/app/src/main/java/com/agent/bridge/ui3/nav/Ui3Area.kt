package com.agent.bridge.ui3.nav

import com.agent.bridge.BuildConfig

/**
 * ui3'ün dock öğeleri — **ui2'nin bilgi mimarisi** (anayasa v2 bölüm 6).
 *
 * Sıra ve davranış ui2'nin alt navigasyonuyla birebir: Oturumlar / Sohbet /
 * Operasyon / Merkez / Ayarlar.
 *
 * Önceki sürüm mockup'ı izliyordu (Oturumlar yok, Dosyalar var, etiketsiz) ve
 * kullanıcı ui3'ü gerçek işte deneyince ilk şikâyet buydu: oturuma ve yeni
 * oturuma erişim zorlaştı. Mimari geri alındı; ui3'ün katkısı malzeme.
 *
 * Rota adları ui2'dekiyle aynı — bildirim yönlendirmesi ve derin bağlantı bu
 * adlara bakıyor.
 *
 * [Oturumlar] bir rota DEĞİL: ui2'de olduğu gibi bir yüzey açar (sohbette
 * değilse önce sohbete geçer, sonra oturum listesini açar). O yüzden [rota]
 * boş; kök onu ayrı ele alır.
 */
internal enum class Ui3Area(val rota: String, val etiket: String) {
    Oturumlar("", "Oturumlar"),
    Sohbet("chat", "Sohbet"),
    // Spotlight dock'ta Operasyon'un YERİNİ aldı (kullanıcı kararı, 17.08.2026):
    // operasyonlara Merkez'den giriliyor ve orada zaten bir kutusu vardı; palet
    // ise her ekrandan tek dokunuşla açılabilmeli. Oturumlar ayrı tuş olarak
    // KALDI — palet onun yerine geçmiyor, ikisi ayrı iş yapıyor.
    Spotlight("", "Spotlight"),
    Operasyon("hub/operations", "Operasyon"),
    Merkez("hub", "Merkez"),
    Ayarlar("settings", "Ayarlar");

    /** Gezinilebilir alanlar — NavHost yalnız bunlar için hedef kurar. */
    val rotali: Boolean get() = rota.isNotEmpty()

    /**
     * Dock'ta çizilir mi? Rotası olmak dock'ta olmayı GEREKTİRMEZ: Operasyon
     * gezinilebilir bir kök ama girişi Merkez'de.
     */
    val dockta: Boolean get() = if (BuildConfig.IS_LITE) {
        this == Oturumlar || this == Sohbet || this == Ayarlar
    } else this != Operasyon

    /**
     * Ekranı kendi başlığını taşıyor mu?
     *
     * [Operasyon] ui2'den ödünç alınıyor ve kendi `ScreenHeader`'ıyla geliyor;
     * ui3'ün büyük başlığı da çizilirse üst üste iki başlık olur (cihazda
     * görüldü). Merkez ve Ayarlar ui3'ün kendi ekranları, başlığı kabuk verir.
     */
    val kendiBasligi: Boolean get() = this == Operasyon

    companion object {
        /**
         * Alt rotalar (ör. `settings/mcp`) kendi kök alanına bağlanır.
         *
         * Operasyon dock'ta olmadığı için MERKEZ'e eşlenir: oraya Merkez'den
         * giriliyor, hiçbir öğe yanmasaydı kullanıcı nerede olduğunu dock'tan
         * okuyamazdı.
         */
        fun forRota(rota: String?): Ui3Area {
            val alan = entries.firstOrNull {
                it.rotali && (rota == it.rota || rota?.startsWith(it.rota + "/") == true)
            } ?: return Sohbet
            return if (alan.dockta) alan else Merkez
        }
    }
}
