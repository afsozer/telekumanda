package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChannelTest {
    @Test
    fun standardPathsAndOldManifestRemainSupported() {
        assertEquals("/update/latest.json", UpdateChannel.STANDARD.manifestPath)
        assertEquals("/update/app-latest.apk", UpdateChannel.STANDARD.apkPath)
        UpdateChannel.STANDARD.validateManifest("", "/update/app-latest.apk", "com.agent.bridge")
    }

    @Test
    fun liteAcceptsItsOwnManifest() {
        assertEquals("/update/lite/latest.json", UpdateChannel.LITE.manifestPath)
        UpdateChannel.LITE.validateManifest("com.agent.bridge.lite", "/update/lite/app-latest.apk", "com.agent.bridge.lite")
    }

    @Test(expected = IllegalArgumentException::class)
    fun liteRejectsStandardApplication() {
        UpdateChannel.LITE.validateManifest("com.agent.bridge", "/update/lite/app-latest.apk", "com.agent.bridge.lite")
    }

    @Test(expected = IllegalArgumentException::class)
    fun liteRejectsStandardApkEvenWhenApplicationIdIsSpoofed() {
        UpdateChannel.LITE.validateManifest("com.agent.bridge.lite", "/update/app-latest.apk", "com.agent.bridge.lite")
    }
}
