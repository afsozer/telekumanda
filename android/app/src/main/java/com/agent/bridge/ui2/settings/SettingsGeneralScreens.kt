package com.agent.bridge.ui2.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agent.bridge.BuildConfig
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.batteryOptimizationIgnored
import com.agent.bridge.deviceAuthLoading
import com.agent.bridge.devicePaired
import com.agent.bridge.pairingCode
import com.agent.bridge.pairingExpiresAt
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.ContentWidth
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun SettingsConnectionScreen(uiState: RemoteUiState, actions: RemoteViewModel, onBack: () -> Unit) {
    var pairingCode by remember(uiState.pairingCode) { mutableStateOf(uiState.pairingCode) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val activeProfile = uiState.bridgeProfiles.firstOrNull { it.id == uiState.activeBridgeProfileId }

    ContentWidth(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Bağlantı ve eşleme",
            subtitle = if (uiState.protocolCompatible) "Bridge adresi ve cihaz anahtarı" else "Bridge protokolü uyumsuz",
            onBack = onBack,
        )
        Column(
            Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
        ) {
            SectionHeader("Köprü profilleri")
            SurfaceCard {
                uiState.bridgeProfiles.forEach { profile ->
                    val selected = profile.id == uiState.activeBridgeProfileId
                    ListRow(
                        title = profile.name.ifBlank { "Adsız köprü" },
                        detail = profile.baseUrl,
                        onClick = { actions.selectBridgeProfile(profile.id) },
                        leading = { Icon(Icons.Outlined.Link, null, tint = Ui2.colors.ink2) },
                        trailing = {
                            if (selected) Icon(Icons.Default.Check, "Aktif profil", tint = Ui2.colors.accent)
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                    OutlinedButton(
                        onClick = actions::addBridgeProfile,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text("Profil ekle")
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = uiState.bridgeProfiles.size > 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Delete, null)
                        Text("Profili sil")
                    }
                }
                if (uiState.bridgeProfiles.size == 1) {
                    Text(
                        "Uygulamanın bağlanabilmesi için en az bir profil kalmalı.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink3,
                    )
                }
            }

            SectionHeader("Seçili profil")
            SurfaceCard {
                OutlinedTextField(
                    value = activeProfile?.name.orEmpty(),
                    onValueChange = actions::updateBridgeProfileName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Profil adı") },
                )
                OutlinedTextField(
                    value = uiState.settings.baseUrl,
                    onValueChange = actions::updateUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Bridge URL") },
                )
                OutlinedTextField(
                    value = uiState.settings.token,
                    onValueChange = actions::updateToken,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Token / cihaz anahtarı") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                    Button(
                        onClick = actions::saveSettings,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Ui2.colors.accent, contentColor = Ui2.colors.onAccent),
                    ) { Text("Kaydet") }
                    OutlinedButton(
                        onClick = actions::testConnection,
                        enabled = !uiState.testing,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (uiState.testing) "Deneniyor…" else "Bağlantıyı dene") }
                }
                StatusBadge(
                    text = when {
                        !uiState.protocolCompatible -> "Protokol uyumsuz"
                        uiState.healthOk -> "Health check başarılı"
                        else -> "Bağlı değil"
                    },
                    kind = if (uiState.healthOk && uiState.protocolCompatible) StatusKind.Done else StatusKind.Danger,
                )
                OutlinedButton(
                    onClick = { confirmRestart = true },
                    enabled = !uiState.bridgeRestarting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, null)
                    Text(if (uiState.bridgeRestarting) "Yeniden başlatılıyor…" else "Masaüstü bridge'i yeniden başlat")
                }
            }

            SectionHeader("Cihaz güvenliği")
            SurfaceCard {
                Text(
                    if (uiState.devicePaired) "Bu telefon kendine özel cihaz anahtarıyla eşleştirildi."
                    else "Ortak token yerine bu telefona özel ve yenilenebilir bir anahtar kullanabilirsin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui2.colors.ink2,
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("6 haneli eşleştirme kodu") },
                )
                if (uiState.pairingExpiresAt.isNotBlank()) {
                    Text("Kod geçerlilik sonu: ${uiState.pairingExpiresAt}", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                    OutlinedButton(
                        onClick = actions::startDevicePairing,
                        enabled = !uiState.deviceAuthLoading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Kod oluştur") }
                    Button(
                        onClick = { actions.completeDevicePairing(pairingCode) },
                        enabled = pairingCode.length == 6 && !uiState.deviceAuthLoading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Eşleştir") }
                }
                if (uiState.devicePaired) {
                    OutlinedButton(
                        onClick = { confirmRotate = true },
                        enabled = !uiState.deviceAuthLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cihaz anahtarını yenile") }
                }
            }
        }
    }
    }

    if (confirmRotate) {
        ConfirmDialog(
            title = "Cihaz anahtarı yenilensin mi?",
            text = "Eski anahtar anında geçersiz olur. İşlem tamamlanmadan uygulamayı kapatma.",
            confirmLabel = "Anahtarı yenile",
            destructive = false,
            onConfirm = { confirmRotate = false; actions.rotateDeviceKey() },
            onDismiss = { confirmRotate = false },
        )
    }
    if (confirmRestart) {
        ConfirmDialog(
            title = "Bridge yeniden başlatılsın mı?",
            text = "Masaüstündeki bridge-restart.bat çalıştırılacak. Aktif bağlantılar kısa süre kesilebilir.",
            confirmLabel = "Yeniden başlat",
            destructive = false,
            onConfirm = { confirmRestart = false; actions.restartBridgeFromDesktop() },
            onDismiss = { confirmRestart = false },
        )
    }
    if (confirmDelete && activeProfile != null) {
        ConfirmDialog(
            title = "${activeProfile.name.ifBlank { "Adsız köprü" }} silinsin mi?",
            text = "Bu profile ait adres ve token bu cihazdan kaldırılacak.",
            confirmLabel = "Profili sil",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                actions.deleteBridgeProfile(activeProfile.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun SettingsNotificationsScreen(uiState: RemoteUiState, actions: RemoteViewModel, onBack: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) actions.refreshBatteryOptimizationStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg).verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "Bildirimler", subtitle = "Not, hatırlatma, onay ve görev uyarıları", onBack = onBack)
        Column(
            Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
        ) {
            SectionHeader("Bildirimler")
            SurfaceCard {
                // Seçim yok: tek kanal uygulamanın köprü bağlantısı. Susturmak için
                // Android'in kendi uygulama/kanal bildirim ayarları kullanılır.
                ListRow(
                    title = "Uygulama bağlantısı",
                    detail = "Notlar, hatırlatmalar, onaylar ve görev bildirimleri köprü bağlantısından gelir",
                    detailMaxLines = 3,
                    leading = { Icon(Icons.Outlined.Notifications, null, tint = Ui2.colors.ink2) },
                )
            }
            SectionHeader("Arka plan bağlantısı")
            SurfaceCard {
                ListRow(
                    title = "Pil optimizasyonu",
                    detail = if (uiState.batteryOptimizationIgnored) {
                        "İstisna açık; ekran kapalıyken bridge bağlantısı daha güvenilir"
                    } else {
                        "İsteğe bağlı: Doze sırasında bağlantı ve onay bildirimlerinin gecikmesini azaltır"
                    },
                    leading = { Icon(Icons.Outlined.BatterySaver, null, tint = Ui2.colors.ink2) },
                    trailing = {
                        if (uiState.batteryOptimizationIgnored) {
                            OutlinedButton(onClick = actions::openBatteryOptimizationSettings) { Text("Ayarlar") }
                        } else {
                            Button(onClick = actions::requestBatteryOptimizationExemption) { Text("İzin ver") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun SettingsUpdateScreen(actions: RemoteViewModel, onBack: () -> Unit) {
    val updateInfo by actions.updateInfo.collectAsState()
    val progress by actions.downloadProgress.collectAsState()

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Güncelleme", subtitle = "Sürüm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
        ) {
            SurfaceCard {
                ListRow(
                    title = "Yüklü sürüm",
                    detail = "${BuildConfig.VERSION_NAME} · versionCode ${BuildConfig.VERSION_CODE}",
                    leading = { Icon(Icons.Outlined.SystemUpdate, null, tint = Ui2.colors.ink2) },
                    trailing = { StatusBadge("Yüklü") },
                )
                Button(
                    onClick = { actions.checkForUpdate(true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ui2.colors.accent, contentColor = Ui2.colors.onAccent),
                ) { Text("Güncellemeyi kontrol et") }
            }

            updateInfo?.let { info ->
                SectionHeader("Yeni sürüm")
                SurfaceCard {
                    Text("${info.versionName} (${info.versionCode})", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink)
                    Text(info.notes.ifBlank { "Sürüm notu yok" }, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink2)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress!!.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Ui2.colors.accent,
                            trackColor = Ui2.colors.surface2,
                        )
                        Text("%${(progress!! * 100).toInt()}", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    }
                    Button(
                        onClick = actions::downloadAndInstallUpdate,
                        enabled = progress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (progress == null) "İndir ve yükle" else "İndiriliyor…") }
                    OutlinedButton(onClick = actions::dismissUpdate, modifier = Modifier.fillMaxWidth()) { Text("Şimdilik kapat") }
                }
            }
        }
    }
}
