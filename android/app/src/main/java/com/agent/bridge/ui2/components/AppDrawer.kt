package com.agent.bridge.ui2.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlinx.coroutines.launch

// Çekmece kabı — anayasa 2: TEK örnek (oturum çekmecesi); yenisi eklenmez.
// Mockup v1 ekran 4: %86 genişlik, sağ kenar yuvarlak, yüzey tonu.
// İçerik slotu ekrandan gelir (Faz 2c); kap deseni burada sabitlenir.
//
// Geniş ekran (≥600dp, tablet/yatay): ModalNavigationDrawer KULLANILMAZ.
// BOM 2024.06'nın (m3 1.2.1) AnchoredDraggable'ı dev fraksiyonel sheet'lerde
// iki anchor arasında takılı kalabiliyordu — çekmece yarı açık donuyor, scrim
// tıklanamıyor, kapanmıyordu. Geniş ekranda sabit 360dp panel + basit scrim
// çizilir; DrawerState anchor'sız sürülür (open/close anında currentValue'ya
// yazar), takılacak sürükleme mekanizması yoktur.
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    drawerContent: @Composable ColumnScope.() -> Unit,
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints {
        if (maxWidth >= 600.dp) {
            WideDrawerPanel(drawerState, drawerContent, content)
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = gesturesEnabled,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = Ui2.colors.surface,
                        drawerContentColor = Ui2.colors.ink,
                        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth(0.86f),
                    ) {
                        DrawerColumn(drawerContent)
                    }
                },
                content = content,
            )
        }
    }
}

// Geniş ekran paneli: içerik + (açıkken) scrim + sağdan kayan sabit panel.
// Scrim'e dokunmak kapatır; görünürlük targetValue'dan okunur ki open()/close()
// çağrısıyla animasyon aynı karede başlasın.
@Composable
private fun WideDrawerPanel(
    drawerState: DrawerState,
    drawerContent: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val open = drawerState.targetValue == DrawerValue.Open
    Box(Modifier.fillMaxSize()) {
        content()
        val scrimAlpha by animateFloatAsState(if (open) 0.5f else 0f, label = "drawerScrim")
        if (scrimAlpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .pointerInput(drawerState) {
                        detectTapGestures { scope.launch { drawerState.close() } }
                    }
            )
        }
        // SAĞDAN açılır: geniş ekranda çekmeceyi açan "Oturumlar" tuşu nav rail'le
        // birlikte sağa taşındı; soldan açmak dokunuşu ekranın karşı kenarına
        // gönderiyordu. Yuvarlatma da kenar değiştirdi (dış kenar düz kalır).
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                color = Ui2.colors.surface,
                contentColor = Ui2.colors.ink,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                modifier = Modifier.fillMaxHeight().width(360.dp),
            ) {
                DrawerColumn(drawerContent)
            }
        }
    }
}

@Composable
private fun DrawerColumn(drawerContent: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Ui2Tokens.s12, vertical = Ui2Tokens.s16),
        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        content = drawerContent,
    )
}
