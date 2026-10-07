package com.agent.bridge.ui2.chat

import com.agent.bridge.RunPodStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RunPodControlTest {
    @Test fun labelsFollowWorkerLifecycle() {
        assertEquals("RunPod başlat", runPodPillLabel(RunPodStatus(phase = "stopped")))
        assertEquals("Pod açılıyor…", runPodPillLabel(RunPodStatus(phase = "starting", step = "pod", operationActive = true)))
        assertEquals("SSH bekleniyor…", runPodPillLabel(RunPodStatus(phase = "starting", step = "ssh_port", operationActive = true)))
        // "API sınanıyor…" değil: metin kaynakta "Model ısınıyor…" olarak
        // değiştirilmiş, test geride kalmıştı (bu oturumda kırmızı bulundu).
        assertEquals("Model ısınıyor…", runPodPillLabel(RunPodStatus(phase = "starting", step = "health", operationActive = true)))
        assertEquals("RunPod durdur", runPodPillLabel(RunPodStatus(phase = "ready", ready = true)))
        // Pod çalışıyor ama köprü yok -> API'den açmayız, yalnız bağlanırız.
        assertEquals("RunPod bağlan", runPodPillLabel(RunPodStatus(phase = "running", podStatus = "RUNNING")))
        assertEquals("Pod kapanıyor…", runPodPillLabel(RunPodStatus(phase = "stopping", step = "pod", operationActive = true)))
    }

    @Test fun actionFollowsWorkerLifecycle() {
        assertEquals("console", runPodPillAction(RunPodStatus(phase = "stopped")))
        assertEquals("connect", runPodPillAction(RunPodStatus(phase = "running", podStatus = "RUNNING")))
        assertEquals("stop", runPodPillAction(RunPodStatus(phase = "ready", ready = true)))
    }

    @Test fun progressTracksRealWorkerSteps() {
        assertEquals(0.05f, runPodProgress(RunPodStatus(phase = "starting", step = "requested")))
        assertEquals(0.28f, runPodProgress(RunPodStatus(phase = "starting", step = "ssh_port")))
        assertEquals(0.62f, runPodProgress(RunPodStatus(phase = "starting", step = "model")))
        assertEquals(0.92f, runPodProgress(RunPodStatus(phase = "starting", step = "health")))
        assertEquals(0.76f, runPodProgress(RunPodStatus(phase = "stopping", step = "pod")))
        assertEquals(null, runPodProgress(RunPodStatus(phase = "ready", ready = true)))
    }
}
