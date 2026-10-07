package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFocusTest {
    @Test
    fun suppressesWhenViewingThatChat() {
        val key = notificationKey("claude-app", "s1")
        assertTrue(suppressesNotification(foreground = true, openKey = key, backend = "claude-app", sessionId = "s1"))
    }

    @Test
    fun notifiesWhenViewingAnotherChat() {
        val key = notificationKey("claude-app", "s1")
        assertFalse(suppressesNotification(foreground = true, openKey = key, backend = "claude-app", sessionId = "s2"))
    }

    // Aynı oturum kimliği başka backend'de de görülebilir; anahtar ikisini birlikte taşır.
    @Test
    fun notifiesWhenSameSessionIdOnAnotherBackend() {
        val key = notificationKey("claude-app", "s1")
        assertFalse(suppressesNotification(foreground = true, openKey = key, backend = "codex-app", sessionId = "s1"))
    }

    @Test
    fun notifiesWhenAppInBackground() {
        val key = notificationKey("claude-app", "s1")
        assertFalse(suppressesNotification(foreground = false, openKey = key, backend = "claude-app", sessionId = "s1"))
    }

    // Sohbet listesindeyken (açık oturum yok) her bildirim gelmeli.
    @Test
    fun notifiesWhenNoChatOpen() {
        assertFalse(suppressesNotification(foreground = true, openKey = "", backend = "claude-app", sessionId = "s1"))
    }

    // Oturumsuz olay bastırılmamalı: eşleşme kurulamıyorsa kullanıcıyı uyar.
    @Test
    fun notifiesWhenEventHasNoSession() {
        val key = notificationKey("claude-app", "s1")
        assertFalse(suppressesNotification(foreground = true, openKey = key, backend = "claude-app", sessionId = ""))
    }

    @Test
    fun keyCombinesBackendAndSession() {
        assertEquals("claude-app:s1", notificationKey("claude-app", "s1"))
    }
}
