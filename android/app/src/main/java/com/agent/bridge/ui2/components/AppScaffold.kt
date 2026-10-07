package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2

// Kök alanlar: Sohbet · Operasyonlar · Merkez · Ayarlar. Uygulama Sohbet köküne
// açılır; landing kavramı yok. Enum saf tutulur (route/başlık) ki unit
// testte compose bağımlılığı olmadan kullanılabilsin — ikonlar aşağıda.
//
// SIRA ANLAMLIDIR: hem alt navın hem geniş ekran rail'in dizilişi budur, ve
// forRoute ilk eşleşeni döndürdüğü için Operations ("hub/operations") Hub'dan
// ("hub") ÖNCE gelmek zorunda — tersi olsaydı önek eşleşip Merkez sanılırdı.
// navTitle: alt nav/rail etiketi. Ekran başlığından AYRI olmasının sebebi ölçü:
// 5 tuşla hücre 1280px ekranda ~256px'e iniyor ve "Operasyonlar" ~272px sürüyor
// — son harfi alt satıra düşüyordu. Kısaltma yalnız navigasyonda, ekranın kendi
// başlığı tam ("Operasyonlar").
enum class RootArea(val route: String, val title: String, val navTitle: String = title) {
    Chat("chat", "Sohbet"),
    Operations("hub/operations", "Operasyonlar", navTitle = "Operasyon"),
    Hub("hub", "Merkez"),
    Settings("settings", "Ayarlar");

    companion object {
        // Ayarların alt rotaları kök Ayarlar alanına bağlanır.
        fun forRoute(route: String?): RootArea = values().firstOrNull {
            route == it.route || route?.startsWith(it.route + "/") == true
        } ?: Chat
    }
}

// Geniş yerleşimde miyiz? Karar TEK yerde (AppScaffold'un ölçtüğü genişlik)
// veriliyor ve buradan yayılıyor; jest gibi başka davranışlar da aynı kaynağı
// okusun diye. LocalConfiguration.screenWidthDp ile ayrıca ölçmek çok pencereli
// / DeX kipinde rail ile jestin FARKLI karar vermesine yol açar — pencere ekran
// genişliğinden dar olabiliyor.
val LocalWideLayout = staticCompositionLocalOf { false }

private fun rootIcon(area: RootArea, selected: Boolean): ImageVector = when (area) {
    RootArea.Chat -> if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
    // Terminal ikonunun dolu karşılığı yok; Merkez'deki satırda da bu ikon vardı.
    RootArea.Operations -> Icons.Outlined.Terminal
    RootArea.Hub -> if (selected) Icons.Filled.GridView else Icons.Outlined.GridView
    RootArea.Settings -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}

