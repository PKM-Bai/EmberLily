package com.emberlily.app.fe

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exact registered ROM inputs only; no game bytes are stored with the tests. */
class FeInventoryLocalTest {
    @Test fun realWorldMapPreparationReadsInventoryWithoutBattleMapTables() {
        val rom = System.getenv("EMBERLILY_FE8J_VOICE_ROM")
        val path = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Set R04 ROM and replay directory for preparation inventory validation",
            !rom.isNullOrBlank() && !path.isNullOrBlank())
        val profile = RomProfiles.fe8jVoice
        val directory = File(checkNotNull(path))
        val prefix = "r04-wide-agent-worldmap-prep-trade"
        val ewram = File(directory, "$prefix.ewram.bin").readBytes()
        val iwram = File(directory, "$prefix.iwram.bin").readBytes()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("6d14990b9cde44df2450a2f9d46eea3c527c34c9905b7a7ae4f89518e20ee063", hash(ewram))
        assertEquals("0b76bf22b77d9e274e41da54f709f3b71cfa3c36bca480529a3ab649b1219e1e", hash(iwram))
        val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef",
            "main", 1, 100, 0, profile.id)
        fun capture() = MemorySnapshot(stamp, listOf(MemorySegment(0x02000000, ewram), MemorySegment(0x03000000, iwram)))
        val decoder = FeDecoder(profile, FeRomCatalog.load(profile, File(checkNotNull(rom)).readBytes()))
        val snapshot = capture()
        val result = decoder.decode(snapshot)
        assertEquals(result.reason, FeScene.PREPARATION, result.scene)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), result.units.filter { it.faction == 0 }.map { it.slot })
        assertEquals(listOf(0x036C, 0x2609, 0, 0, 0), result.units.single { it.slot == 2 }.items.map { it.raw })
        assertNull(result.cursor)
        assertNull(result.mapWidth)
        assertNull(result.selectedUnit)
        assertEquals(100, result.convoy?.items?.size)
        assertTrue(checkNotNull(result.convoy).items.all { it.itemId == 0 })
        fun write(address: Long, value: Long) {
            repeat(4) { ewram[(address - 0x02000000L).toInt() + it] = (value ushr (it * 8)).toByte() }
        }
        write(profile.layout.mapUnitRows, 0)
        write(profile.layout.mapTerrainRows, 0)
        assertEquals(FeScene.PREPARATION, decoder.decode(capture()).scene)
        val context = checkNotNull(PrepTradeCommands.inspect(snapshot))
        write(context.procAddress + 0x38, 0) // Native first selection keeps read-only inventory valid.
        assertEquals(FeScene.PREPARATION, decoder.decode(capture()).scene)
        repeat(8) { write(profile.layout.procRoots + it * 4L, 0) }
        val unavailable = decoder.decode(capture())
        assertEquals(FeScene.UNAVAILABLE, unavailable.scene)
        assertTrue(unavailable.units.isEmpty())
        assertNull(unavailable.convoy)
    }

    @Test fun fourExactCatalogsReadWeaponBaseValuesAndDistinctConvoyLiterals() {
        val inputs = listOf(RomProfiles.fe8j to "EMBERLILY_FE8J_ROM",
            RomProfiles.fe8jVoice to "EMBERLILY_FE8J_VOICE_ROM",
            RomProfiles.fe7j to "EMBERLILY_FE7J_ROM", RomProfiles.fe7jChinese to "EMBERLILY_FE7J_CHINESE_ROM")
        assumeTrue("Set all four registered ROM paths for inventory catalog validation",
            inputs.all { !System.getenv(it.second).isNullOrBlank() })
        for ((profile, env) in inputs) {
            val catalog = FeRomCatalog.load(profile, File(checkNotNull(System.getenv(env))).readBytes())
            val iron = catalog.item(0x2E01, 4)
            assertEquals(4, iron.slot)
            assertEquals(46, iron.uses)
            assertEquals(FeItemDetails(0, 1, 5, 90, 5, 0, 0x11), iron.details)
            val lance = catalog.item(0x2D14, 99)
            assertEquals(99, lance.slot)
            assertEquals(FeItemDetails(1, 1, 7, 80, 8, 0, 0x11), lance.details)
            val literal = if (profile.layout.id == "fe7j") 0x0802EBD0L else 0x08031450L
            assertEquals(profile.layout.convoyAddress, catalog.u32(literal))
            assertEquals(100, profile.layout.convoyCapacity)
            assertNull(catalog.item(0, 0).details)
            assertThrows(IllegalArgumentException::class.java) { catalog.item(1, 100) }
        }
    }
}
