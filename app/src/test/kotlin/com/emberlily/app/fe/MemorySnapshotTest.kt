package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class MemorySnapshotTest {
    private val stamp = SnapshotStamp("ab".repeat(32), "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 12, 0)

    @Test fun captureAndReturnedBytesCannotBeMutatedByCaller() {
        val supplied = byteArrayOf(1, 2, 3, 4)
        val segment = MemorySegment(0x02000000, supplied)
        val list = mutableListOf(segment)
        val snapshot = MemorySnapshot(stamp, list)
        supplied[0] = 55
        list.clear()
        segment.copyBytes()[0] = 66
        snapshot.readBytes(0x02000000, 4)[0] = 77
        assertEquals(1, snapshot.u8(0x02000000))
        assertEquals(0x04030201L, snapshot.u32(0x02000000))
    }

    @Test fun scalarReadsAreLittleEndianAndDoNotCrossMissingMemory() {
        val snapshot = MemorySnapshot(stamp, listOf(MemorySegment(0x03000000, byteArrayOf(-1, -128, 3, -1))))
        assertEquals(255, snapshot.u8(0x03000000))
        assertEquals(-1, snapshot.s8(0x03000000))
        assertEquals(0x80FF, snapshot.u16(0x03000000))
        assertEquals(0x80FF.toShort().toInt(), snapshot.s16(0x03000000))
        assertEquals(0xFF0380FFL, snapshot.u32(0x03000000))
        assertTrue(snapshot.contains(0x03000000, 4))
        assertFalse(snapshot.contains(0x03000001, 4))
        assertFalse(snapshot.contains(Long.MAX_VALUE, 8))
        assertThrows(IllegalArgumentException::class.java) { snapshot.u32(0x03000001) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.readBytes(0x03000000, -1) }
    }

    @Test fun overlappingDuplicateMirroredAndDeviceRangesAreRejected() {
        fun segment(address: Long, count: Int = 4) = MemorySegment(address, ByteArray(count))
        assertThrows(IllegalArgumentException::class.java) { MemorySnapshot(stamp, listOf(segment(0x02000000), segment(0x02000002))) }
        assertThrows(IllegalArgumentException::class.java) { MemorySnapshot(stamp, listOf(segment(0x02000000), segment(0x02000000))) }
        for (address in listOf(0x02040000L, 0x03008000L, 0x04000000L, 0x08000000L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { segment(address) }
        }
        assertThrows(IllegalArgumentException::class.java) { segment(0x0203FFFF, 2) }
        assertThrows(IllegalArgumentException::class.java) { MemorySnapshot(stamp, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { MemorySnapshot(stamp, (0..8).map { segment(0x02000000L + it * 4) }) }
    }

    @Test fun staleDecodeAndCommandContextsHaveDifferentFrameRequirements() {
        val gate = SnapshotGate()
        assertFalse(gate.acceptContext(stamp))
        gate.reset(stamp)
        assertTrue(gate.advance(stamp.copy(frame = 13)))
        assertFalse(gate.acceptExact(stamp))
        assertTrue(gate.acceptContext(stamp))
        assertFalse(gate.acceptContext(stamp.copy(frame = 14)))
        assertFalse(gate.advance(stamp))
        for (changed in listOf(
            stamp.copy(romSha256 = "cd".repeat(32)), stamp.copy(sessionId = "11111111-1111-1111-1111-111111111111"),
            stamp.copy(branchId = "11111111-1111-1111-1111-111111111111"), stamp.copy(epoch = 2),
            stamp.copy(revision = 1), stamp.copy(adapterVersion = 2), stamp.copy(adapterId = "another-adapter"),
        )) {
            assertFalse(gate.acceptContext(changed))
            assertFalse(gate.advance(changed))
        }
        gate.reset(stamp.copy(epoch = 2, frame = 0))
        assertFalse(gate.acceptContext(stamp))
        gate.clear()
        assertFalse(gate.acceptContext(stamp.copy(epoch = 2, frame = 0)))
    }

    @Test fun identitiesAndCountersAreValidatedBeforeExport() {
        assertThrows(IllegalArgumentException::class.java) { stamp.copy(epoch = -1) }
        assertThrows(IllegalArgumentException::class.java) { stamp.copy(romSha256 = "BE8J") }
        assertThrows(IllegalArgumentException::class.java) { stamp.copy(sessionId = "not-a-session") }
        assertThrows(IllegalArgumentException::class.java) { stamp.copy(branchId = "../main") }
        assertThrows(IllegalArgumentException::class.java) { stamp.copy(adapterId = "../../profile") }
    }
}
