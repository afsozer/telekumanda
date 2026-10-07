package com.agent.bridge.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSwipeLogicTest {

    @Test
    fun rightSwipeOpensSessions() {
        assertEquals(
            ChatSwipeAction.Sessions,
            chatSwipeAction(distanceX = 120f, threshold = 100f, coworkFilesAvailable = true),
        )
    }

    @Test
    fun leftSwipeOpensCoworkFiles() {
        assertEquals(
            ChatSwipeAction.CoworkFiles,
            chatSwipeAction(distanceX = -120f, threshold = 100f, coworkFilesAvailable = true),
        )
    }

    // Kenar şartı kalktı: jestin ekranın neresinden başladığı kararı etkilemez.
    // Eskiden yalnız ilk/son 56dp'de çalışıyordu; artık mesafe tek ölçüt.
    @Test
    fun sameDistanceWorksRegardlessOfWhereSwipeStarted() {
        repeat(3) {
            assertEquals(
                ChatSwipeAction.Sessions,
                chatSwipeAction(distanceX = 120f, threshold = 100f, coworkFilesAvailable = true),
            )
        }
    }

    @Test
    fun shortSwipeBelowThresholdDoesNothing() {
        assertEquals(
            ChatSwipeAction.None,
            chatSwipeAction(distanceX = 80f, threshold = 100f, coworkFilesAvailable = true),
        )
        assertEquals(
            ChatSwipeAction.None,
            chatSwipeAction(distanceX = -80f, threshold = 100f, coworkFilesAvailable = true),
        )
    }

    @Test
    fun leftSwipeIgnoredWithoutCoworkWorkspace() {
        assertEquals(
            ChatSwipeAction.None,
            chatSwipeAction(distanceX = -120f, threshold = 100f, coworkFilesAvailable = false),
        )
    }

    // Geniş yerleşimde çekmece SAĞDAN açılıyor; soldan sağa çekmek onu ters
    // yönden açıyordu, o yüzden jest orada kapalı.
    @Test
    fun rightSwipeDoesNothingInWideLayout() {
        assertEquals(
            ChatSwipeAction.None,
            chatSwipeAction(
                distanceX = 120f,
                threshold = 100f,
                coworkFilesAvailable = true,
                sessionsSwipeEnabled = false,
            ),
        )
    }

    // Kapatılan YALNIZ oturum jesti: Cowork dosyaları sağdan sola çalışmaya devam eder.
    @Test
    fun coworkSwipeSurvivesInWideLayout() {
        assertEquals(
            ChatSwipeAction.CoworkFiles,
            chatSwipeAction(
                distanceX = -120f,
                threshold = 100f,
                coworkFilesAvailable = true,
                sessionsSwipeEnabled = false,
            ),
        )
    }

    // Dar yerleşim (telefon dikey) etkilenmedi: çekmece hâlâ soldan açılıyor.
    @Test
    fun rightSwipeStillOpensSessionsInCompactLayout() {
        assertEquals(
            ChatSwipeAction.Sessions,
            chatSwipeAction(
                distanceX = 120f,
                threshold = 100f,
                coworkFilesAvailable = true,
                sessionsSwipeEnabled = true,
            ),
        )
    }
}
