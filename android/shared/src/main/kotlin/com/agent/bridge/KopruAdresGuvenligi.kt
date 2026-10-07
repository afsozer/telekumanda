package com.agent.bridge

import java.net.URI

/**
 * Köprü adresi düz `http://` ve Tailscale dışı bir ağa mı gidiyor?
 *
 * Engellemez, yalnız uyarı içindir (kullanıcı kararı): yerel ağda düz HTTP
 * köprüsü meşru bir kurulum, ama token ve oturum içeriği o ağda şifresiz akar.
 *
 * Uyarı GEREKMEYEN düz HTTP hedefleri:
 * - Tailscale: `100.64.0.0/10` (CGNAT aralığı, 100.64.x.x – 100.127.x.x) ve
 *   MagicDNS adları (`*.ts.net`). Trafik WireGuard tünelinde zaten şifreli.
 * - Cihazın kendisi: `localhost`, `127.0.0.1`, `::1`.
 *
 * Kısa MagicDNS adı (yalnız makine adı, nokta yok) burada Tailscale SAYILMAZ:
 * aynı ad yerel ağın DNS'inden de çözülebilir ve adresten hangisi olduğu
 * anlaşılmaz. `https://` her zaman uyarısızdır; ayrıştırılamayan adres de
 * (henüz yazılıyor) uyarı üretmez.
 */
fun duzHttpUyarisiGerekli(adres: String): Boolean {
    val temiz = adres.trim()
    if (temiz.isEmpty()) return false
    val uri = runCatching { URI(temiz) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val host = uri.host?.trim()?.trimStart('[')?.trimEnd(']')?.lowercase()?.trimEnd('.')
    if (host.isNullOrEmpty()) return false
    return !guvenliDuzHttpHedefi(host)
}

private fun guvenliDuzHttpHedefi(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    if (host == "ts.net" || host.endsWith(".ts.net")) return true
    return tailscaleIpv4(host)
}

// 100.64.0.0/10: ilk sekizli 100, ikincinin üst iki biti 01 → 64..127.
private fun tailscaleIpv4(host: String): Boolean {
    val parcalar = host.split('.')
    if (parcalar.size != 4) return false
    val sayilar = parcalar.map { p ->
        if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false
        p.toInt().takeIf { it in 0..255 } ?: return false
    }
    return sayilar[0] == 100 && sayilar[1] in 64..127
}

/** Ayarlarda adres alanının altında gösterilen uyarı metni. */
const val DUZ_HTTP_UYARISI =
    "Bu adres şifresiz HTTP kullanıyor ve Tailscale ağında değil: token ve oturum içeriği " +
        "bu ağda şifrelenmeden gider. Mümkünse Tailscale adresini (100.x.x.x ya da *.ts.net) " +
        "veya https kullan."
