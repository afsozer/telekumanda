package com.agent.bridge

/**
 * Güncelleme meta-bilgisi. UpdateManager (Android, indirme+APK kurma) ve
 * UpdateDelegate (shared, sunucudan metadata okuma) tarafından paylaşılır.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)
