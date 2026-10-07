package com.agent.bridge.ui2.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.VideoViewerState
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import java.io.File

/**
 * Uygulama içi video oynatıcı (gezgindeki mp4'ler ve diğer klipler).
 *
 * Kararlar:
 * - Kaynak HER ZAMAN telefonun diskindeki dosya. Köprüden akış yapmak ExoPlayer'a
 *   Authorization başlığı taşımayı gerektirirdi; klipler küçük olduğu için önce
 *   indirip yerelden oynatmak hem basit hem de ikinci izlemede anında açılıyor.
 * - Oynatıcı `DisposableEffect` ile bırakılır. Salınmayan bir ExoPlayer codec ve
 *   ses odağı tutar; ekran değiştikçe biriktiğinde başka videolar açılmaz olur.
 * - Kendi kontrol çubuğunu çizmiyoruz: PlayerView'ın kontrolleri (oynat/duraklat,
 *   sürgü, tam ekran) zaten erişilebilirlik ve dokunma hedefleri açısından doğru.
 */
@Composable
fun VideoViewer(
    state: VideoViewerState,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    when {
        state.error.isNotBlank() -> EmptyState(
            title = "Video açılamadı",
            description = state.error,
            icon = Icons.Outlined.Movie,
            modifier = modifier,
        )
        state.loading -> VideoLoading(state, modifier)
        state.localPath.isBlank() -> EmptyState(
            title = "Video yok",
            description = "Oynatılacak dosya bulunamadı.",
            icon = Icons.Outlined.Movie,
            modifier = modifier,
        )
        else -> Column(modifier.fillMaxSize()) {
            VideoActions(state, actions)
            VideoSurface(state.localPath, Modifier.fillMaxWidth())
        }
    }
}

/** PDF/görsel görüntüleyicilerle aynı şerit — dördünün dili ayrışmasın. */
@Composable
private fun VideoActions(state: VideoViewerState, actions: RemoteViewModel) {
    // Videoda gezinme tuşu yok (oynatıcı kendi sürüyor); sol kutup boş, hepsi sağda.
    ViewerActionStrip(Modifier.padding(bottom = Ui2Tokens.s8)) {
        // Telefondaki dosyayı telefona indirmek anlamsız.
        if (!state.local) {
            ImageCompactIconAction(Icons.Outlined.Download, "Telefona indir") {
                actions.downloadOpenedVideo()
            }
        }
        ImageCompactIconAction(Icons.AutoMirrored.Filled.OpenInNew, "Birlikte aç") {
            actions.openOpenedVideoExternally()
        }
        ImageCompactIconAction(Icons.Default.Share, "Paylaş") { actions.shareOpenedVideo() }
    }
}

@Composable
private fun VideoLoading(state: VideoViewerState, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(Ui2Tokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Boyut biliniyorsa oranlı çubuk: bekleme süresini söyleyen tek ipucu.
        if (state.progress >= 0f) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            state.name.ifBlank { "Video" } + " indiriliyor…",
            style = MaterialTheme.typography.bodySmall,
            color = Ui2.colors.ink2,
        )
    }
}

/**
 * Tek bir yerel dosyayı oynatan yüzey. `path` değişince oynatıcı yeniden kurulur;
 * aynı oynatıcıya yeni medya vermek yerine sıfırlamak, önceki videonun son
 * karesinin yeni videonun üstünde asılı kalmasını engelliyor.
 */
@Composable
fun VideoSurface(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
            // Kısa klipler tekrar tekrar izleniyor; döngü varsayılan.
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(modifier.aspectRatio(16f / 9f)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
