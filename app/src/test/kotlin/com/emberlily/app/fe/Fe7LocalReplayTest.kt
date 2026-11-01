package com.emberlily.app.fe

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real local captures, identified by complete state hashes; no ROM/state bytes are distributed. */
class Fe7LocalReplayTest {
    @Test fun r02DialogueAndPlayerIdleDecodeAndOnlyIdleProducesAnHpPlan() {
        val romPath = System.getenv("EMBERLILY_FE7J_ROM")
        val replayPath = System.getenv("EMBERLILY_FE7_REPLAY_DIR")
        assumeTrue("Set registered R02 ROM and FE7 replay directory", !romPath.isNullOrBlank() && !replayPath.isNullOrBlank())
        val profile = RomProfiles.fe7j
        val layout = profile.layout
        val catalog = FeRomCatalog.load(profile, File(checkNotNull(romPath)).readBytes())
        val directory = File(checkNotNull(replayPath))
        val cases = mapOf(
            "r02-dialog.state" to "0ae4e91523ecebd7545af1e2e472acc4e68982ec54dbe8fa15cc3f1f500b5c5b",
            "r02-movement-tutorial.state" to "31bb62fe7da8925a63b73386e543a7d71a1bdddfbd612882c9974c91c9dc4c62",
            "r02-agent-movement.state" to "9376763c8e7d284901acc76994e960e83004aeec6cb26165166f32ea8153a334",
            "r02-01.state" to "b3de6c97f99fbd50246e378af2b4925a56eff32c000d3dc3e12c2986f18c61cd",
        )
        for ((name, hash) in cases) {
            val snapshot = state(File(directory, name), hash, profile)
            val decoder = FeDecoder(profile, catalog)
            val decoded = decoder.decodeRaw(snapshot)
            assertEquals(name, FeScene.MAP, decoded.scene)
            assertEquals(0, decoded.chapter); assertEquals(2, decoded.turn); assertEquals(0, decoded.phase)
            assertEquals(15, decoded.mapWidth); assertEquals(10, decoded.mapHeight)
            val movement = name == "r02-agent-movement.state"
            assertEquals(MapPosition(if (movement) 7 else 8, 7), decoded.cursor)
            assertEquals(listOf(1, 0x81, 0x82), decoded.units.map { it.slot })
            assertEquals(listOf(3, 135, 136), decoded.units.map { it.characterId })
            assertEquals(listOf(16, 21, 20), decoded.units.map { it.hp })
            assertEquals(listOf("リン", "バッタ", "山賊"), decoded.units.map { it.name.text })
            val lyn = decoded.units.single { it.slot == 1 }
            assertEquals(2, lyn.classId); assertEquals("ロード", lyn.className.text)
            assertEquals(1, lyn.level); assertEquals(0, lyn.experience)
            assertEquals(16, lyn.maxHp); assertEquals(8, lyn.x); assertEquals(7, lyn.y)
            assertEquals(listOf(0x2E01, 0x036B, 0x036B, 0, 0), lyn.items.map { it.raw })
            assertEquals(listOf("てつの剣", "きずぐすり", "きずぐすり", "空", "空"), lyn.items.map { it.name.text })
            assertEquals(listOf(0x2D1F, 0, 0, 0, 0), decoded.units.single { it.slot == 0x81 }.items.map { it.raw })
            assertEquals(listOf(0x2D1F, 0, 0, 0, 0), decoded.units.single { it.slot == 0x82 }.items.map { it.raw })
            if (movement) assertNull(decoded.focusedUnit) else assertEquals(lyn, decoded.focusedUnit)
            // Neither an enemy pointer retained after an event nor a newly shown movement overlay
            // grants a usable selection while tutorial dialogue still owns the game.
            assertEquals(checkNotNull(layout.unitAddress(if (movement || name == "r02-movement-tutorial.state") 1 else 0x82)), snapshot.u32(layout.activeUnit))
            if (movement) { assertEquals(1, decoded.selectedSlot); assertEquals(lyn, decoded.selectedUnit) }
            else { assertNull(decoded.selectedSlot); assertNull(decoded.selectedUnit) }
            val replay = SnapshotCodec.decode(SnapshotCodec.encode(snapshot), profile.identity.sha256)
            assertEquals(decoded, decoder.decodeRaw(replay))
            val request = HpEditRequest(snapshot.stamp, lyn.identity, lyn.classId, 16, 15)
            if (name != "r02-01.state") {
                assertEquals(if (movement) 0 else 1, snapshot.u8(layout.bmState + 1))
                assertEquals(!movement, 0x08BFFF78L in decoded.activeProcScripts)
                if (name == "r02-dialog.state") assertFalse(layout.playerPhaseScript in decoded.activeProcScripts)
                else {
                    assertEquals(3, snapshot.u8(layout.bmState + 4))
                    val player = FeProcesses.read(snapshot, layout) { catalog.contains(it, 8) }.single { it.script == layout.playerPhaseScript }
                    assertEquals(if (movement) 0x08C026E0L else 0x08C026D8L, snapshot.u32(player.address + 4))
                    assertEquals(if (movement) 0x0801CACDL else 0L, snapshot.u32(player.address + 0x0C))
                }
                val failure = assertThrows(IllegalArgumentException::class.java) { FeResearchCommands.planHp(request, snapshot, decoded) }
                if (name == "r02-dialog.state") assertEquals("游戏正在处理事件或动作", failure.message)
            } else {
                assertVerifiedIdle(snapshot, profile)
                val plan = FeResearchCommands.planHp(request, snapshot, decoded)
                assertEquals(16, plan.beforeHp); assertEquals(15, plan.afterHp)
                assertEquals(lyn.address, plan.unitAddress)
                assertEquals(6, plan.patches.size)
                val edits = plan.patches.filter { !it.before.contentEquals(it.after) }
                assertEquals(1, edits.size)
                val patch = edits.single()
                assertEquals(lyn.address, patch.address)
                assertEquals(listOf(0x13), patch.before.indices.filter { patch.before[it] != patch.after[it] })
                assertEquals(16, patch.before[0x13].toInt()); assertEquals(15, patch.after[0x13].toInt())
                assertEquals(16, snapshot.u8(lyn.address + 0x13)) // A plan never changes its captured input.
            }
        }
    }

