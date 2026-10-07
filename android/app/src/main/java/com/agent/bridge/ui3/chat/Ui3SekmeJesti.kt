package com.agent.bridge.ui3.chat

import com.agent.bridge.AppTab

/**
 * SOHBET YÜZEYİNDEKİ YATAY JESTİN KARARI — artık AÇIK SEKMELER arasında geçiş.
 *
 * 19.08.2026 kullanıcı kararı: jest, oturum panelini açmayı ve cowork dosya
 * yöneticisine gitmeyi DEVRALDI. Gerekçe okunabilir — ikisi de "başka bir yere
 * git" demekti ve ikisinin de tuşu duruyor (dock'ta Oturumlar, ⋯ menüsünde
 * Çalışma klasörü); sekme değiştirmenin ise sekme çubuğuna dokunmaktan başka
 * yolu yoktu ve o çubuk okuma sırasında ekranın en uzağında kalıyor.
 *
 * Yön ViewPager mantığında: parmak SOLA giderse sağdaki sekme gelir. Sekme
 * çubuğu soldan sağa dizildiği için "sayfa çevirme" hissi buradan çıkıyor.
 *
 * Ayrı ve saf: karar cihazda ölçmeden test edilebilsin (`Ui3SekmeJestiTest`).
 */
internal fun ui3SekmeJestYonu(mesafeX: Float, esik: Float): Int = when {
    mesafeX <= -esik -> 1
    mesafeX >= esik -> -1
    else -> 0
}

/**
 * Komşu sekmenin kimliği; yoksa null.
 *
 * SARMA YOK (baştan sona atlamaz): sekme çubuğu nerede olduğunu gösteriyor,
 * son sekmede sola çekince ilk sekmeye ışınlanmak "bir sekme kaydım" beklentisini
 * bozardı. Uçta jest sessizce hiçbir şey yapmaz.
 */
internal fun ui3KomsuSekme(sekmeler: List<AppTab>, aktifId: String, yon: Int): String? {
    if (yon == 0) return null
    val yer = sekmeler.indexOfFirst { it.id == aktifId }
    if (yer == -1) return null
    return sekmeler.getOrNull(yer + yon)?.id
}
