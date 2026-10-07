package com.agent.bridge

enum class UpdateChannel(val manifestPath: String, val apkPath: String) {
    STANDARD("/update/latest.json", "/update/app-latest.apk"),
    LITE("/update/lite/latest.json", "/update/lite/app-latest.apk");

    // Manifestteki `apkPath` HER kanalda kanalın sabit yoluyla birebir aynı
    // olmalı: indirme adresi bu alandan kuruluyor ve köprü (ya da araya giren
    // biri) başka bir yol yazarsa uygulama başka bir dosyayı güncelleme diye
    // indirirdi. applicationId yalnız Lite'ta zorunlu; standart kanalın eski
    // manifesti bu alanı hiç taşımıyor.
    fun validateManifest(applicationId: String, apkPath: String, expectedApplicationId: String) {
        if (this == LITE) {
            require(applicationId == expectedApplicationId) { "Güncelleme Lite uygulamasına ait değil" }
        }
        require(apkPath == this.apkPath) {
            if (this == LITE) "Güncelleme Lite kanalına ait değil" else "Güncelleme paketi beklenen yolda değil"
        }
    }
}
