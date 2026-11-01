package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class PrepTradeGuardTest {
    private val stamp = SnapshotStamp(RomProfiles.FE8J_VOICE_SHA256, "00000000-0000-0000-0000-000000000001", "main", 0, 0, 0, "fe8j-voice-r04")
    private val parent = Fe8JLayout.PROC_POOL
    private val trade = parent + Fe8JLayout.PROC_SIZE
    private val left = Fe8JLayout.PLAYER_UNITS
    private val right = left + Fe8JLayout.UNIT_SIZE
    private fun snapshot(change: (ByteArray) -> Unit = {}): MemorySnapshot {
        val ram = ByteArray(0x40000)
        fun put(address: Long, value: Long, size: Int = 4) {
            repeat(size) { ram[(address - 0x02000000).toInt() + it] = (value shr (it * 8)).toByte() }
        }
        put(Fe8JLayout.PROC_ROOTS, parent)
        put(parent, PrepTradeCommands.PARENT_SCRIPT)
        put(parent + 0x18, trade)
        put(parent + 0x28, 1, 1)
        put(trade, PrepTradeCommands.SCRIPT)
        put(trade + 4, PrepTradeCommands.IDLE_CURSOR)
        put(trade + 0x0C, PrepTradeCommands.IDLE_CALLBACK)
        put(trade + 0x14, parent)
        put(trade + 0x2C, left)
        put(trade + 0x30, right)
        put(trade + 0x38, 255)
        put(trade + 0x3C, 255)
        for ((index, address) in listOf(left, right).withIndex()) {
            put(address, Fe8JLayout.CHARACTER_TABLE + index * Fe8JLayout.CHARACTER_SIZE)
            put(address + 4, Fe8JLayout.CLASS_TABLE)
            put(address + 0x0B, (index + 1).toLong(), 1)
            put(address + 0x1E, (0x1903 + index).toLong(), 2)
        }
        change(ram)
        return MemorySnapshot(stamp, listOf(MemorySegment(0x02000000, ram)))
    }
    private fun ByteArray.setByte(address: Long, value: Int) { this[(address - 0x02000000).toInt()] = value.toByte() }

    @Test fun onlyLiveOwnershipEstablishesThePreparationScene() {
        assertTrue(checkNotNull(PrepTradeCommands.inspect(snapshot())).ready)
        assertNull(PrepTradeCommands.inspect(snapshot { ram -> repeat(4) { ram.setByte(Fe8JLayout.PROC_ROOTS + it, 0) } }))
        assertNull(PrepTradeCommands.inspect(snapshot().let { MemorySnapshot(it.stamp.copy(romSha256 = RomProfiles.FE7J_CHINESE_SHA256),
            it.ranges.map { range -> MemorySegment(range.address, it.readBytes(range.address, range.length)) }) }))
    }
    @Test fun linkArenaSelectionHelpFadeAndLocksRemainReadOnly() {
        val changes: List<(ByteArray) -> Unit> = listOf(
            { it.setByte(Fe8JLayout.BM_STATE + 4, 0x40) },
            { it.setByte(trade + 0x38, 0) },
            { it.setByte(trade + 0x3C, 0) },
            { it.setByte(trade + 4, 0x70) },
            { it.setByte(trade + 0x28, 1) },
            { it.setByte(parent + 0x28, 0) },
        )
        for (change in changes) {
            val view = checkNotNull(PrepTradeCommands.inspect(snapshot(change)))
            assertFalse(view.ready)
            assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(view.request(0, 0), view) }
        }
    }
}
