package com.agent.bridge.ui2.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Sohbetin soldan-sağa çekmece jestinin YATAY kaydırılabilir içerikle (kod
 * bloğu, tablo, görsel şeridi) çakışmaması için bölge kaydı (kullanıcı isteği
 * 05.08.2026: "kod bloklarında yana kayarken çekmece açılmasın").
 *
 * Neden tüketim (isConsumed) kontrolü değil: jest handler'ı Initial geçişte
 * dinlemek ZORUNDA — sohbet yüzeyi Markwon TextView'larıyla kaplı ve onlar
 * dokunma akışını tüketiyor; tüketime bakan bir kural düz mesaj metninin
 * üstünde jesti tamamen öldürürdü (bu bug bir kez yaşandı ve Initial'a bu
 * yüzden geçildi). Bunun yerine kaydırılabilir bileşenler pencere-koordinatlı
 * sınırlarını buraya yazar; jest, başlangıç noktası bu bölgelerden birindeyse
 * hiç başlamaz.
 */
class ChatSwipeExclusions {
    private val rects = HashMap<Any, Rect>()

    fun update(key: Any, boundsInWindow: Rect) {
        rects[key] = boundsInWindow
    }

    fun remove(key: Any) {
        rects.remove(key)
    }

    fun contains(pointInWindow: Offset): Boolean = rects.values.any { it.contains(pointInWindow) }
}

/** ChatRootScreen sağlar; sohbet dışı ekranlarda (dosya görüntüleyici vb.) null. */
val LocalChatSwipeExclusions = staticCompositionLocalOf<ChatSwipeExclusions?> { null }

/**
 * Yatay kaydırılabilir bileşene eklenir. Sohbet bağlamı dışında (registry yok)
 * hiçbir iş yapmaz; LazyColumn geri dönüşümünde kayıt otomatik silinir.
 */
@Composable
fun Modifier.chatSwipeExclusion(): Modifier {
    val registry = LocalChatSwipeExclusions.current ?: return this
    val key = remember { Any() }
    DisposableEffect(registry, key) {
        onDispose { registry.remove(key) }
    }
    return onGloballyPositioned { registry.update(key, it.boundsInWindow()) }
}
