import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonSlurper
import java.net.InetAddress
import java.util.Properties

private fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

@Suppress("UNCHECKED_CAST")
val localBridgeConfig = rootProject.file("../bridge/config.json")
    .takeIf { it.isFile }
    ?.let { JsonSlurper().parseText(it.readText()) as? Map<String, Any?> }
    .orEmpty()
val liteBridgeHost = runCatching { InetAddress.getLocalHost().hostName.trim().lowercase() }
    .getOrDefault("")
val liteBridgePort = (localBridgeConfig["port"] as? Number)?.toInt() ?: 8787
val liteBridgeUrl = if (liteBridgeHost.isBlank()) "" else "http://$liteBridgeHost:$liteBridgePort"
// config.json'dan YALNIZ port okunur. Köprünün ana token'ı APK'ya gömülmez:
// APK'yı okuyabilen her uygulama BuildConfig'ten çıkarabilirdi. Lite ilk
// açılışta eşleştirme koduyla kendi cihaz anahtarını alır (LiteEslestirmeEkrani).

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.agent.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agent.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 437
        versionName = "11.115"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "IS_LITE", "false")
        buildConfigField("String", "LITE_BRIDGE_URL", "".asBuildConfigString())
    }

    buildTypes {
        getByName("release") {
            // OTA guncellemesi cihazdaki kurulumun UZERINE gelmek zorunda.
            // Android farkli anahtarla imzalanmis APK'yi guncelleme olarak
            // KABUL ETMEZ; kaldirip yeniden kurmak gerekir ve uygulama verisi
            // (kopru adresi, token, sekmeler) gider. Bugune kadar yayimlanan
            // surumlerin hepsi assembleDebug ciktisiydi, yani debug anahtariyla
            // imzaliydi — olculdu (14.09.2026): yayindaki app-latest.apk
            // sertifikasi CN=Android Debug. Release de AYNI anahtarla
            // imzalaniyor. Dagitim yalniz kullanicinin kendi Tailscale
            // koprusunden; magaza yok.
            signingConfig = signingConfigs.getByName("debug")
            // R8 KAPALI: proguard-rules.pro hic yazilmadi. Markwon ve media3
            // gibi yerlerde derleme gecip calisma aninda cokme riski var;
            // acilacaksa once cihazda test kurulumu.
            isMinifyEnabled = false
        }
        create("lite") {
            initWith(getByName("debug"))
            // initWith(debug) hata ayıklanabilirliği de miras verir; açık kalırsa
            // adb `run-as` uygulamanın özel verisini (cihaz anahtarı dahil) okur ve
            // BuildConfig.DEBUG true olup hata ayıklama loglarını açar.
            // İmza yapılandırması debug'dan miras kalıyor ve BİLEREK değişmiyor:
            // yayımlanmış Lite APK'larının üzerine OTA kurulumu aynı anahtarı ister.
            isDebuggable = false
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "IS_LITE", "true")
            buildConfigField("String", "LITE_BRIDGE_URL", liteBridgeUrl.asBuildConfigString())
            matchingFallbacks += listOf("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // composeOptions/kotlinCompilerExtensionVersion YOK: Kotlin 2.x'te Compose
    // derleyicisi Kotlin ile birlikte sürümleniyor ve eklenti olarak uygulanıyor.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // DelegateReachabilityTest iki kaynak ağacını doğrudan diskten okur.
            // Bu ağaçlar görev girdisi olarak bildirilmezse içerik değişse bile
            // Gradle testi UP-TO-DATE sayıp atlar ve bekçi sessizce devre dışı
            // kalır. Yollar rootProject'e göre kurulur; kişiye özel mutlak yol
            // yok. Var olmayan dizin girdisi Gradle'da boş anlık görüntü olur,
            // yapılandırma aşamasında patlamaz.
            it.inputs.files(
                rootProject.layout.projectDirectory.dir("app/src/main"),
                rootProject.layout.projectDirectory.dir("shared/src/main")
            ).withPropertyName("delegateReachabilitySourceTrees")
        }
    }
}

// Lite sürümü normal uygulamanın sürüm sayacı ve OTA yayınından bağımsızdır.
val liteVersion = Properties().apply {
    rootProject.file("lite-version.properties").inputStream().use { load(it) }
}
androidComponents {
    onVariants(selector().withBuildType("lite")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(liteVersion.getProperty("versionCode").toInt())
            output.versionName.set(liteVersion.getProperty("versionName"))
        }
    }
}

// jvmToolchain YERİNE doğrudan hedef: makinede kurulu JDK 21, toolchain 17
// istemek ayrıca bir JDK indirmeyi/sağlamayı gerektiriyordu.
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    // ui2 (Faz 2a): gerçek back-stack için Navigation Compose. Navigation3'e
    // geçiş bilinçli olarak AYRI tutuldu (16.08.2026): Ui2Root çalışıyor,
    // kazancı düşük, riski tüm gezinme yüzeyi.
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.compose.material3:material3")
    // ui3 (liquid glass) cam malzemesi. Compose'da arka plan bulanıklığının
    // hazır API'si YOK: Modifier.blur bileşenin kendi içeriğini bulanıklaştırır,
    // arkasındakini değil. Haze 1.x bunu Compose 1.7+ GraphicsLayer API'siyle
    // yapıyor (bizde 1.10.x var). Gerçek blur RenderEffect'e dayandığı için
    // API 31 altında scrim'e düşer — minSdk 26 olduğundan bu fark bilinçli.
    // Sürüm Maven Central'da yayımlanmış en yükseği (16.08.2026); tahminle
    // değil, listeden seçildi.
    implementation("dev.chrisbanes.haze:haze:1.5.3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime:2.10.5")
    // Room artık KSP ile: kapt Kotlin 2.x'te ek bir stub üretme adımı ve
    // yavaşlık demek, Room'un kendi önerisi de KSP.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Video oynatma (yerel LTX-Video çıktısı ve gezgindeki mp4'ler).
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    // Compose yerleşim sözleşmesi ihlalleri (ör. iç içe horizontalScroll) yalnız
    // ÖLÇÜM anında patlıyor; JVM birim testi bunu göremez, çökme cihazda oluyor.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
