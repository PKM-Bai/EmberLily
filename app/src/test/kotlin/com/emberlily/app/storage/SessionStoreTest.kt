package com.emberlily.app.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val hash = "ab".repeat(32)
    private fun root() = File(temporary.root, "sessions")

    @Test fun mainIsStableAcrossStoreInstancesAndHashCase() {
        val first = SessionStore(root()).main(hash)
        first.saveFile.writeBytes(byteArrayOf(1, 2, 3))
        val again = SessionStore(root()).main(hash.uppercase())
        assertEquals(first, again)
        assertArrayEquals(byteArrayOf(1, 2, 3), again.saveFile.readBytes())
        assertEquals(listOf(first), SessionStore(root()).list(hash))
    }

    @Test fun experimentsCopyBothFilesAndRemainIsolatedInBothDirections() {
        val store = SessionStore(root())
        val main = store.main(hash)
        main.saveFile.writeBytes(byteArrayOf(1, 2))
        main.stateFile.writeBytes(byteArrayOf(3, 4))
        val experiment = store.fork(main, "实验一")
        assertNotEquals(main.id, experiment.id)
        assertArrayEquals(main.saveFile.readBytes(), experiment.saveFile.readBytes())
        assertArrayEquals(main.stateFile.readBytes(), experiment.stateFile.readBytes())
        experiment.saveFile.writeBytes(byteArrayOf(5))
        main.stateFile.writeBytes(byteArrayOf(6))
        assertArrayEquals(byteArrayOf(1, 2), main.saveFile.readBytes())
        assertArrayEquals(byteArrayOf(3, 4), experiment.stateFile.readBytes())
        assertEquals(listOf(main, experiment), SessionStore(root()).list(hash))
    }

    @Test fun newGameCanForkBeforeItsFirstBatterySave() {
        val store = SessionStore(root())
        val main = store.main(hash)
        main.stateFile.writeBytes(byteArrayOf(1))
        val experiment = store.fork(main, "New game")
        assertFalse(experiment.saveFile.exists())
        assertArrayEquals(byteArrayOf(1), experiment.stateFile.readBytes())
        assertArrayEquals(byteArrayOf(1), experiment.checkpointFile.readBytes())
    }

    @Test fun automaticResumeWritesDoNotReplaceManualCheckpointAcrossReopening() {
        val store = SessionStore(root())
        val main = store.main(hash)
        val checkpoint = byteArrayOf(1, 2, 3)
        main.checkpointFile.writeBytes(checkpoint)
        main.stateFile.writeBytes(byteArrayOf(4, 5, 6))
        val reopened = SessionStore(root()).main(hash)
        assertArrayEquals(checkpoint, reopened.checkpointFile.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), reopened.stateFile.readBytes())
        reopened.stateFile.writeBytes(byteArrayOf(7, 8, 9))
        assertArrayEquals(checkpoint, main.checkpointFile.readBytes())
    }

    @Test fun forkCheckpointUsesCurrentResumeInsteadOfOlderSourceCheckpoint() {
        val store = SessionStore(root())
        val main = store.main(hash)
        val oldCheckpoint = byteArrayOf(1, 2)
        val currentResume = byteArrayOf(3, 4)
        main.checkpointFile.writeBytes(oldCheckpoint)
        main.stateFile.writeBytes(currentResume)
        main.saveFile.writeBytes(byteArrayOf(5, 6))

        val experiment = store.fork(main, "Checkpoint at experiment start")
        assertArrayEquals(currentResume, experiment.stateFile.readBytes())
        assertArrayEquals(currentResume, experiment.checkpointFile.readBytes())
        assertArrayEquals(currentResume, main.stateFile.readBytes())
        assertArrayEquals(oldCheckpoint, main.checkpointFile.readBytes())

        experiment.stateFile.writeBytes(byteArrayOf(7, 8))
        val reopened = SessionStore(root()).list(hash).first { it.id == experiment.id }
        assertArrayEquals(currentResume, reopened.checkpointFile.readBytes())
        assertArrayEquals(oldCheckpoint, main.checkpointFile.readBytes())
    }

    @Test fun failedSecondCopyPublishesNoPartialBranch() {
        val store = SessionStore(root())
        val main = store.main(hash)
        main.saveFile.writeBytes(byteArrayOf(1))
        assertTrue(main.stateFile.mkdir()) // The first copy succeeds, the second must fail.
        try {
            store.fork(main, "Broken")
            throw AssertionError("Expected an IOException")
        } catch (_: IOException) {
            assertEquals(listOf(main), store.list(hash))
            assertEquals(listOf("main"), File(root(), hash).list()!!.toList())
            assertArrayEquals(byteArrayOf(1), main.saveFile.readBytes())
        }
    }

    @Test fun missingResumeDoesNotCreateVisibleOrPendingBranch() {
        val store = SessionStore(root())
        val main = store.main(hash)
        try {
            store.fork(main, "Missing")
            throw AssertionError("Expected an IOException")
        } catch (_: IOException) {
            assertEquals(listOf(main), store.list(hash))
            assertEquals(listOf("main"), File(root(), hash).list()!!.toList())
        }
    }

    @Test fun rejectsHashTraversalAndForgedSourceDirectory() {
        val store = SessionStore(root())
        for (invalid in listOf("../outside", "a".repeat(63), "g".repeat(64), "/absolute", "a".repeat(64) + "/../x")) {
            expectInvalid { store.main(invalid) }
            expectInvalid { store.list(invalid) }
        }
        val main = store.main(hash)
        val elsewhere = temporary.newFolder("elsewhere")
        expectInvalid { store.fork(main.copy(directory = elsewhere), "Forged") }
        expectInvalid { store.fork(main.copy(id = "../main"), "Forged") }
        assertEquals(listOf(main), store.list(hash))
    }

    @Test fun unknownGamesAndPendingDirectoriesAreNotListedAsSessions() {
        val store = SessionStore(root())
        assertTrue(store.list(hash).isEmpty())
        val main = store.main(hash)
        File(main.directory.parentFile, ".pending-interrupted").mkdir()
        assertEquals(listOf(main), store.list(hash))
        assertTrue(store.list("cd".repeat(32)).isEmpty())
    }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Invalid paths are rejected before they can create or copy files.
        }
    }
}
