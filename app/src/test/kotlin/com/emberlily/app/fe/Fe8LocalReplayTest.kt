package com.emberlily.app.fe

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hashes and expectations are distributable metadata; actual ROM and capture bytes stay local. */
class Fe8LocalReplayTest {
    @Test fun registeredR04ScenesDecodeAndOnlyPlayerIdlePlansAnHpEdit() {
        val romPath = System.getenv("EMBERLILY_FE8J_VOICE_ROM")
        val capturePath = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Set both local R04 ROM and replay directory to exercise recorded game scenes", !romPath.isNullOrBlank() && !capturePath.isNullOrBlank())
        val profile = RomProfiles.fe8jVoice
        val catalog = Fe8RomCatalog.load(profile, File(checkNotNull(romPath)).readBytes())
        val directory = File(checkNotNull(capturePath))
        val hashes = mapOf(
            "r04-02" to listOf("2f0d2e0785b3ff28bb58575b1e3f694e746206e2e33951d43f35c042959aad8f", "760625484029719a8d9bc79c1b8d99845d2d5efe60f84945190dc4dc80944a36"),
            "r04-07" to listOf("dca4d2fe99680bacad55f132768873860206a6224836f0d41071dc80b5bb75a3", "d385cb76c6d1857e4bb33042fcffa8df02666e90fa08a1de2db5391c94cb11b5"),
            "r04-08" to listOf("466f6d0e8477d8427e175fece2405423a92a4ff2ea927e5f43728b42045444a5", "ac0b9d12051a0602aebee0400daffa9ec1e6207bee01c807653ecabb118376e2"),
            "r04-09" to listOf("5d03a21e54fdddef35f14185a28959f4f86c9affbe5d40f0239d441a2d5d6799", "e15c60c521260a5a5dd5e683b562b5b04607b0fbaca8319e9e90ac9dac75bd3a"),
        )
        for ((label, expectedHashes) in hashes) {
            val segments = listOf("ewram", "iwram").mapIndexed { index, region ->
                val bytes = File(directory, "$label.$region.bin").readBytes()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("Capture identity changed: $label.$region", expectedHashes[index], sha)
                MemorySegment(if (index == 0) 0x02000000 else 0x03000000, bytes)
            }
            // Synthetic replay session metadata does not grant permission to a live emulator or branch.
            val stamp = SnapshotStamp(profile.identity.sha256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 0, 0, profile.id)
            val capture = SnapshotCodec.decode(SnapshotCodec.encode(MemorySnapshot(stamp, segments)), profile.identity.sha256)
            val decoded = Fe8Decoder(profile, catalog).decode(capture)
            if (label == "r04-02") {
                assertEquals(FeScene.UNAVAILABLE, decoded.scene)
                assertTrue(decoded.units.isEmpty())
                continue
            }
            assertEquals(label, FeScene.MAP, decoded.scene)
            assertEquals(0, decoded.chapter)
            assertEquals(15, decoded.mapWidth)
            assertEquals(10, decoded.mapHeight)
            assertEquals(listOf(1, 2, 0x81, 0x82, 0x83), decoded.units.map { it.slot })
            assertEquals(listOf(13, 16, 23, 22, 20), decoded.units.map { it.hp })
            assertTrue(decoded.units.all { it.name.text == null })
            val eirika = decoded.units.single { it.characterId == 1 }
            assertEquals(2, eirika.slot)
            assertEquals(2, eirika.classId)
            assertEquals(4, eirika.x)
            assertEquals(5, eirika.y)
            assertEquals(listOf(0x036C, 0x2809, 0, 0, 0), eirika.items.map { it.raw })
            if (label == "r04-07") {
                assertEquals(0, decoded.turn)
                assertEquals(0x40, decoded.phase)
                assertNull(decoded.selectedSlot)
            } else {
                assertEquals(1, decoded.turn)
                assertEquals(0, decoded.phase)
                assertEquals(MapPosition(4, 5), decoded.cursor)
                assertEquals(eirika, decoded.focusedUnit) // Selection/cancel must not change cursor-follow details.
                if (label == "r04-08") {
                    assertEquals(2, decoded.selectedSlot)
                    assertEquals(eirika, decoded.selectedUnit)
                } else {
                    assertEquals(eirika.address, capture.u32(Fe8JLayout.ACTIVE_UNIT)) // Retained pointer after cancel.
                    assertNull(decoded.selectedSlot)
                    assertNull(decoded.selectedUnit)
                }
            }
            val request = HpEditRequest(stamp, eirika.identity, eirika.classId, 16, 15)
            if (label == "r04-09") {
                val plan = Fe8ResearchCommands.planHp(request, capture, decoded)
                assertEquals(16, plan.beforeHp)
                assertEquals(15, plan.afterHp)
                assertEquals(6, plan.patches.size)
                assertEquals(16, capture.u8(eirika.address + 0x13)) // Planning never mutates captured or live state.
            } else {
                assertThrows(IllegalArgumentException::class.java) { Fe8ResearchCommands.planHp(request, capture, decoded) }
            }
        }
    }
}