    @Test fun r06TitleDoesNotTurnZeroFilledRamIntoABattle() {
        val romPath = System.getenv("EMBERLILY_FE7J_CHINESE_ROM")
        val replayPath = System.getenv("EMBERLILY_FE7_REPLAY_DIR")
        assumeTrue("Set registered R06 ROM and FE7 replay directory", !romPath.isNullOrBlank() && !replayPath.isNullOrBlank())
        val profile = RomProfiles.fe7jChinese
        val catalog = FeRomCatalog.load(profile, File(checkNotNull(romPath)).readBytes())
        val snapshot = state(File(checkNotNull(replayPath), "r06-title.state"),
            "3b5788277a6d46212814cb7deae2be2e85d58d07f502c801df48e98c6bbca639", profile)
        val decoded = FeDecoder(profile, catalog).decodeRaw(snapshot)
        assertEquals(FeScene.UNAVAILABLE, decoded.scene)
        assertNull(decoded.chapter); assertNull(decoded.turn); assertNull(decoded.phase); assertNull(decoded.cursor)
        assertNull(decoded.mapWidth); assertNull(decoded.mapHeight); assertNull(decoded.selectedSlot)
        assertTrue(decoded.units.isEmpty())
        assertFalse(profile.layout.mapTaskScript in decoded.activeProcScripts)
        val replay = SnapshotCodec.decode(SnapshotCodec.encode(snapshot), profile.identity.sha256)
        assertEquals(decoded, FeDecoder(profile, catalog).decodeRaw(replay))
    }

