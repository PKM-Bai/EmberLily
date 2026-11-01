package com.emberlily.app.fe

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exact local ROMs plus authored RAM. This verifies adapter structure, not real gameplay coverage. */
class Fe7LocalRomTest {
    @Test fun fe7TablesAndSyntheticMapUseTheirOwnProfileAndRejectFe8Context() {
        val jp = System.getenv("EMBERLILY_FE7J_ROM")
        val cn = System.getenv("EMBERLILY_FE7J_CHINESE_ROM")
        assumeTrue("Set both registered FE7J ROM paths for the local adapter integration", !jp.isNullOrBlank() && !cn.isNullOrBlank())
        for ((profile, path) in listOf(RomProfiles.fe7j to jp!!, RomProfiles.fe7jChinese to cn!!)) {
            val layout = profile.layout
            val catalog = FeRomCatalog.load(profile, File(path).readBytes())
            assertEquals(1, catalog.characterId(layout.characterTable))
            assertEquals(1, catalog.classId(layout.classTable + layout.classSize))
            assertEquals(46, catalog.item(0x2E01, 0).maxUses)
            if (profile.nameEncoding == FeNameEncoding.SHIFT_JIS) {
                assertEquals("エリウッド", catalog.recordName(layout.characterTable).text)
                assertEquals("てつの剣", catalog.item(0x2E01, 0).name.text)
            } else {
                assertNull(catalog.recordName(layout.characterTable).text)
                assertNotNull(catalog.recordName(layout.characterTable).note)
                assertNull(catalog.item(0x2E01, 0).name.text)
            }
            val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 50, 0, profile.id)
            val ewram = ByteArray(0x40000)
            val iwram = ByteArray(0x8000)
            fun write(address: Long, value: Long, count: Int = 4) {
                val buffer = if (address >= 0x03000000) iwram else ewram
                val base = if (address >= 0x03000000) 0x03000000L else 0x02000000L
                repeat(count) { buffer[(address - base).toInt() + it] = (value ushr (8 * it)).toByte() }
            }
            fun capture(context: SnapshotStamp = stamp) = MemorySnapshot(context, listOf(
                MemorySegment(0x02000000, ewram), MemorySegment(0x03000000, iwram)))
            val decoder = FeDecoder(profile, catalog)
            assertEquals(FeScene.UNAVAILABLE, decoder.decode(capture()).scene)
            write(layout.procRoots, layout.procPool)
            write(layout.procPool, layout.mapTaskScript)
            write(layout.mapSize, 8, 2); write(layout.mapSize + 2, 6, 2)
            write(layout.mapUnitRows, 0x02010000); write(layout.mapTerrainRows, 0x02011000)
            repeat(6) { row ->
                write(0x02010000 + row * 4L, 0x02010100 + row * 10L)
                write(0x02011000 + row * 4L, 0x02011100 + row * 10L)
            }
            write(layout.playState + layout.chapterOffset, 3, 1)
            write(layout.playState + layout.turnOffset, 4, 2)
            write(layout.bmState + layout.cursorOffset, 2, 2)
            write(layout.bmState + layout.cursorOffset + 2, 3, 2)
            val unit = checkNotNull(layout.unitAddress(1))
            write(unit, layout.characterTable); write(unit + 4, layout.classTable + layout.classSize)
            write(unit + 8, 3, 1); write(unit + 0x0B, 1, 1)
            write(unit + 0x10, 2, 1); write(unit + 0x11, 3, 1)
            write(unit + 0x12, 20, 1); write(unit + 0x13, 17, 1); write(unit + 0x1E, 0x2E01, 2)
            val result = decoder.decode(capture())
            assertEquals(FeScene.MAP, result.scene)
            assertEquals(3, result.chapter); assertEquals(4, result.turn)
            assertEquals(MapPosition(2, 3), result.cursor)
            assertEquals(unit, result.focusedUnit?.address)
            assertEquals(17, result.focusedUnit?.hp)
            assertEquals(46, result.focusedUnit?.items?.first()?.uses)
            val replay = SnapshotCodec.decode(SnapshotCodec.encode(capture()), profile.identity.sha256)
            assertEquals(result, decoder.decode(replay))
            assertEquals(FeScene.INVALID, decoder.decode(capture(stamp.copy(romSha256 = RomProfiles.FE8J_VOICE_SHA256))).scene)
            write(unit + 0x0B, 2, 1)
            assertEquals(FeScene.INVALID, decoder.decode(capture()).scene)
            write(unit + 0x0B, 1, 1)
            write(layout.mapTerrainRows, 0)
            assertEquals(FeScene.INVALID, decoder.decode(capture()).scene)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeRomCatalog.load(RomProfiles.fe7j, File(cn).readBytes())
        }
    }
}
