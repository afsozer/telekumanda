package com.agent.bridge.ui3.chat

import android.view.View

/**
 * ÖDÜNÇ `AndroidView`LERİN ODAĞINI BIRAK — sekme değiştirmeden ÖNCE.
 *
 * 19.08.2026 canlı çökmesi: birkaç sekme geçişinden sonra uygulama
 * `IllegalStateException: LayoutNode should be attached to an owner` ile
 * kapanıyordu. Zinciri yığın izinden okumak mümkün:
 *
 *   ViewGroup.removeViewInternal → rootViewRequestFocus →
 *   AndroidComposeView.requestFocus → iki boyutlu odak araması →
 *   LazyLayoutBeyondBoundsProviderModifierNode → forceRemeasure
 *
 * Yani: odağı TUTAN bir Android View (markdown'ın Markwon `TextView`i) tam
 * `applyChanges` sırasında ağaçtan çıkarılınca, Android çerçevesi "odak
 * kimde kalacak" diye kökten arama başlatıyor. Arama tembel listenin sınır
 * ötesi sağlayıcısına düşüp ölçümü ZORLUYOR — ama o an düğümler ağaçtan
 * kopmuş durumda. Compose/AndroidView birlikte çalışmasının bilinen bir
 * kenar durumu; sekme geçişi bütün mesaj listesini bir kerede söktüğü için
 * jestle sık sık tetiklenir hale geldi.
 *
 * `removeViewInternal` bu aramayı YALNIZCA sökülen view odağı tutuyorsa
 * yapıyor. O yüzden çare basit: sökmeden önce odağı bırak.
 *
 * Compose'un kendi odağına (composer alanı) dokunulmuyor — o odak
 * `AndroidComposeView`in ÜSTÜNDE durur, sökülen bir çocuk değildir.
 */
internal fun View.ui3OduncOdagiBirak() {
    val odakli = findFocus() ?: return
    if (odakli !== this) odakli.clearFocus()
}