    @Test fun r06OwnIdleAllowsHpPlanningWhileItsOpeningAndMovementTutorialRemainBlocked() {
        val romPath = System.getenv("EMBERLILY_FE7J_CHINESE_ROM")
        val replayPath = System.getenv("EMBERLILY_FE7_REPLAY_DIR")
        assumeTrue("Set registered R06 ROM and FE7 replay directory", !romPath.isNullOrBlank() && !replayPath.isNullOrBlank())
        val profile = RomProfiles.fe7jChinese
        val layout = profile.layout
        val catalog = FeRomCatalog.load(profile, File(checkNotNull(romPath)).readBytes())
        val directory = File(checkNotNull(replayPath))
        val cases = mapOf(
            "r06-agent-dialog-intro.state" to "89515f5111d74bbf400de3873048d4dcdb003193b7eb445416ebb10dccd9abff",
            "r06-agent-idle.state" to "34ea61fcf0a4ac24594d65d6dd8929dd44cb05f24fd35e601d127452b9a0faeb",
            "r06-agent-selection.state" to "147e8e0099d84a1e9f914017f76b774b05178bb9b5e2fd6ce6a6419f21f55053",
            "r06-agent-movement.state" to "a61310edd23ff068bf38d91df3099d413ef10c4051dbd80d7b06518c76c691b3",
        )
        for ((name, hash) in cases) {
            val snapshot = state(File(directory, name), hash, profile)
            val decoded = FeDecoder(profile, catalog).decodeRaw(snapshot)
            assertEquals(name, FeScene.MAP, decoded.scene)
            assertEquals(0, decoded.chapter); assertEquals(1, decoded.turn); assertEquals(0, decoded.phase)
            assertEquals(15, decoded.mapWidth); assertEquals(10, decoded.mapHeight)
            val movement = name == "r06-agent-movement.state"
            if (movement) assertEquals(1, decoded.selectedSlot) else assertNull(decoded.selectedSlot)
            assertTrue("Chinese names must not inherit Japanese cached text", decoded.units.all { it.name.text == null && it.className.text == null })
            assertTrue(decoded.units.flatMap { it.items }.filter { it.itemId != 0 }.all { it.name.text == null })
            val opening = name == "r06-agent-dialog-intro.state"
            assertEquals(if (opening) listOf(0x81, 0x82) else listOf(1, 0x81, 0x82), decoded.units.map { it.slot })
            assertEquals(if (opening) MapPosition(0, 0) else MapPosition(if (movement) 12 else 13, 7), decoded.cursor)
            val boss = decoded.units.single { it.slot == 0x81 }
            assertEquals(135, boss.characterId); assertEquals(57, boss.classId)
            assertEquals(21, boss.hp); assertEquals(3, boss.x); assertEquals(2, boss.y)
            val bandit = decoded.units.single { it.slot == 0x82 }
            assertEquals(136, bandit.characterId); assertEquals(20, bandit.hp)
            assertEquals(2, bandit.x); assertEquals(6, bandit.y)
            assertEquals(listOf(0x2D1F, 0, 0, 0, 0), boss.items.map { it.raw })
            assertEquals(listOf(0x2D1F, 0, 0, 0, 0), bandit.items.map { it.raw })
            val target = if (opening) boss else decoded.units.single { it.slot == 1 }.also { lyn ->
                assertEquals(3, lyn.characterId); assertEquals(2, lyn.classId)
                assertEquals(16, lyn.hp); assertEquals(16, lyn.maxHp)
                assertEquals(1, lyn.level); assertEquals(0, lyn.experience)
                assertEquals(13, lyn.x); assertEquals(7, lyn.y)
                assertEquals(listOf(0x2E01, 0x036B, 0x036B, 0, 0), lyn.items.map { it.raw })
            }
            val request = HpEditRequest(snapshot.stamp, target.identity, target.classId, target.hp, target.hp - 1)
            if (name == "r06-agent-idle.state") {
                assertVerifiedIdle(snapshot, profile, 0x02025330)
                assertEquals(0L, snapshot.u32(layout.activeUnit))
                val plan = FeResearchCommands.planHp(request, snapshot, decoded)
                assertEquals(16, plan.beforeHp); assertEquals(15, plan.afterHp)
                assertEquals(target.address, plan.unitAddress)
                val edits = plan.patches.filter { !it.before.contentEquals(it.after) }
                assertEquals(1, edits.size)
                assertEquals(listOf(0x13), edits.single().before.indices.filter { edits.single().before[it] != edits.single().after[it] })
                assertEquals(16, snapshot.u8(target.address + 0x13))
            } else {
                assertEquals(if (movement) 0 else 1, snapshot.u8(layout.bmState + 1))
                assertEquals(!movement, 0x08BFFF78L in decoded.activeProcScripts)
                if (!opening) {
                    val player = FeProcesses.read(snapshot, layout) { catalog.contains(it, 8) }.single { it.script == layout.playerPhaseScript }
                    assertEquals(if (movement) 0x08C026E0L else 0x08C026D8L, snapshot.u32(player.address + 4))
                    assertEquals(if (movement) 0x0801CACDL else 0L, snapshot.u32(player.address + 0x0C))
                    assertEquals(3, snapshot.u8(layout.bmState + 4))
                    assertEquals(target.address, snapshot.u32(layout.activeUnit))
                    if (movement) {
                        assertEquals(target, decoded.selectedUnit)
                        assertNull("Cursor moved off Lyn; focused and selected units are different concepts", decoded.focusedUnit)
                    }
                }
                assertThrows(IllegalArgumentException::class.java) { FeResearchCommands.planHp(request, snapshot, decoded) }
            }
            val replay = SnapshotCodec.decode(SnapshotCodec.encode(snapshot), profile.identity.sha256)
            assertEquals(decoded, FeDecoder(profile, catalog).decodeRaw(replay))
        }
    }

