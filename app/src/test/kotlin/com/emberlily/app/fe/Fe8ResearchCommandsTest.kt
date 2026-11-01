package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class Fe8ResearchCommandsTest {
    private class Fixture(val profile: RomProfile = RomProfiles.fe8j) {
        val layout = profile.layout
        val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef", "11111111-1111-1111-1111-111111111111", 2, 100, 0, profile.id)
        val ewram = ByteArray(0x40000)
        val iwram = ByteArray(0x8000)
        val parent = layout.procPool
        val player = parent + layout.procSize
        val renderer = player + layout.procSize
        val address = layout.units.first().address
        val identity = UnitIdentity(stamp.copy(frame = 0), 1, 1, 11, layout.characterTable)
        var unit = FeUnit(identity, 1, address, 1, 1, FeName(0x199, "synthetic"), FeName(1, "synthetic"),
            0, 4, 6, 1, 0, 16, 13, 4, 4, 4, 4, 4, 4, 0, emptyList())
        init {
            write(layout.playState + 0x0E, 1, 1)
            write(layout.procRoots + 8, parent)
            write(layout.procRoots + 16, renderer)
            write(parent, layout.bmapMainScript)
            write(parent + 0x18, player)
            write(parent + 0x28, 1, 1)
            write(player, layout.playerPhaseScript)
            val idle = checkNotNull(layout.idle)
            write(player + 4, idle.cursor)
            write(player + 0x0C, idle.callback)
            write(player + 0x14, parent)
            write(renderer, layout.mapTaskScript)
            write(address, layout.characterTable)
            write(address + 4, layout.classTable + layout.classSize)
            write(address + 0x0B, 1, 1)
            write(address + 0x10, 4, 1)
            write(address + 0x11, 6, 1)
            write(address + 0x12, 16, 1)
            write(address + 0x13, 13, 1)
        }
        fun write(address: Long, value: Long, count: Int = 4) {
            val buffer = if (address >= 0x03000000) iwram else ewram
            val base = if (address >= 0x03000000) 0x03000000L else 0x02000000L
            repeat(count) { buffer[(address - base).toInt() + it] = (value ushr (it * 8)).toByte() }
        }
        fun capture(current: SnapshotStamp = stamp) = MemorySnapshot(current, listOf(MemorySegment(0x02000000, ewram), MemorySegment(0x03000000, iwram)))
        fun decoded(current: SnapshotStamp = stamp) = FeBattleSnapshot(current, FeScene.MAP, chapter = 1, turn = 2, phase = 0,
            mapWidth = 15, mapHeight = 10, units = listOf(unit.copy(identity = unit.identity.copy(generation = 0))))
        fun request() = HpEditRequest(stamp.copy(frame = 96), identity, 1, 13, 14)
        fun plan(request: HpEditRequest = request()) = Fe8ResearchCommands.planHp(request, capture(), decoded())
    }

    @Test fun validPlanOnlyChangesHpAndGuardsTheWholeRecordAndTiming() {
        val fixture = Fixture()
        val plan = fixture.plan()
        assertEquals(13, plan.beforeHp)
        assertEquals(14, plan.afterHp)
        assertEquals(fixture.address, plan.unitAddress)
        val write = plan.patches.first()
        assertEquals(Fe8JLayout.UNIT_SIZE, write.length)
        assertEquals(listOf(0x13), write.before.indices.filter { write.before[it] != write.after[it] })
        assertEquals(14, write.after[0x13].toInt())
        assertTrue(plan.patches.drop(1).all { it.before.contentEquals(it.after) })
        assertTrue(plan.patches.sumOf { it.length } <= 16 * 1024)
        assertTrue(plan.patches.sortedBy { it.address }.zipWithNext().all { (a, b) -> a.address + a.length <= b.address })
        val leaked = write.after
        leaked[0x13] = 0
        assertEquals(14, write.after[0x13].toInt())
        assertEquals(13, fixture.capture().u8(fixture.address + 0x13))
    }

    @Test fun fe7PlanningUsesIndependentAddressesAndRejectsAnFe8IdleCallback() {
        for (profile in listOf(RomProfiles.fe7j, RomProfiles.fe7jChinese)) {
            val fixture = Fixture(profile)
            val plan = fixture.plan()
            assertEquals(0x0202BD4CL, plan.unitAddress)
            assertNotEquals(Fe8JLayout.PLAYER_UNITS, plan.unitAddress)
            assertEquals(14, plan.afterHp)
            assertEquals(setOf(fixture.address, profile.layout.procPool, profile.layout.procRoots,
                profile.layout.bmState, profile.layout.mapSize, profile.layout.activeUnit), plan.patches.map { it.address }.toSet())
            fixture.write(fixture.player + 0x0C, Fe8JLayout.PLAYER_IDLE_CALLBACK)
            assertThrows(IllegalArgumentException::class.java) { fixture.plan() }
        }
    }

    @Test fun changedContextsAndReusedOrChangedUnitTargetsReject() {
        val fixture = Fixture()
        val request = fixture.request()
        val changes = listOf(
            fixture.stamp.copy(epoch = 3), fixture.stamp.copy(revision = 1), fixture.stamp.copy(branchId = "main"),
            fixture.stamp.copy(sessionId = "22222222-2222-2222-2222-222222222222"),
            fixture.stamp.copy(romSha256 = RomProfiles.FE8J_VOICE_SHA256), fixture.stamp.copy(frame = 95),
        )
        for (current in changes) assertThrows(IllegalArgumentException::class.java) {
            Fe8ResearchCommands.planHp(request, fixture.capture(current), fixture.decoded(current))
        }
        for (changed in listOf(request.copy(classId = 2), request.copy(expectedHp = 12),
            request.copy(identity = request.identity.copy(characterAddress = request.identity.characterAddress + 0x34)),
            request.copy(identity = request.identity.copy(chapter = 2)), request.copy(identity = request.identity.copy(slot = 2)))) {
            assertThrows(IllegalArgumentException::class.java) { fixture.plan(changed) }
        }
    }

    @Test fun unsafeTargetsAndHpBoundsReject() {
        for (target in listOf(-1, 0, 13, 17, 256)) {
            val fixture = Fixture()
            assertThrows(IllegalArgumentException::class.java) { fixture.plan(fixture.request().copy(targetHp = target)) }
        }
        for (state in listOf(1L, 4L, 8L, 0x10L, 0x20L, 0x40L)) {
            val fixture = Fixture()
            fixture.unit = fixture.unit.copy(state = state)
            fixture.write(fixture.address + 0x0C, state)
            assertThrows(IllegalArgumentException::class.java) { fixture.plan() }
        }
    }

    @Test fun selectedMovementEventsMenusAndProcLockChangesReject() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.write(Fe8JLayout.BM_STATE + 1, 1, 1) },
            { it.write(Fe8JLayout.BM_STATE + 2, 1, 1) },
            { it.write(Fe8JLayout.BM_STATE + 4, 3, 1) },
            { it.write(Fe8JLayout.PLAY_STATE + 0x0E, 2, 1) },
            { it.write(Fe8JLayout.PLAY_STATE + 0x0F, 0x80, 1) },
            { it.write(it.player + 0x28, 1, 1) },
            { it.write(it.parent + 0x28, 0, 1) },
            { it.write(it.renderer + 0x28, 1, 1) },
            { it.write(it.player + 4, 0x085C3020) },
            { it.write(it.player + 0x0C, 0x0801C985) },
            { it.write(it.player + 0x24, 1, 2) },
            { it.write(it.player + 0x14, it.renderer) },
            { it.write(it.renderer, 0x085B98A0) },
        )
        for (change in changes) {
            val fixture = Fixture()
            change(fixture)
            assertThrows(IllegalArgumentException::class.java) { fixture.plan() }
        }
    }
}
