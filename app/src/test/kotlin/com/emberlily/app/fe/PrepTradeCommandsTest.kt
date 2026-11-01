package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class PrepTradeCommandsTest {
    private val stamp = SnapshotStamp(RomProfiles.FE8J_VOICE_SHA256, "00000000-0000-0000-0000-000000000001", "main", 0, 20, 0, "fe8j-voice-r04")
    private fun context() = PrepTradeContext(stamp, 0x02025378, 0x02025AA4, 2,
        PrepTradeUnit(0x0202BE48, 1, 0x088582F0, 0x0885B6BC, 1, listOf(0x0E17, 0x1903, 0x026C, 0, 0)),
        PrepTradeUnit(0x0202BE90, 2, 0x088582BC, 0x0885B668, 1, listOf(0x036C, 0x2609, 0, 0, 0)),
        0, 255, 255, true)

    @Test fun exchangePreservesFullItemValuesAndPacksBothOwners() {
        val c = context()
        val swap = PrepTradeCommands.plan(c.request(1, 0), c)
        assertEquals(listOf(0x0E17, 0x036C, 0x026C, 0, 0), swap.afterLeft)
        assertEquals(listOf(0x1903, 0x2609, 0, 0, 0), swap.afterRight)
        val move = PrepTradeCommands.plan(c.request(1, 2), c)
        assertEquals(listOf(0x0E17, 0x026C, 0, 0, 0), move.afterLeft)
        assertEquals(listOf(0x036C, 0x2609, 0x1903, 0, 0), move.afterRight)
        assertEquals(1, move.source)
        assertEquals(10, move.target)
    }
    @Test fun emptyLeftSlotSelectsTheActualRightItemFirst() {
        val c = context()
        val plan = PrepTradeCommands.plan(c.request(3, 1), c)
        assertEquals(9, plan.source)
        assertEquals(3, plan.target)
        assertEquals(listOf(0x0E17, 0x1903, 0x026C, 0x2609, 0), plan.afterLeft)
        assertEquals(listOf(0x036C, 0, 0, 0, 0), plan.afterRight)
    }
    @Test fun wrongEmptySlotAndNoOpAreRejected() {
        val c = context()
        assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(c.request(0, 4), c) }
        assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(c.request(3, 2), c) }
        assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(c.request(-1, 0), c) }
    }
    @Test fun everyInventorySlotIsCheckedBeforeAnyInput() {
        val c = context()
        val changed = c.copy(left = c.left.copy(items = listOf(0x0E17, 0x1903, 0x016C, 0, 0)))
        assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(c.request(0, 0), changed) }
    }
    @Test fun restoreReusedParticipantAndNonIdleMenuInvalidateRequest() {
        val c = context()
        for (changed in listOf(c.copy(stamp = stamp.copy(epoch = 1)), c.copy(procAddress = c.procAddress + 0x6C),
            c.copy(right = c.right.copy(characterAddress = c.left.characterAddress)),
            c.copy(selectedSlot = 0), c.copy(helpSlot = 0), c.copy(idle = false))) {
            assertThrows(IllegalArgumentException::class.java) { PrepTradeCommands.plan(c.request(0, 0), changed) }
        }
    }
}
