package com.agent.bridge

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** ViewModel niyeti yollar; dosya/bağlantı açmanın karşılığı UI kabuğundadır. */
sealed interface AppAlertAction {
    data class OpenFile(val path: String, val coworkOnly: Boolean) : AppAlertAction
    data class OpenUrl(val url: String) : AppAlertAction
}

data class AppAlert(
    val text: String,
    val action: AppAlertAction? = null,
    val actionLabel: String = "",
)

internal class AlertHostState {
    var visible by mutableStateOf(false)
        private set
    var alert by mutableStateOf(AppAlert(""))
        private set
    var revision by mutableStateOf(0L)
        private set
    var toastText by mutableStateOf("")
        private set
    var toastRevision by mutableStateOf(0L)
        private set

    fun show(msg: String) = show(AppAlert(msg))
    fun show(next: AppAlert) {
        if (next.action == null && isSimpleConfirmation(next.text)) {
            toastText = next.text
            toastRevision++
        } else {
            alert = next
            revision++
            visible = true
        }
    }
    fun dismiss() { visible = false }
}

@Composable
internal fun AppAlertHost(
    state: AlertHostState,
    modifier: Modifier = Modifier,
    onAction: (AppAlertAction) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accessibility = LocalAccessibilityManager.current
    var toast by remember { mutableStateOf<Toast?>(null) }
    var detail by remember { mutableStateOf<AppAlert?>(null) }

    LaunchedEffect(state.toastRevision) {
        if (state.toastRevision > 0) {
            // Seri işlemler eskimiş bildirimleri kuyrukta biriktirmesin.
            toast?.cancel()
            toast = Toast.makeText(context, state.toastText, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    DisposableEffect(context) {
        onDispose { toast?.cancel() }
    }

    if (state.visible) {
        val current = state.alert
        Box(
            modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar(
                actionOnNewLine = current.action != null,
                action = {
                    Row {
                        val colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary,
                        )
                        val action = current.action
                        if (action != null && current.actionLabel.isNotBlank()) {
                            TextButton(onClick = { state.dismiss(); onAction(action) }, colors = colors) {
                                Text(current.actionLabel)
                            }
                        }
                        TextButton(onClick = { detail = current; state.dismiss() }, colors = colors) {
                            Text("Ayrıntı")
                        }
                    }
                },
                dismissAction = {
                    IconButton(onClick = state::dismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                },
            ) {
                Text(current.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    detail?.let { current ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Bildirim ayrıntısı") },
            text = {
                SelectionContainer {
                    Text(current.text, Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text("Tamam") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(current.text)) }) {
                        Text("Kopyala")
                    }
                    val action = current.action
                    if (action != null && current.actionLabel.isNotBlank()) {
                        TextButton(onClick = { detail = null; onAction(action) }) {
                            Text(current.actionLabel)
                        }
                    }
                }
            },
        )
    }

    LaunchedEffect(state.visible, state.revision) {
        if (state.visible) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = 9_000L,
                containsIcons = false,
                containsText = true,
                containsControls = true,
            ) ?: 9_000L
            delay(timeout)
            state.dismiss()
        }
    }
}
