// Araç zinciri (16.08.2026 yükseltmesi): Kotlin 2.3.20 + AGP 9.0.1 + Gradle
// 9.1.0. Bu üçlü aynı makinede UDF Editor Pro'da çalışıyordu; oradan alındı.
//
// Eski hat (Kotlin 1.9.24 / AGP 8.6.1 / Compose 1.6) birçok bağımlılığı
// çakılı tutuyordu — WorkManager 2.9.1, media3 1.3.1, Room 2.6.1 hep "daha
// yenisi Kotlin 2.x metadata'sı istiyor" diye sabitlenmişti. Yükseltme bu
// kilidi açtı.
//
// Compose derleyicisi artık ayrı bir eklenti (kotlinCompilerExtensionVersion
// yok); Room ise kapt yerine KSP kullanıyor.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    // kotlin.android YOK: AGP 9'dan itibaren Android modüllerinde Kotlin desteği
    // AGP'nin içinde geliyor, ayrıca uygulanırsa yapılandırma hatası veriyor.
    // Saf JVM modülü (:shared) hâlâ kotlin.jvm kullanır.
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    // KSP artık Kotlin sürümüne bağlı `<kotlin>-<ksp>` şemasını kullanmıyor,
    // kendi sürüm hattı var.
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
