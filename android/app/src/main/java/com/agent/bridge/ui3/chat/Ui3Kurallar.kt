package com.agent.bridge.ui3.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.Opencode2Instruction
import com.agent.bridge.Opencode2SavedPermission
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Sheet'in tamamını kaplamasın (Ui3Degisiklikler'deki LISTE_MAKS ile aynı
// gerekçe ve ölçü).
private val LISTE_MAKS = 400.dp

/**
 * OTURUM KURALLARI (yalnız v2) — tek sheet, iki bölüm:
 *
 *   1. TALİMATLAR — AGENTS.md'ye dokunmadan bu oturuma bağlanan kalıcı kural
 *      parçaları (örn. "dilekçeleri UYAP biçiminde yaz"). Ekleme formu listede.
 *   2. KAYITLI İZİNLER — "always" yanıtı verilen izinlerin birikimi; tek
 *      tuşla kaldırılır. Oturumdan bağımsız (v2 deposu genel).
 *
 * Aç-çek kuralı (diff/checkpoint ile aynı): açılışta bir kez istenir, her
 * ekleme/silmeden sonra liste yenilenir. Yoklamaya binmez.
 */
@Composable
internal fun ColumnScope.Ui3Kurallar(
    instructions: List<Opencode2Instruction>,
    instructionsLoading: Boolean,
    savedPermissions: List<Opencode2SavedPermission>,
    savedPermissionsLoading: Boolean,
    onTalimatEkle: (String, String) -> Unit,
    onTalimatSil: (String) -> Unit,
    onIzinSil: (String) -> Unit,
    onGeri: (() -> Unit)?,
) {
    var yeniAnahtar by remember { mutableStateOf("") }
    var yeniDeger by remember { mutableStateOf("") }

    SheetBasligi(
        "OTURUM KURALLARI",
        listOfNotNull(
            instructions.takeIf { it.isNotEmpty() }?.let { "${it.size} talimat" },
            savedPermissions.takeIf { it.isNotEmpty() }?.let { "${it.size} izin" },
        ).joinToString(" · ").ifEmpty { null },
        onGeri,
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = LISTE_MAKS).testTag("kurallar_liste"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Ui3Tokens.s12,
            vertical = Ui3Tokens.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // ── Bölüm 1: Talimatlar ─────────────────────────────────────────────
        item {
            BolumBasligi("TALİMATLAR", "Bu oturuma kalıcı kural — ajan her turda görür")
        }
        if (instructionsLoading && instructions.isEmpty()) {
            item { YukleniyorSatiri("Talimatlar okunuyor…") }
        } else if (instructions.isEmpty()) {
            item {
                BosSatiri("Talimat yok. Aşağıdan ekle — örn. anahtar: yazim, değer: resmî yazışma dili kullan.")
            }
        } else {
            items(instructions, key = { "ins_" + it.key }) { talimat ->
                KuralSatiri(
                    baslik = talimat.key,
                    metin = talimat.value,
                    testEtiketi = "kural_talimat",
                    onSil = { onTalimatSil(talimat.key) },
                )
            }
        }
        item {
            // Ekleme formu: anahtar v2 şeması gereği a-z 0-9 . _ -; burada
            // zorlanmıyor (köprü reddedip hatayı söylüyor) ama ipucu duruyor.
            Column(
                Modifier.fillMaxWidth().padding(top = Ui3Tokens.s4).testTag("kural_ekleme"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = yeniAnahtar,
                    onValueChange = { yeniAnahtar = it },
                    label = { Text("Anahtar (a-z 0-9 . _ -)", style = Ui3Type.alt) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = yeniDeger,
                    onValueChange = { yeniDeger = it },
                    label = { Text("Kural", style = Ui3Type.alt) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (yeniAnahtar.isNotBlank() && yeniDeger.isNotBlank()) {
                                onTalimatEkle(yeniAnahtar.trim(), yeniDeger.trim())
                                yeniAnahtar = ""
                                yeniDeger = ""
                            }
                        },
                        enabled = yeniAnahtar.isNotBlank() && yeniDeger.isNotBlank(),
                    ) { Text("Ekle") }
                }
            }
        }

        // ── Bölüm 2: Kayıtlı izinler ────────────────────────────────────────
        item {
            BolumBasligi("KAYITLI İZİNLER", "\"Her zaman\" verilen izinler — silinen yeni sorulur")
        }
        if (savedPermissionsLoading && savedPermissions.isEmpty()) {
            item { YukleniyorSatiri("İzin kuralları okunuyor…") }
        } else if (savedPermissions.isEmpty()) {
            item { BosSatiri("Kayıtlı izin kuralı yok — onaylarda \"her zaman\" dersen burada birikir.") }
        } else {
            items(savedPermissions, key = { "perm_" + it.id }) { izin ->
                KuralSatiri(
                    baslik = izin.action,
                    metin = izin.resource,
                    yanNot = izin.created.takeIf { it > 0 }?.let { tarih(it) },
                    testEtiketi = "kural_izin",
                    onSil = { onIzinSil(izin.id) },
                )
            }
        }
    }
}

@Composable
private fun BolumBasligi(baslik: String, alt: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s8, vertical = Ui3Tokens.s8),
    ) {
        Text(baslik, style = Ui3Type.etiket, color = Ui3Colors.ink3)
        if (alt.isNotBlank()) {
            Text(alt, style = Ui3Type.alt, color = Ui3Colors.ink3)
        }
    }
}

@Composable
private fun YukleniyorSatiri(metin: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = Ui3Colors.vurguHi,
            strokeWidth = 2.dp,
        )
        Text(metin, style = Ui3Type.govde, color = Ui3Colors.ink2)
    }
}

@Composable
private fun BosSatiri(metin: String) {
    Text(
        metin,
        style = Ui3Type.govde,
        color = Ui3Colors.ink2,
        modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
    )
}

@Composable
private fun KuralSatiri(
    baslik: String,
    metin: String,
    yanNot: String? = null,
    testEtiketi: String,
    onSil: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .testTag(testEtiketi),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier.padding(horizontal = Ui3Tokens.s12, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    baslik,
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metin.isNotBlank()) {
                    Text(
                        metin,
                        style = Ui3Type.alt,
                        color = Ui3Colors.ink2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (yanNot != null) {
                    Text(yanNot, style = Ui3Type.rozet, color = Ui3Colors.ink3)
                }
            }
            IconButton(onClick = onSil, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Kuralı sil",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(16.dp).testTag(testEtiketi + "_sil"),
                )
            }
        }
    }
}

private fun tarih(ms: Long): String {
    val bicim = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
    return bicim.format(Date(ms))
}