// Kök kabuk: içerik + alt navigasyon. Onay bekleyen oturum alt navdaki
// Sohbet ikonunda kehribar noktayla HER YERDEN görünür (anayasa 6).
// onArchive: en soldaki Oturumlar tuşu — kök alan DEĞİL, eylem: oturum geçmişi
// çekmecesini açar/kapar (Sohbet köküne geçip çekmeceyi tetikler).
@Composable
fun AppScaffold(
    current: RootArea,
    onSelect: (RootArea) -> Unit,
    chatAttention: Boolean = false,
    // Operasyonlar tuşundaki kehribar nokta: Merkez'deki satırda duran uyarı
    // (bekleyen oturum / görülmemiş olay) satır kaldırılınca buraya taşındı.
    operationsAttention: Boolean = false,
    onArchive: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        val wide = maxWidth >= 840.dp
        CompositionLocalProvider(LocalWideLayout provides wide) {
        if (wide) {
            // Geniş ekranda Scaffold yok; telefonda Scaffold'un content padding'iyle
            // gelen sistem çubuğu inset'leri burada elle geçilir. Boş PaddingValues
            // geçmek TÜM ekranları status bar'ın altına taşırıyordu (tablet, 10.92).
            val systemBarPadding = WindowInsets.systemBars.asPaddingValues()
            Row(Modifier.fillMaxSize()) {
                // Rail SAĞDA: tablet ve telefon yatayken cihaz iki elle tutuluyor ve
                // gezinme başparmağın altına düşsün isteniyor (kullanıcı kararı 13.08.2026).
                // M3'ün varsayılan rail inset'i Start tarafını koruyor; sağa alınca
                // yatay jest çubuğu/çentik tuşların üstüne binmesin diye End'e çevrildi.
                Box(Modifier.weight(1f)) { content(systemBarPadding) }
                // 112dp'de tuşların iki yanında ölü alan kalıyordu (tablet, 13.08.2026).
                // En uzun etiket "Oturumlar"; 88dp onu tek satırda taşıyor, M3'ün 80dp
                // varsayılanına inmek etiketi kırpma sınırına getiriyordu.
                NavigationRail(
                    containerColor = Ui2.colors.navBg,
                    modifier = Modifier.width(88.dp),
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
                ) {
                    // Gezinme tuşları üstte grup, Ayarlar tek başına en altta — klasik
                    // masaüstü kalıbı (kullanıcı kararı 10.99: tam dağıtım fazla boşluklu
                    // bulundu). Üstte nefes payı, tuşlar arasında ferah sabit aralık.
                    Spacer(Modifier.height(16.dp))
                    if (onArchive != null) {
                        NavigationRailItem(
                            selected = false,
                            onClick = onArchive,
                            icon = { Icon(Icons.Outlined.History, "Oturumlar") },
                            label = { RailLabel("Oturumlar") },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    val areas = RootArea.values()
                    areas.forEachIndexed { index, area ->
                        if (index == areas.lastIndex) Spacer(Modifier.weight(1f))
                        val selected = area == current
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onSelect(area) },
                            icon = { RootIcon(area, selected, chatAttention, operationsAttention) },
                            label = { RailLabel(area.navTitle) },
                        )
                        if (index < areas.lastIndex - 1) Spacer(Modifier.height(12.dp))
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = Ui2.colors.bg,
                bottomBar = {
                    // Kompakt alt nav: M3 NavigationBar'ın 80dp sabit gövdesi altta
                    // gereksiz alan bırakıyordu. navigationBarsPadding tuşları jest
                    // çubuğunun ÜSTÜNDE tutar (üstüne binmez/ezmez), gövde ~52dp.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Ui2.colors.navBg)
                            .navigationBarsPadding()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onArchive != null) {
                            CompactNavItem(
                                selected = false,
                                onClick = onArchive,
                                label = "Oturumlar",
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Outlined.History, "Oturumlar") }
                        }
                        RootArea.values().forEach { area ->
                            val selected = area == current
                            CompactNavItem(
                                selected = selected,
                                onClick = { onSelect(area) },
                                label = area.navTitle,
                                modifier = Modifier.weight(1f),
                            ) { RootIcon(area, selected, chatAttention, operationsAttention) }
                        }
                    }
                },
                content = content,
            )
        }
        }
    }
}

// Rail etiketi. Rail daraldığı için tek satır ZORUNLU: etiket alt satıra taşarsa
// tuşun yüksekliği değişip tüm sütun kayıyor (alt navda ölçülen davranışın aynısı).
@Composable
private fun RailLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// Kompakt alt nav tuşu: ikon + etiket dikey, seçiliyse accent tonu.
@Composable
private fun CompactNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val tint = if (selected) Ui2.colors.accent else Ui2.colors.ink3
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { icon() }
        // Tek satır ZORUNLU: etiket sığmazsa alt satıra taşıp tuşun yüksekliğini
        // değiştiriyor ve tüm çubuk kayıyordu (canlı: "Operasyonlar"ın son harfi).
        // navTitle zaten kısaltılmış; bu, ileride uzun etiket eklenirse ağ.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootIcon(area: RootArea, selected: Boolean, chatAttention: Boolean, operationsAttention: Boolean = false) {
    val showDot = (area == RootArea.Chat && chatAttention) ||
        (area == RootArea.Operations && operationsAttention)
    if (showDot) {
        BadgedBox(badge = { Badge(containerColor = Ui2.colors.attention) }) {
            Icon(rootIcon(area, selected), area.title)
        }
    } else {
        Icon(rootIcon(area, selected), area.title)
    }
}
