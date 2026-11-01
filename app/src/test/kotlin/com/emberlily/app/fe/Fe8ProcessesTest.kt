package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class Fe8ProcessesTest {
    private val stamp = SnapshotStamp(RomProfiles.FE8J_SHA256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 12, 0, "fe8j-r03")
    private val bytes = ByteArray(0x40000)
    private val first = Fe8JLayout.PROC_POOL
    private val second = first + Fe8JLayout.PROC_SIZE
    private val third = second + Fe8JLayout.PROC_SIZE
    private fun write(address: Long, value: Long) { repeat(4) { bytes[(address - 0x02000000L).toInt() + it] = (value ushr (it * 8)).toByte() } }
    private fun read() = Fe8Processes.read(MemorySnapshot(stamp, listOf(MemorySegment(0x02000000, bytes)))) {
        it in 0x08000000L..0x08FFFFF8L && it % 4L == 0L
    }

    @Test fun unrootedPoolRecordsCannotInventAnActiveBattle() {
        write(first, Fe8JLayout.MAP_TASK_SCRIPT)
        assertTrue(read().isEmpty())
    }

    @Test fun traversalUsesRootsThenChildrenAndPreviousSiblings() {
        write(Fe8JLayout.PROC_ROOTS, first)
        write(first, 0x08000100)
        write(first + 0x18, second)
        write(second, Fe8JLayout.MAP_TASK_SCRIPT)
        write(second + 0x20, third)
        write(third, 0x08000200)
        write(third + 0x1C, second) // Next points back toward the latest sibling; it is not the traversal direction.
        assertEquals(listOf(first, second, third), read().map { it.address })
        assertEquals(Fe8JLayout.MAP_TASK_SCRIPT, read()[1].script)
    }

    @Test fun cyclicDuplicateAndUnalignedPointersAreRejected() {
        write(Fe8JLayout.PROC_ROOTS, first)
        write(first, Fe8JLayout.MAP_TASK_SCRIPT)
        write(first + 0x18, first)
        assertThrows(IllegalArgumentException::class.java) { read() }
        write(first + 0x18, 0)
        write(Fe8JLayout.PROC_ROOTS + 4, first)
        assertThrows(IllegalArgumentException::class.java) { read() }
        write(Fe8JLayout.PROC_ROOTS + 4, 0)
        write(first + 0x18, second + 1)
        assertThrows(IllegalArgumentException::class.java) { read() }
    }

    @Test fun activeScriptsMustBeWithinTheVerifiedRom() {
        write(Fe8JLayout.PROC_ROOTS, first)
        write(first, 0x02001000)
        assertThrows(IllegalArgumentException::class.java) { read() }
    }
}
