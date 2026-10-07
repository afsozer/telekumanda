plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // jvmToolchain(17) yerine doğrudan hedef: makinede JDK 21 kurulu, ayrı bir
    // 17 toolchain'i indirmeye gerek yok.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Faz 1: saf mantık + ağ katmanı. Android framework'ündeki org.json yerine
    // Maven Central'dan açık bağımlılık (Android app testleri zaten bunu kullanıyordu).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    // Compose runtime annotation'ları (@Immutable, @Stable) düz JVM'de de kullanılır
    // (RemoteUiState bunları işaretli). Runtime implementasyonu UI tarafından sağlanır.
    implementation("androidx.compose.runtime:runtime:1.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
