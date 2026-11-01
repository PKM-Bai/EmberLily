package com.emberlily.app.fe

import org.junit.Assert.*
import org.junit.Test

class UnitLifetimeTrackerTest {
    private val stamp = SnapshotStamp(RomProfiles.FE8J_SHA256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 12, 0, "fe8j-r03")
    private val first = UnitLifetimeTracker.Observation(1, Fe8JLayout.CHARACTER_TABLE)

    @Test fun stableFramesAndEditsKeepAnObservedInstanceButReuseDoesNot() {
        val tracker = UnitLifetimeTracker()
        val original = tracker.identify(stamp, 1, listOf(first)).getValue(1)
        assertEquals(original, tracker.identify(stamp.copy(frame = 20, revision = 1), 1, listOf(first)).getValue(1))
        tracker.identify(stamp.copy(frame = 24), 1, emptyList())
        val reused = tracker.identify(stamp.copy(frame = 28), 1, listOf(first)).getValue(1)
        assertNotEquals(original, reused)
        assertTrue(reused.generation > original.generation)
        val changed = tracker.identify(stamp.copy(frame = 32), 1, listOf(first.copy(characterAddress = first.characterAddress + 0x34))).getValue(1)
        assertNotEquals(reused, changed)
    }

    @Test fun restoredContextChapterAndInvalidScenesCannotResurrectOldReferences() {
        val tracker = UnitLifetimeTracker()
        var previous = tracker.identify(stamp, 1, listOf(first)).getValue(1)
        val contexts = listOf(
            stamp.copy(epoch = 2), stamp.copy(romSha256 = RomProfiles.FE8J_VOICE_SHA256),
            stamp.copy(branchId = "11111111-1111-1111-1111-111111111111"),
            stamp.copy(sessionId = "11111111-1111-1111-1111-111111111111"),
        )
        for (context in contexts) {
            val next = tracker.identify(context, 1, listOf(first)).getValue(1)
            assertNotEquals(previous, next)
            previous = next
        }
        val nextChapter = tracker.identify(contexts.last(), 2, listOf(first)).getValue(1)
        assertNotEquals(previous, nextChapter)
        tracker.invalidate()
        assertNotEquals(nextChapter, tracker.identify(contexts.last(), 2, listOf(first)).getValue(1))
    }

    @Test fun duplicateSlotObservationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { UnitLifetimeTracker().identify(stamp, 1, listOf(first, first)) }
    }
}
