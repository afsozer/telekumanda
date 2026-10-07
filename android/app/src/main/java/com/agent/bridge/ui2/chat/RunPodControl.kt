package com.agent.bridge.ui2.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.RunPodStatus
import com.agent.bridge.ui2.theme.Ui2
import kotlin.math.PI
import kotlin.math.sin

// Pill'in temsil ettiği eylem:
//  "stop"    -> köprü bağlı (ready): RunPod durdur
//  "connect" -> pod ÇALIŞIYOR ama köprü yok: sadece bağlan (pod'u API'den açmayız)
//  "console" -> pod kapalı: kullanıcıyı RunPod sitesine at; elle başlatır/migrate eder,
//               dönüp tekrar basınca "connect" olur. GPU-dolu/migrate işi kullanıcıda.
internal fun runPodPillAction(status: RunPodStatus): String = when {
    status.ready && status.phase == "ready" -> "stop"
    status.phase == "running" || (status.podStatus.isNotBlank() && status.podStatus != "EXITED") -> "connect"
    else -> "console"
}

internal fun runPodShouldStop(status: RunPodStatus): Boolean = runPodPillAction(status) == "stop"

internal fun runPodPillLabel(status: RunPodStatus): String = when {
    status.phase == "starting" || (status.operationActive && status.action == "start") -> when (status.step) {
        "pod" -> "Pod açılıyor…"
        "ssh_port" -> "SSH bekleniyor…"
        "ssh" -> "SSH bağlanıyor…"
        "model" -> "Model açılıyor…"
        "tunnel" -> "Tünel açılıyor…"
        "health" -> "Model ısınıyor…"
        "proxy" -> "Proxy açılıyor…"
        else -> "RunPod başlıyor…"
    }
    status.phase == "stopping" || (status.operationActive && status.action == "stop") -> when (status.step) {
        "tunnel" -> "Tünel kapanıyor…"
        "pod" -> "Pod kapanıyor…"
        else -> "RunPod duruyor…"
    }
    else -> when (runPodPillAction(status)) {
        "stop" -> "RunPod durdur"
        "connect" -> "RunPod bağlan"
        else -> "RunPod başlat"
    }
}

internal fun runPodProgress(status: RunPodStatus): Float? {
    val stopping = status.phase == "stopping" || (status.operationActive && status.action == "stop")
    val starting = status.phase == "starting" || (status.operationActive && status.action == "start")
    if (!starting && !stopping) return null
    return if (stopping) when (status.step) {
        "tunnel" -> 0.38f
        "pod" -> 0.76f
        else -> 0.08f
    } else when (status.step) {
        "pod" -> 0.12f
        "ssh_port" -> 0.28f
        "ssh" -> 0.44f
        "model" -> 0.62f
        "tunnel" -> 0.78f
        "health" -> 0.92f
        else -> 0.05f
    }
}

@Composable
fun RunPodControlPill(
    status: RunPodStatus,
    onClick: () -> Unit,
) {
    val ready = status.ready && status.phase == "ready"
    val busy = status.operationActive || status.phase == "starting" || status.phase == "stopping"
    val targetProgress = runPodProgress(status) ?: 0f
    val fillProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(650),
        label = "runpod-step-progress",
    )
    val transition = rememberInfiniteTransition(label = "runpod-ready-glow")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "runpod-ready-pulse",
    )
    val sparklePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "runpod-ready-sparkles",
    )
    val shape = RoundedCornerShape(999.dp)
    val active = Color(0xFFFFB300)
    val background = when {
        ready -> active.copy(alpha = 0.72f + pulse * 0.25f)
        busy -> Ui2.colors.surface2
        status.phase == "running" -> Ui2.colors.attention.copy(alpha = 0.18f)
        else -> Ui2.colors.surface2
    }
    val foreground = when {
        ready -> Color(0xFF251A00)
        busy -> Ui2.colors.running
        status.phase == "running" -> Ui2.colors.attention
        else -> Ui2.colors.ink2
    }
    val baseModifier = Modifier
        .then(if (ready) Modifier
            .shadow(
                elevation = (7f + pulse * 11f).dp,
                shape = shape,
                ambientColor = active,
                spotColor = active,
            )
            .graphicsLayer {
                scaleX = 1f + pulse * 0.025f
                scaleY = 1f + pulse * 0.025f
            } else Modifier)
        .background(background, shape)
        .border(1.dp, if (ready) active else foreground.copy(alpha = 0.35f), shape)
        .clickable(enabled = !busy, onClick = onClick)

    Box(baseModifier) {
        if (busy) {
            Box(Modifier.matchParentSize().clip(shape)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillProgress)
                        .background(Ui2.colors.running.copy(alpha = 0.24f + pulse * 0.12f)),
                )
            }
        }
        if (ready) {
            Canvas(Modifier.matchParentSize().clip(shape)) {
                val sparkles = listOf(
                    Triple(0.12f, 0.30f, 0.00f),
                    Triple(0.29f, 0.72f, 0.36f),
                    Triple(0.56f, 0.24f, 0.68f),
                    Triple(0.76f, 0.68f, 0.16f),
                    Triple(0.91f, 0.34f, 0.52f),
                )
                sparkles.forEach { (baseX, y, offset) ->
                    val cycle = (sparklePhase + offset) % 1f
                    val twinkle = ((sin(cycle * 2f * PI).toFloat() + 1f) / 2f)
                    val x = ((baseX + sparklePhase * 0.08f) % 1f) * size.width
                    drawRunPodSparkle(
                        center = Offset(x, y * size.height),
                        radius = (1.6f + twinkle * 2.2f).dp.toPx(),
                        color = Color.White.copy(alpha = 0.16f + twinkle * 0.68f),
                    )
                }
            }
        }
        Text(
            runPodPillLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.drawRunPodSparkle(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.23f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + inner, center.y - inner)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + inner, center.y + inner)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - inner, center.y + inner)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - inner, center.y - inner)
        close()
    }
    drawPath(path, color)
}
