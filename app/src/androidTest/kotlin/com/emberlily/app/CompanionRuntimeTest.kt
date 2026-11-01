package com.emberlily.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.ImportedRom
import com.emberlily.app.storage.SessionStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The original smoke ROM exercises actual capture/restore/branch boundaries.
 * Worker results are held until the test explicitly executes the owner callback,
 * making late publications deterministic without timing-dependent sleep loops.
 * These tests do not claim FE scene-decoder coverage for an unknown ROM.
 */
@RunWith(AndroidJUnit4::class)
class CompanionRuntimeTest {
    private lateinit var directory: File
    private lateinit var imported: ImportedRom
    private lateinit var store: SessionStore
    private lateinit var main: GameSession
    private lateinit var runtime: CompanionRuntime
    private val results = LinkedBlockingQueue<() -> Unit>()
    private var handle = 0L

    @Before
    fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(requireNotNull(context.getExternalFilesDir(null)), "smoke.gba")
        require(fixture.isFile && fixture.length() == 32768L) { "Deploy the original scripts/create_smoke_rom.py fixture" }
        val bytes = fixture.readBytes()
        require(sha256(bytes) == SMOKE_SHA256) { "Companion runtime tests require the original SRAM smoke.gba" }
        directory = File(context.cacheDir, "companion-runtime-${UUID.randomUUID()}").canonicalFile
        check(directory.mkdir())
        imported = ImportedRom(SMOKE_SHA256, "Original smoke fixture", File(directory, "smoke.gba").apply { writeBytes(bytes) })
        store = SessionStore(File(directory, "sessions"))
        main = store.main(SMOKE_SHA256)
        runtime = CompanionRuntime { callback -> results.add(callback) }
        open(main)
        advance(16)
    }

    @After
    fun cleanUp() {
        try { if (::runtime.isInitialized) runtime.close() }
        finally {
            try { closeCore() }
            finally {
                if (::directory.isInitialized) {
                    check(directory.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun unknownRomCaptureAndRepeatedPausedSamplesDoNotAdvanceGame() {
        val before = runtime.capture(handle, 16)
        assertEquals("gba-raw", before.stamp.adapterId)
        assertEquals(SMOKE_SHA256, before.stamp.romSha256)
        assertEquals("main", before.stamp.branchId)
        assertEquals(16L, before.stamp.frame)
        assertTrue(before.u32(0x02000000) > 0)
        assertNull(runtime.state.value.profile)

        runtime.sample(handle, 16)
        awaitResult().invoke()
        assertEquals(1L, runtime.state.value.captured)
        assertNull(runtime.state.value.battle)
        assertTrue(runtime.accepts(before.stamp))

        // Pause is represented by not calling runFrame. Capturing and analysis
        // must still work at the same frame and must never advance the core.
        runtime.sample(handle, 16)
        awaitResult().invoke()
        val after = runtime.capture(handle, 16)
        assertEquals(2L, runtime.state.value.captured)
        assertEquals(before.stamp, after.stamp)
        assertArrayEquals(before.readBytes(0x02000000, 0x40000), after.readBytes(0x02000000, 0x40000))
        assertArrayEquals(before.readBytes(0x03000000, 0x8000), after.readBytes(0x03000000, 0x8000))
    }

    @Test
    fun restoringAtAnEarlierFrameRejectsAlreadyCompletedOldWorkerResult() {
        val saved = runtime.capture(handle, 16)
        assertTrue(NativeCore.saveState(handle, main.checkpointFile.absolutePath))
        advance(8)
        runtime.sample(handle, 24)
        val oldResult = awaitResult()
        val oldStamp = runtime.capture(handle, 24).stamp
        assertTrue(NativeCore.loadState(handle, main.checkpointFile.absolutePath))
        runtime.invalidate("Restored the test checkpoint")
        val restored = runtime.capture(handle, 0)

        assertEquals(saved.stamp.epoch + 1, restored.stamp.epoch)
        assertEquals(saved.stamp.revision, restored.stamp.revision)
        assertFalse(runtime.accepts(oldStamp))
        assertTrue(runtime.accepts(restored.stamp))
        assertArrayEquals(saved.readBytes(0x02000000, 0x40000), restored.readBytes(0x02000000, 0x40000))
        val awaitingFresh = runtime.state.value
        oldResult.invoke()
        assertEquals("Old completion cannot replace the restored context", awaitingFresh, runtime.state.value)

        runtime.sample(handle, 0)
        awaitResult().invoke()
        assertTrue(runtime.state.value.captured > 0)
        assertNotEquals(awaitingFresh.message, runtime.state.value.message)
    }

    @Test
    fun newerCaptureWinsEvenWhenOlderCallbackIsDeliveredLast() {
        runtime.sample(handle, 16)
        val oldResult = awaitResult()
        val previous = runtime.capture(handle, 16).stamp
        advance(1)
        runtime.sample(handle, 17)
        val latest = awaitResult()
        latest.invoke()
        val published = runtime.state.value
        assertEquals(2L, published.captured)
        assertTrue("Commands may still check older frames in the same context", runtime.accepts(previous))
        oldResult.invoke()
        assertEquals("Older analysis cannot overwrite the newer result", published, runtime.state.value)
    }

    @Test
    fun branchAndReopenedSessionRejectPreviousContextWithoutChangingMainFiles() {
        val oldMain = runtime.capture(handle, 16)
        runtime.sample(handle, 16)
        val oldResult = awaitResult()
        assertTrue(NativeCore.saveState(handle, main.stateFile.absolutePath))
        val branch = store.fork(main, "Disposable companion test")
        closeCore()
        val mainFiles = hashes(main.directory)
        open(branch)
        assertTrue(NativeCore.loadState(handle, branch.stateFile.absolutePath))
        val experiment = runtime.capture(handle, 0)
        assertEquals(branch.id, experiment.stamp.branchId)
        assertNotEquals(oldMain.stamp.sessionId, experiment.stamp.sessionId)
        assertFalse(runtime.accepts(oldMain.stamp))
        val awaitingBranch = runtime.state.value
        oldResult.invoke()
        assertEquals(awaitingBranch, runtime.state.value)
        runtime.sample(handle, 0)
        awaitResult().invoke()
        assertEquals(1L, runtime.state.value.captured)
        assertArrayEquals(oldMain.readBytes(0x02000000, 0x40000), experiment.readBytes(0x02000000, 0x40000))
        closeCore()
        assertEquals("Companion sampling must not touch the main branch", mainFiles, hashes(main.directory))

        open(main)
        assertTrue(NativeCore.loadState(handle, main.stateFile.absolutePath))
        val reopened = runtime.capture(handle, 0)
        assertEquals("main", reopened.stamp.branchId)
        assertFalse("Reopening the same branch also creates a fresh session", runtime.accepts(oldMain.stamp))
        assertFalse(runtime.accepts(experiment.stamp))
        assertTrue(runtime.accepts(reopened.stamp))
    }

    @Test
    fun clearAndCloseRejectPendingResultsAndClosedSamplesNeverTouchCore() {
        val old = runtime.capture(handle, 16).stamp
        runtime.sample(handle, 16)
        val oldResult = awaitResult()
        runtime.clear("The core is no longer available")
        val cleared = runtime.state.value
        assertFalse(runtime.accepts(old))
        oldResult.invoke()
        assertEquals(cleared, runtime.state.value)

        runtime.open(imported, main)
        runtime.sample(handle, 16)
        val closingResult = awaitResult()
        runtime.close()
        closeCore()
        val closed = runtime.state.value
        closingResult.invoke()
        assertEquals(closed, runtime.state.value)
        runtime.sample(0, 16)
        assertEquals(closed, runtime.state.value)
        assertThrows(IllegalStateException::class.java) { runtime.capture(0, 16) }
    }

    private fun open(session: GameSession) {
        check(handle == 0L)
        handle = NativeCore.open(imported.file.absolutePath, session.saveFile.absolutePath)
        check(handle != 0L)
        runtime.open(imported, session)
    }

    private fun closeCore() {
        val old = handle
        handle = 0L
        if (old != 0L) NativeCore.close(old)
    }

    private fun advance(frames: Int) {
        val audio = ShortArray(4096)
        repeat(frames) { NativeCore.runFrame(handle, 0); NativeCore.drainAudio(handle, audio) }
    }

    private fun awaitResult(): () -> Unit = checkNotNull(results.poll(5, TimeUnit.SECONDS)) {
        "Companion worker did not return a result within five seconds"
    }

    private fun hashes(directory: File): Map<String, String> = directory.listFiles()!!.filter(File::isFile)
        .associate { it.name to sha256(it.readBytes()) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val SMOKE_SHA256 = "2444a7fd33bfe199a4607f7fc7bde3fe66ea94ebc6373fb45919a7e9e1556ec4"
    }
}