    private fun assertVerifiedIdle(snapshot: MemorySnapshot, profile: RomProfile, playerAddress: Long = 0x02025258) {
        val layout = profile.layout
        val contract = checkNotNull(layout.idle)
        for (offset in listOf(1, 2, 4)) assertEquals(0, snapshot.u8(layout.bmState + offset))
        val processes = FeProcesses.read(snapshot, layout) {
            it % 4L == 0L && it in 0x08000000L..(0x08000000L + profile.identity.size - 8)
        }
        assertTrue(processes.none { it.script in contract.blockedScripts })
        val player = processes.single { it.script == layout.playerPhaseScript }
        assertEquals(playerAddress, player.address)
        assertEquals(0x08C026A0L, snapshot.u32(player.address + 4))
        assertEquals(0x0801C625L, snapshot.u32(player.address + 0x0C))
        assertEquals(contract.cursor, snapshot.u32(player.address + 4))
        assertEquals(contract.callback, snapshot.u32(player.address + 0x0C))
        assertEquals(0, player.lock); assertEquals(0, snapshot.s16(player.address + 0x24))
        assertEquals(0L, snapshot.u32(player.address + 0x18))
        val parent = processes.single { it.address == snapshot.u32(player.address + 0x14) }
        assertEquals(layout.bmapMainScript, parent.script); assertEquals(contract.parentLock, parent.lock)
        assertEquals(player.address, snapshot.u32(parent.address + 0x18))
        assertEquals(0, processes.single { it.script == layout.mapTaskScript }.lock)
    }

    private fun state(file: File, expectedSha: String, profile: RomProfile): MemorySnapshot {
        val bytes = file.readBytes()
        assertEquals("Local capture changed: ${file.name}", expectedSha, sha256(bytes))
        require(bytes.size >= 128 + 0x61000 && String(bytes, 0, 8, Charsets.US_ASCII) == "ELSTATE2")
        // Synthetic replay identity grants no authority to a live emulator or writable branch.
        val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 0, 0, profile.id, profile.version)
        return MemorySnapshot(stamp, listOf(
            MemorySegment(0x02000000, bytes.copyOfRange(128 + 0x21000, 128 + 0x61000)),
            MemorySegment(0x03000000, bytes.copyOfRange(128 + 0x19000, 128 + 0x21000)),
        ))
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
