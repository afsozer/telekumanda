package com.agent.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPodCapacityErrorTest {
    @Test fun detectsEnglishAndTurkishRunPodCapacityErrors() {
        assertTrue(isRunPodGpuCapacityError(
            "There are not enough free GPUs on the host machine to start this pod.",
        ))
        assertTrue(isRunPodGpuCapacityError(
            "RunPod hostunda yeterli boş GPU yok. Birkaç dakika sonra tekrar deneyin.",
        ))
        assertFalse(isRunPodGpuCapacityError("SSH bağlantısı kurulamadı"))
        assertFalse(isRunPodGpuCapacityError("RunPod kapalı"))
    }
}
