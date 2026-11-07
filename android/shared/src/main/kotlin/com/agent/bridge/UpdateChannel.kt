package com.agent.bridge

enum class UpdateChannel(val manifestPath: String, val apkPath: String) {
    STANDARD("/update/latest.json", "/update/app-latest.apk"),
    LITE("/update/lite/latest.json", "/update/lite/app-latest.apk");

    fun validateManifest(applicationId: String, apkPath: String, expectedApplicationId: String) {
        if (this == LITE) {
            require(applicationId == expectedApplicationId) { "Güncelleme bu uygulamaya ait değil" }
            require(apkPath == this.apkPath) { "Güncelleme bu kanala ait değil" }
        }
    }
}
