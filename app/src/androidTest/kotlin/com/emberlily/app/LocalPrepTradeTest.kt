package com.emberlily.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Opt-in, disposable core/session only. Never opens the UI session directory. */
@RunWith(AndroidJUnit4::class)
class LocalPrepTradeTest {
    private lateinit var directory: File
    private lateinit var inputRom: File
    private lateinit var inputState: File
    private lateinit var sourceRomHash: String
    private lateinit var sourceStateHash: String
    private var handle = 0L
    private var frame = 0L
    private val stamp = SnapshotStamp(RomProfiles.FE8J_VOICE_SHA256, UUID.randomUUID().toString(), "main", 0, 0, 0, "fe8j-voice-r04")

    @Before fun openPrivateFixture() {
        assumeTrue("Set prepTradeTest=true to run the local R04 native menu fixture",
            InstrumentationRegistry.getArguments().getString("prepTradeTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        inputRom = File(context.getExternalFilesDir(null), "targets/r04.gba")
        inputState = File(context.filesDir, "local-probes/r04-wide-agent-worldmap-prep-trade.state")
        sourceRomHash = hash(inputRom)
        sourceStateHash = hash(inputState)
        assertEquals(RomProfiles.FE8J_VOICE_SHA256, sourceRomHash)
        assertEquals("97f697fd9b5f3423742d565449ad9a5de94be536dbd57e2c809bb73815b34efb", sourceStateHash)
        directory = File(context.cacheDir, "prep-trade-${UUID.randomUUID()}").apply { check(mkdir()) }
        val rom = File(directory, "input.gba").also { inputRom.copyTo(it) }
        val state = File(directory, "input.state").also { inputState.copyTo(it) }
        handle = NativeCore.open(rom.absolutePath, File(directory, "battery.sav").absolutePath)
        check(handle != 0L && NativeCore.loadState(handle, state.absolutePath))
        assertTrue(view().ready)
        assertEquals(listOf(0x0E17, 0x1903, 0x026C, 0, 0), view().left.items)
        assertEquals(listOf(0x036C, 0x2609, 0, 0, 0), view().right.items)
    }
    @After fun closePrivateFixture() {
        if (handle != 0L) NativeCore.close(handle)
        handle = 0
        if (::inputRom.isInitialized && ::sourceRomHash.isInitialized) assertEquals(sourceRomHash, hash(inputRom))
        if (::inputState.isInitialized && ::sourceStateHash.isInitialized) assertEquals(sourceStateHash, hash(inputState))
        if (::directory.isInitialized) directory.deleteRecursively()
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun capture() = MemorySnapshot(stamp.copy(frame = frame), GbaRamCapture.ranges.map {
        MemorySegment(it.address, NativeCore.readMemory(handle, it.address, it.length))
    })
    private fun view() = checkNotNull(PrepTradeCommands.inspect(capture()))
    private fun advance(keys: Int) { NativeCore.runFrame(handle, keys); frame++ }
    private fun runner(cancel: () -> Boolean = { false }) = PrepTradeRunner(::capture, ::advance, cancel)

    @Test fun originalMenuExchangesCompleteItemsAndRejectsReplayedRequest() {
        val before = view()
        val request = before.request(1, 0)
        val started = System.nanoTime()
        val after = runner().execute(request)
        println("R04 native prep trade: $frame frames, ${(System.nanoTime() - started) / 1e6} ms")
        assertEquals(listOf(0x0E17, 0x036C, 0x026C, 0, 0), after.left.items)
        assertEquals(listOf(0x1903, 0x2609, 0, 0, 0), after.right.items)
        assertTrue(after.ready)
        assertEquals(before.left.state, after.left.state)
        assertEquals(before.right.state, after.right.state)
        val stoppedAt = frame
        assertThrows(IllegalArgumentException::class.java) { runner().execute(request) }
        assertEquals(stoppedAt, frame)
    }
    @Test fun transferCompactsTheSourceAndSurvivesStateReload() {
        val after = runner().execute(view().request(1, 2))
        assertEquals(listOf(0x0E17, 0x026C, 0, 0, 0), after.left.items)
        assertEquals(listOf(0x036C, 0x2609, 0x1903, 0, 0), after.right.items)
        val saved = File(directory, "after.state")
        assertTrue(NativeCore.saveState(handle, saved.absolutePath))
        repeat(12) { advance(0) }
        assertTrue(NativeCore.loadState(handle, saved.absolutePath))
        assertEquals(after.left.items, view().left.items)
        assertEquals(after.right.items, view().right.items)
    }
    @Test fun nativeHelpAndSelectionRejectNewRequestsWithoutAdvancing() {
        val request = view().request(0, 0)
        repeat(3) { advance(1) }; repeat(3) { advance(0) }
        assertEquals(0, view().selectedSlot)
        val stoppedAt = frame
        assertThrows(IllegalArgumentException::class.java) { runner().execute(request) }
        assertEquals(stoppedAt, frame)
        repeat(3) { advance(2) }; repeat(3) { advance(0) }
        assertTrue(view().ready)
        repeat(3) { advance(256) }; repeat(3) { advance(0) }
        assertFalse(view().ready)
        val helpAt = frame
        assertThrows(IllegalArgumentException::class.java) { runner().execute(request) }
        assertEquals(helpAt, frame)
    }
    @Test fun interruptionAfterSelectionDoesNotExchangeOrPretendRollback() {
        val before = view()
        assertThrows(IllegalStateException::class.java) { runner { frame >= 4 }.execute(before.request(0, 0)) }
        assertEquals(before.left.items, view().left.items)
        assertEquals(before.right.items, view().right.items)
        assertEquals(0, view().selectedSlot)
    }
    @Test fun interruptedHeldKeyCanBeReleasedWithoutAdvancingThePausedGame() {
        val before = view()
        assertThrows(IllegalStateException::class.java) { runner { frame >= 4 }.execute(before.request(0, 0)) }
        // The fixture takes two native frames to observe the A edge, and cancellation
        // can stop before its neutral release frames. Clear the core mask explicitly.
        val ram = NativeCore.readMemory(handle, 0x02000000, 0x40000)
        val at = frame
        val held = NativeCore.releaseKeys(handle)
        assertTrue(held == 0 || held == 1)
        assertEquals(0, NativeCore.releaseKeys(handle))
        assertEquals(at, frame)
        assertArrayEquals(ram, NativeCore.readMemory(handle, 0x02000000, 0x40000))
        assertEquals(before.left.items, view().left.items)
        // Explicitly test a definitely held mask too, without relying on callback timing.
        advance(2)
        assertEquals(2, NativeCore.releaseKeys(handle))
        assertEquals(0, NativeCore.releaseKeys(handle))
    }
}
