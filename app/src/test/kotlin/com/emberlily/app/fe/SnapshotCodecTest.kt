package com.emberlily.app.fe

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SnapshotCodecTest {
    private val stamp = SnapshotStamp("ab".repeat(32), "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 12, 3)
    private fun capture(stamp: SnapshotStamp = this.stamp) = MemorySnapshot(stamp, listOf(
        MemorySegment(0x02000000, byteArrayOf(1, 2, 3, 4)), MemorySegment(0x03000000, byteArrayOf(5, 6, 7, 8)),
    ))

    @Test fun roundTripRetainsEveryIdentityCounterAndByte() {
        val original = capture()
        val encoded = SnapshotCodec.encode(original)
        val decoded = SnapshotCodec.decode(ByteArrayInputStream(encoded), stamp.romSha256)
        encoded.fill(0)
        assertEquals(stamp, decoded.stamp)
        assertEquals(original.ranges, decoded.ranges)
        assertArrayEquals(original.readBytes(0x02000000, 4), decoded.readBytes(0x02000000, 4))
        assertArrayEquals(original.readBytes(0x03000000, 4), decoded.readBytes(0x03000000, 4))
    }

    @Test fun damagedTruncatedAppendedAndWrongRomCapturesAreRejected() {
        val valid = SnapshotCodec.encode(capture())
        val damaged = valid.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        for (bad in listOf(damaged, valid.copyOf(valid.size - 1), valid + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(valid, "cd".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(ByteArrayInputStream(ByteArray(SnapshotCodec.MAX_ENCODED_BYTES + 1))) }
    }

    @Test fun validChecksumDoesNotBypassVersionLengthAndMemoryValidation() {
        val valid = SnapshotCodec.encode(capture())
        fun changed(offset: Int, value: Int): ByteArray = valid.copyOf().also {
            ByteBuffer.wrap(it).putInt(offset, value)
            resign(it)
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(changed(8, 2)) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(changed(12, Int.MAX_VALUE)) }

        // The two data blocks finish the body. Re-signing malformed data tests
        // structural validation independently of the integrity checksum.
        val firstBlock = valid.size - 32 - 2 * (8 + 4 + 4)
        val duplicate = valid.copyOf().also {
            ByteBuffer.wrap(it).putLong(firstBlock + 16, 0x02000000)
            resign(it)
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(changed(firstBlock + 8, Int.MAX_VALUE)) }
        val deviceMemory = valid.copyOf().also {
            ByteBuffer.wrap(it).putLong(firstBlock, 0x04000000)
            resign(it)
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.decode(deviceMemory) }
    }

    @Test fun replayCanSeekEarlierFramesButCannotMixIndependentSessions() {
        val replay = SnapshotReplay(listOf(capture(), capture(stamp.copy(frame = 20)), capture(stamp.copy(epoch = 2, frame = 1))))
        assertEquals(20L, replay.seek(1).stamp.frame)
        assertEquals(12L, replay.seek(0).stamp.frame)
        assertEquals(2L, replay.seek(2).stamp.epoch)
        assertThrows(IllegalArgumentException::class.java) { replay.seek(3) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotReplay(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotReplay(listOf(capture(), capture(stamp.copy(romSha256 = "cd".repeat(32))))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotReplay(listOf(capture(), capture(stamp.copy(branchId = "11111111-1111-1111-1111-111111111111")))) }
    }

    private fun resign(encoded: ByteArray) {
        val payload = encoded.copyOf(encoded.size - 32)
        MessageDigest.getInstance("SHA-256").digest(payload).copyInto(encoded, payload.size)
    }
}
