package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModelsTest {

    @Test
    fun projectAndCoworkWorkspaceWithSamePathMergeIntoOneItem() {
        val project = project(id = "p1", path = "C:\\work\\alpha", displayName = "Alpha etiketi")
        val workspace = CoworkWorkspace(name = "alpha", path = "c:/work/alpha/", matter = "Dava 12")
        val items = RemoteUiState().copy(
            projectSummaries = listOf(project),
            cowork = CoworkUiState(workspaces = listOf(workspace)),
        ).hubProjects()

        assertEquals(1, items.size)
        assertEquals("p1", items[0].projectId)
        assertEquals("c:/work/alpha/", items[0].workspacePath)
        assertEquals("Alpha etiketi", items[0].title)
        assertEquals("Dava 12", items[0].matter)
        assertTrue(items[0].isCoworkWorkspace)
    }

    @Test
    fun coworkOnlyWorkspaceUsesMatterAsTitle() {
        val items = RemoteUiState().copy(
            cowork = CoworkUiState(workspaces = listOf(CoworkWorkspace("ham-ad", "C:/cowork/ham-ad", "Örnek dosyası")))
        ).hubProjects()

        assertEquals(1, items.size)
        assertEquals(null, items[0].projectId)
        assertEquals("Örnek dosyası", items[0].title)
        assertEquals("C:/cowork/ham-ad", items[0].workspacePath)
    }

    @Test
    fun projectUnderCoworkSpacesFolderCountsAsWorkspaceWithoutRegistryEntry() {
        val items = RemoteUiState().copy(
            projectSummaries = listOf(project("p1", "C:\\Users\\Dev\\CoworkSpaces\\icy-urchin", "icy-urchin"))
        ).hubProjects()

        assertEquals(1, items.size)
        assertEquals(null, items[0].workspacePath)
        assertTrue(items[0].isCoworkWorkspace)
    }

    @Test
    fun itemsWithNewDeliveriesAndRunningSessionsSortFirst() {
        val quiet = project(id = "quiet", path = "C:/quiet", displayName = "A sessiz")
        val running = project(id = "running", path = "C:/running", displayName = "Z çalışan", running = 1)
        val fresh = project(id = "fresh", path = "C:/fresh", displayName = "M yeni", newOutputs = 2)

        val items = RemoteUiState().copy(projectSummaries = listOf(quiet, running, fresh)).hubProjects()

        assertEquals(listOf("fresh", "running", "quiet"), items.map { it.projectId })
    }

    @Test
    fun hubSummaryCombinesCountsAndDetectsUnseenEvent() {
        val operations = OperationsResult(
            events = listOf(OperationEvent("e2", "completed", "done", "agy", "Agy", "s", "C:/p", "m", "ok", "now")),
            counts = OperationCounts(running = 2, waiting = 1, failed = 3),
        )
        val state = RemoteUiState().copy(
            projectSummaries = listOf(project("p", "C:/p", "P", outputs = 5, newOutputs = 2)),
            cowork = CoworkUiState(workspaces = listOf(CoworkWorkspace("extra", "C:/extra"))),
            operations = operations,
            usage = UsageResult(listOf(UsageGroup("Claude", "", emptyList())), ""),
            lastSeenOperationEventId = "e1",
        )

        val summary = state.hubSummary()
        assertEquals(2, summary.projectCount)
        assertEquals(2, summary.runningCount)
        assertEquals(1, summary.waitingCount)
        assertEquals(3, summary.failedCount)
        assertEquals(5, summary.deliveryCount)
        assertEquals(2, summary.newDeliveryCount)
        assertEquals(1, summary.usageGroupCount)
        assertTrue(summary.hasUnseenOperationEvents)
    }

    @Test
    fun teslimatRozetiKaynaginaGoreAyrilir() {
        // Canli hata: cowork calisma alanindaki teslimatlar "Projeler" satirinda
        // rozet oluyordu, ama Projeler listesi calisma alanlarini ICERMIYOR —
        // rozete dokunup giren kullanici hicbir teslimat gormuyordu.
        val state = RemoteUiState().copy(
            projectSummaries = listOf(
                project("p", "C:/p", "Proje", outputs = 4, newOutputs = 1),
            ),
            // Cowork workspace'i: hubProjects() bunu isCoworkWorkspace olarak uretir.
            cowork = CoworkUiState(workspaces = listOf(CoworkWorkspace("alan", "C:/coworkspaces/alan"))),
        )
        val summary = state.hubSummary()
        // Proje rozeti yalniz gercek projeleri sayar.
        assertEquals(1, summary.newDeliveryCount)
        // Calisma alani teslimatlari kendi rozetinde (bu ornekte 0).
        assertEquals(0, summary.workspaceNewDeliveryCount)
        // Toplam teslimat sayisi ayrilmadan once neyse o kalir.
        assertEquals(4, summary.deliveryCount)
    }

    @Test
    fun matchingLastSeenEventIsNotUnseen() {
        val event = OperationEvent("e1", "completed", "done", "agy", "Agy", "s", "", "", "", "")
        val summary = RemoteUiState().copy(
            operations = OperationsResult(events = listOf(event)),
            lastSeenOperationEventId = "e1",
        ).hubSummary()

        assertFalse(summary.hasUnseenOperationEvents)
    }

    @Test
    fun hubProjectsSortingLogic() {
        val p1 = project(id = "p1", path = "C:/p1", displayName = "Alpha", pinned = false, lastActivityAt = "2026-07-11T12:00:00Z")
        val p2 = project(id = "p2", path = "C:/p2", displayName = "Beta", pinned = true, lastActivityAt = "2026-07-10T12:00:00Z")
        val p3 = project(id = "p3", path = "C:/p3", displayName = "Gamma", pinned = false, lastActivityAt = "2026-07-12T12:00:00Z")

        val state = RemoteUiState().copy(projectSummaries = listOf(p1, p2, p3))
        val projects = state.hubProjects()

        // p2 is pinned, so it should sort first.
        // p3 has lastActivityAt "2026-07-12", p1 has "2026-07-11". So p3 sorts before p1.
        assertEquals("p2", projects[0].projectId)
        assertEquals("p3", projects[1].projectId)
        assertEquals("p1", projects[2].projectId)
    }

    @Test
    fun filterHubProjectsLogic() {
        val p1 = HubProjectItem("k1", "p1", null, "C:/p1", "Alpha Project", "Matter Alpha", true, 1, 0, 1, 0, listOf("c1"), false, "", "2026-07-11")
        val p2 = HubProjectItem("k2", "p2", null, "C:/p2", "Beta Project", "Matter Beta", true, 1, 1, 1, 0, listOf("c1"), true, "", "2026-07-10")
        val p3 = HubProjectItem("k3", "k3", null, "C:/p3", "Gamma Project", "Matter Gamma", true, 1, 0, 1, 2, listOf("c1"), false, "", "2026-07-09")
        val items = listOf(p1, p2, p3)

        // 1. ALL filter
        assertEquals(3, filterHubProjects(items, ProjectFilter.ALL, "").size)

        // 2. PINNED filter
        val pinned = filterHubProjects(items, ProjectFilter.PINNED, "")
        assertEquals(1, pinned.size)
        assertEquals("p2", pinned[0].projectId)

        // 3. ACTIVE filter
        val active = filterHubProjects(items, ProjectFilter.ACTIVE, "")
        assertEquals(1, active.size)
        assertEquals("p2", active[0].projectId)

        // 4. NEW_DELIVERY filter
        val newDelivery = filterHubProjects(items, ProjectFilter.NEW_DELIVERY, "")
        assertEquals(1, newDelivery.size)
        assertEquals("k3", newDelivery[0].projectId)

        // 5. Query filter case-insensitive
        val queryResult = filterHubProjects(items, ProjectFilter.ALL, "beta")
        assertEquals(1, queryResult.size)
        assertEquals("p2", queryResult[0].projectId)

        // 6. Query matching matter
        val queryMatter = filterHubProjects(items, ProjectFilter.ALL, "gamma")
        assertEquals(1, queryMatter.size)
        assertEquals("k3", queryMatter[0].projectId)
    }

    private fun project(
        id: String,
        path: String,
        displayName: String,
        running: Int = 0,
        outputs: Int = 0,
        newOutputs: Int = 0,
        pinned: Boolean = false,
        lastActivityAt: String = "",
    ) = ProjectSummary(
        id = id,
        path = path,
        name = path.substringAfterLast('/').substringAfterLast('\\'),
        displayName = displayName,
        exists = true,
        sessionCount = 1,
        runningCount = running,
        outputCount = outputs,
        newOutputCount = newOutputs,
        providers = listOf("codex-app"),
        pinned = pinned,
        lastOpenedAt = "",
        lastActivityAt = lastActivityAt,
        quickStartProvider = "",
        quickStartModel = "",
        quickStartPermissionMode = "",
        quickStartEffort = "",
    )
}
