package com.agent.bridge.ui3.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.agent.bridge.AlertHostState
import com.agent.bridge.AppAlertHost
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.ContentWidth
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.settings.DuzHttpUyarisi
import com.agent.bridge.ui2.settings.EslestirmeKoduAlani
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import com.agent.bridge.ui3.material.MeshBackground
import com.agent.bridge.ui3.material.Ui3OduncKap
import com.agent.bridge.ui3.theme.Ui3Tema

/**
 * Lite'ın ilk açılışı: köprü kimliği yoksa sohbet kabuğu yerine bu ekran.
 *
 * Lite APK köprünün token'ını taşımıyor; telefon kendine özel cihaz anahtarını
 * eşleştirme koduyla alır. Kod, kimliği olan bir istemcide (tam sürüm: Ayarlar ›
 * Bağlantı ve cihaz eşleme › Kod oluştur) üretilir. Akış tam sürümünkiyle aynı
 * (`completeDevicePairing` → `/pairing/complete`); anahtar etkin köprü
 * profiline yazılır ve kimlik dolduğu an MainActivity sohbet kabuğuna geçer.
 *
 * Adres derlemeden gelir (sır değil) ama düzenlenebilir: derleme makinesinin
 * adı telefondan çözülmeyebilir.
 */
@Composable
internal fun LiteEslestirmeEkrani(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    alertHostState: AlertHostState,
) = Ui3Tema {
    Box(Modifier.fillMaxSize().testTag("ekran_lite_eslestirme")) {
        MeshBackground(Modifier.fillMaxSize())
        Ui3OduncKap {
            ContentWidth(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ScreenHeader(
                        title = "Köprüye bağlan",
                        subtitle = "Bu telefon için cihaz anahtarı al",
                    )
                    Column(
                        Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
                        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
                    ) {
                        SectionHeader("Köprü adresi")
                        SurfaceCard {
                            OutlinedTextField(
                                value = uiState.settings.baseUrl,
                                onValueChange = actions::updateUrl,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Bridge URL") },
                            )
                            DuzHttpUyarisi(uiState.settings.baseUrl)
                        }
                        SectionHeader("Eşleştirme kodu")
                        SurfaceCard {
                            Text(
                                "Bilgisayardaki ya da başka bir telefondaki tam sürümde Ayarlar › Bağlantı ve " +
                                    "cihaz eşleme › Kod oluştur ile 6 haneli bir kod üret ve buraya yaz. Kod kısa " +
                                    "süre geçerlidir ve yalnız bir kez kullanılır.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ui2.colors.ink2,
                            )
                            EslestirmeKoduAlani(uiState, actions, kodUretilebilir = false)
                        }
                    }
                }
            }
        }
        AppAlertHost(state = alertHostState)
    }
}
