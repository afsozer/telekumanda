package com.agent.bridge.ui2.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.udf.MOBIL_IMZA_OPERATORLERI
import com.agent.bridge.udf.MobilImzaDurumu
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * UYAP mobil imza penceresi.
 *
 * Doğrulama kodu ekranda BÜYÜK duruyor ve kod ekrandayken pencere kendiliğinden
 * kapanmıyor: kullanıcı telefonuna düşen imza isteğindeki kodla buradakini
 * karşılaştırmadan PIN girmemeli — kod eşleşmiyorsa imzalanan şey onun belgesi
 * değildir. Bu, akışın güvenlik gerekçesi; süslemesi değil.
 */
@Composable
fun MobilImzaDialog(durum: MobilImzaDurumu, actions: RemoteViewModel) {
    if (durum is MobilImzaDurumu.Kapali) return

    var telNo by rememberSaveable { mutableStateOf(actions.mobilImzaTelefon()) }
    var operator by rememberSaveable {
        mutableStateOf(actions.mobilImzaOperator().ifBlank { MOBIL_IMZA_OPERATORLERI.first() })
    }
    val calisiyor = durum is MobilImzaDurumu.HashIsteniyor || durum is MobilImzaDurumu.OnayBekleniyor

    AlertDialog(
        // Çalışırken dışarı dokunuşla kapanmaz: kullanıcı telefonuna bakarken
        // yanlışlıkla dokunup akışı iptal etmesin.
        onDismissRequest = { if (!calisiyor) actions.mobilImzaKapat() },
        title = { Text("Mobil imza") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12)) {
                when (durum) {
                    is MobilImzaDurumu.Form -> {
                        Text(
                            "Belge UYAP mobil imza geçidine gönderilecek. Telefonuna imza isteği düşecek.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ui2.colors.ink2,
                        )
                        OutlinedTextField(
                            value = telNo,
                            onValueChange = { telNo = it },
                            label = { Text("Telefon numarası") },
                            singleLine = true,
                            isError = durum.hata.isNotBlank(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                            MOBIL_IMZA_OPERATORLERI.forEach { secenek ->
                                FilterChip(
                                    selected = operator == secenek,
                                    onClick = { operator = secenek },
                                    label = { Text(secenek, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (durum.hata.isNotBlank()) {
                            Text(durum.hata, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.danger)
                        }
                    }

                    is MobilImzaDurumu.HashIsteniyor -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                    ) {
                        CircularProgressIndicator()
                        Text("UYAP geçidine bağlanılıyor…", style = MaterialTheme.typography.bodyMedium)
                    }

                    is MobilImzaDurumu.OnayBekleniyor -> {
                        Text(
                            "DOĞRULAMA KODU",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ui2.colors.ink2,
                        )
                        Text(
                            durum.dogrulamaKodu.ifBlank { "—" },
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Ui2.colors.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Ui2.colors.line, RoundedCornerShape(8.dp))
                                .padding(vertical = Ui2Tokens.s12, horizontal = Ui2Tokens.s16),
                        )
                        Text(
                            "Telefonundaki istekte AYNI kodu gör, sonra PIN'ini gir. Kod tutmuyorsa imzalama.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ui2.colors.ink2,
                        )
                    }

                    is MobilImzaDurumu.Tamam -> Text(durum.mesaj, style = MaterialTheme.typography.bodyMedium)

                    is MobilImzaDurumu.Hata -> Text(
                        durum.mesaj,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ui2.colors.danger,
                    )

                    MobilImzaDurumu.Kapali -> Unit
                }
            }
        },
        confirmButton = {
            when (durum) {
                is MobilImzaDurumu.Form -> TextButton(
                    onClick = { actions.mobilImzaBaslat(telNo.trim(), operator) },
                ) { Text("İmzala") }
                is MobilImzaDurumu.Hata -> TextButton(
                    onClick = { actions.mobilImzaAc() },
                ) { Text("Tekrar dene") }
                is MobilImzaDurumu.Tamam -> TextButton(
                    onClick = { actions.mobilImzaKapat() },
                ) { Text("Tamam") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = { actions.mobilImzaKapat() }) {
                Text(if (calisiyor) "İptal" else "Kapat")
            }
        },
    )
}
