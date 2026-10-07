package com.agent.bridge.ui3.chat

import com.agent.bridge.RunPodStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Model çipindeki noktanın rengi hapla AYNI durumu söylemeli.
 *
 * Hap MODEL SEÇ sheet'inde, nokta composer şeridinde; ikisi aynı `RunPodStatus`
 * nesnesini okuyor ama farklı yerlerde çiziliyor. Ayrışırlarsa kullanıcı
 * çipte "kapalı" görüp sheet'i açtığında "durdur" bulur. Beklenen eşleme
 * `runPodPillLabel`/`runPodPillAction`'ın (ui2, `RunPodControlTest`) dallarına
 * bağlanıyor.
 */
class Ui3RunPodIsigiTest {
    @Test fun isikPodYasamDongusunuIzler() {
        assertEquals(Ui3RunPodIsigi.Kapali, ui3RunPodIsigi(RunPodStatus(phase = "stopped")))
        assertEquals(
            Ui3RunPodIsigi.Mesgul,
            ui3RunPodIsigi(RunPodStatus(phase = "starting", step = "model", operationActive = true)),
        )
        assertEquals(
            Ui3RunPodIsigi.Mesgul,
            ui3RunPodIsigi(RunPodStatus(phase = "stopping", step = "pod", operationActive = true)),
        )
        // Pod ayakta, köprü yok: hapta "RunPod bağlan" yazıyor.
        assertEquals(
            Ui3RunPodIsigi.PodAcik,
            ui3RunPodIsigi(RunPodStatus(phase = "running", podStatus = "RUNNING")),
        )
        assertEquals(
            Ui3RunPodIsigi.Hazir,
            ui3RunPodIsigi(RunPodStatus(phase = "ready", ready = true)),
        )
    }

    @Test fun hazirMesgulunOnunde() {
        // `ready && operationActive` geçici bir hâl (durdurma isteği daha faz
        // değiştirmemişken). Hap bu durumda kehribar kalıyor; nokta da öyle
        // kalmalı, yoksa iki yüzey tek karede iki farklı renk gösterirdi.
        assertEquals(
            Ui3RunPodIsigi.Hazir,
            ui3RunPodIsigi(RunPodStatus(phase = "ready", ready = true, operationActive = true)),
        )
    }
}
