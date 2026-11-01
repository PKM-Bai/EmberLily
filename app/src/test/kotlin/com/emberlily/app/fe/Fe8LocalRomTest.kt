package com.emberlily.app.fe

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional local integration: the repository stores only synthetic RAM, never game bytes. */
class Fe8LocalRomTest {
    @Test fun fingerprintedCatalogAndDecoderAcceptValidSyntheticStateAndRejectBrokenState() {
        val path = System.getenv("EMBERLILY_FE8J_ROM")
        assumeTrue("Set EMBERLILY_FE8J_ROM to the registered R03 ROM for this local integration", !path.isNullOrBlank())
        val profile = RomProfiles.fe8j
        val catalog = Fe8RomCatalog.load(profile, File(checkNotNull(path)).readBytes())
        assertEquals("エイリーク", catalog.recordName(Fe8JLayout.CHARACTER_TABLE).text)
        assertEquals("てつの剣", catalog.item(0x2E01, 0).name.text)
        assertEquals(46, catalog.item(0x2E01, 0).maxUses)

        val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 12, 0, profile.id)
        val ewram = ByteArray(0x40000)
        val iwram = ByteArray(0x8000)
        fun write(address: Long, value: Long, count: Int = 4) {
            val buffer = if (address >= 0x03000000) iwram else ewram
            val base = if (address >= 0x03000000) 0x03000000L else 0x02000000L
            repeat(count) { buffer[(address - base).toInt() + it] = (value ushr (8 * it)).toByte() }
        }
        fun capture(stampOverride: SnapshotStamp = stamp) = MemorySnapshot(stampOverride, listOf(
            MemorySegment(0x02000000, ewram), MemorySegment(0x03000000, iwram),
        ))
        write(Fe8JLayout.PROC_ROOTS, Fe8JLayout.PROC_POOL)
        write(Fe8JLayout.PROC_POOL, Fe8JLayout.MAP_TASK_SCRIPT)
        write(Fe8JLayout.MAP_SIZE, 15, 2)
        write(Fe8JLayout.MAP_SIZE + 2, 10, 2)
        write(Fe8JLayout.MAP_UNIT_ROWS, 0x02010000)
        write(Fe8JLayout.MAP_TERRAIN_ROWS, 0x02011000)
        repeat(10) { row ->
            write(0x02010000 + row * 4L, 0x02010100 + row * 15L)
            write(0x02011000 + row * 4L, 0x02011100 + row * 15L)
        }
        write(Fe8JLayout.PLAY_STATE + 0x0E, 1, 1)
        write(Fe8JLayout.PLAY_STATE + 0x10, 3, 2)
        write(Fe8JLayout.BM_STATE + 0x14, 4, 2)
        write(Fe8JLayout.BM_STATE + 0x16, 6, 2)
        val unit = Fe8JLayout.PLAYER_UNITS
        write(unit, Fe8JLayout.CHARACTER_TABLE)
        write(unit + 4, Fe8JLayout.CLASS_TABLE + Fe8JLayout.CLASS_SIZE)
        write(unit + 8, 1, 1)
        write(unit + 0x0B, 1, 1)
        write(unit + 0x10, 4, 1)
        write(unit + 0x11, 6, 1)
        write(unit + 0x12, 16, 1)
        write(unit + 0x13, 13, 1)
        write(unit + 0x1E, 0x2E01, 2)
        write(Fe8JLayout.ACTIVE_UNIT, unit)
        val decoder = Fe8Decoder(profile, catalog)
        val decoded = decoder.decode(capture())
        assertEquals(FeScene.MAP, decoded.scene)
        assertEquals(1, decoded.chapter)
        assertEquals(3, decoded.turn)
        assertEquals(MapPosition(4, 6), decoded.cursor)
        assertEquals("エイリーク", decoded.focusedUnit?.name?.text)
        assertNull(decoded.selectedUnit) // An active-unit pointer alone does not establish a current selection.
        assertEquals(13, decoded.focusedUnit?.hp)
        assertEquals(1, decoded.units.single().items.first().itemId)
        val roundTrip = SnapshotCodec.decode(SnapshotCodec.encode(capture()), profile.identity.sha256)
        assertEquals(decoded, decoder.decode(roundTrip))
        assertEquals(0L, decoder.decodeRaw(capture()).units.single().identity.generation)

        write(unit + 0x0B, 2, 1)
        assertEquals(FeScene.INVALID, decoder.decode(capture()).scene)
        write(unit + 0x0B, 1, 1)
        assertNotEquals(decoded.units.single().identity, decoder.decode(capture()).units.single().identity)
        assertEquals(FeScene.INVALID, decoder.decode(capture(stamp.copy(romSha256 = RomProfiles.FE8J_VOICE_SHA256))).scene)
        assertEquals(FeScene.INVALID, decoder.decode(capture(stamp.copy(adapterVersion = 2))).scene)
        write(Fe8JLayout.MAP_UNIT_ROWS, 0)
        assertEquals(FeScene.INVALID, decoder.decode(capture()).scene)
        write(Fe8JLayout.PROC_ROOTS, 0)
        assertEquals(FeScene.UNAVAILABLE, decoder.decode(capture()).scene)
    }
}
