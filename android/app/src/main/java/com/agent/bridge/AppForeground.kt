package com.agent.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Uygulama önde mi (üst odaktaki pencere). Kalıcılığa gerek yok — süreç
// ölmüşse zaten ön planda değiliz. Sohbet yoklamasının görünürlük kapısı ayrı
// (`SohbetYoklamasi`, ON_START/ON_STOP): bölünmüş ekranda alttaki pencere
// odakta olmasa da görünür, sohbeti akmaya devam etmeli.
object AppForeground {
    // StateFlow, çünkü periyodik ağ döngüleri (ısıtma + sekme aynası) arka
    // planda `first { it }` ile bunun üstünde uyuyor — 28-29 Ağu gecesi bu
    // döngüler ekrana bakmadan 45 sn'de bir LTE'yi uyandırıp gecede %16 pil
    // yaktı. Volatile boolean bekletmeyi kuramazdı.
    private val foregroundFlow = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> get() = foregroundFlow

    fun setForeground(value: Boolean) {
        foregroundFlow.value = value
    }
}
