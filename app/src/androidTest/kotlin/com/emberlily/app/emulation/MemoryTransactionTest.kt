package com.emberlily.app.emulation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.SessionStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the original smoke.gba fixture and disposable sessions, never user progress. */
@RunWith(AndroidJUnit4::class)
class MemoryTransactionTest {
    private lateinit var owner: ExecutorService
    private lateinit var directory: File
    private lateinit var rom: File
    private val handles = linkedSetOf<Long>()

    @Before
    fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supplied = File(requireNotNull(context.getExternalFilesDir(null)), "smoke.gba")
        require(supplied.isFile) { "Generate scripts/create_smoke_rom.py and deploy smoke.gba before this test" }
        val bytes = supplied.readBytes()
        // Bind the test to the SRAM-writing original program, not any commercial input.
        require(sha(bytes) == "2444a7fd33bfe199a4607f7fc7bde3fe66ea94ebc6373fb45919a7e9e1556ec4") {
            "Memory transaction tests require the original SRAM smoke.gba fixture"
        }
        directory = File(context.cacheDir, "memory-transaction-${UUID.randomUUID()}").canonicalFile
        check(directory.mkdir())
        rom = File(directory, "smoke.gba").apply { writeBytes(bytes) }
        owner = Executors.newSingleThreadExecutor { work -> Thread(work, "memory-transaction-owner") }
    }

    @After
    fun cleanUp() {
        try {
            if (::owner.isInitialized) onCore {
                var first: Throwable? = null
                for (handle in handles.toList()) {
                    try { close(handle) } catch (failure: Throwable) { if (first == null) first = failure }
                }
                first?.let { throw it }
            }
        } finally {
            if (::owner.isInitialized) owner.shutdownNow()
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test
    fun commitsBothRamRegionsAndGuardsWithoutAdvancingOrReapplying() = onCore {
        val handle = open(main())
        advance(handle, 16)
        val original = readRam(handle)
        val firstAddress = 0x0203FFFDL // Last three canonical EWRAM bytes.
        val secondAddress = 0x03007FFEL // Last two canonical IWRAM bytes.
        val first = NativeCore.readMemory(handle, firstAddress, 3)
        val second = NativeCore.readMemory(handle, secondAddress, 2)
        val guard = NativeCore.readMemory(handle, EWRAM, 8)
        val addresses = longArrayOf(firstAddress, secondAddress, EWRAM)
        val expected = arrayOf(first, second, guard)
        val replacement = arrayOf(changed(first), changed(second), guard.copyOf())
        val expectedInputs = expected.map { it.copyOf() }

        assertTrue(NativeCore.compareAndWriteMemory(handle, addresses, expected, replacement))
        val changedRam = original.first.copyOf().also { replacement[0].copyInto(it, EWRAM_SIZE - 3) }
        val changedIram = original.second.copyOf().also { replacement[1].copyInto(it, IWRAM_SIZE - 2) }
        assertRamEquals(changedRam to changedIram, readRam(handle))
        assertArrayEquals("The diagnostic counter must not advance", guard, NativeCore.readMemory(handle, EWRAM, 8))
        expectedInputs.forEachIndexed { index, bytes ->
            assertArrayEquals("JNI must not modify caller-owned expected arrays", bytes, expected[index])
        }
        assertFalse("Submitting the same expected bytes twice must not commit twice",
            NativeCore.compareAndWriteMemory(handle, addresses, expected, replacement))
        assertRamEquals(changedRam to changedIram, readRam(handle))
        assertTrue("Equal replacement values can guard a stable state",
            NativeCore.compareAndWriteMemory(handle, longArrayOf(EWRAM), arrayOf(guard), arrayOf(guard.copyOf())))
        assertRamEquals(changedRam to changedIram, readRam(handle))

        close(handle)
        expectIllegalState {
            NativeCore.compareAndWriteMemory(handle, longArrayOf(EWRAM), arrayOf(guard), arrayOf(guard))
        }
    }

    @Test
    fun permitsAdjacentRangesAtBothTransactionLimits() = onCore {
        val handle = open(main())
        advance(handle, 16)
        val original = readRam(handle)
        val before = NativeCore.readMemory(handle, SAFE_EWRAM, 16 * 1024)
        val addresses = LongArray(64) { SAFE_EWRAM + it * 256L }
        val expected = Array(64) { before.copyOfRange(it * 256, (it + 1) * 256) }
        val replacement = expected.map(::changed).toTypedArray()
        assertTrue(NativeCore.compareAndWriteMemory(handle, addresses, expected, replacement))
        val expectedRam = original.first.copyOf().also { changed(before).copyInto(it, (SAFE_EWRAM - EWRAM).toInt()) }
        assertRamEquals(expectedRam to original.second, readRam(handle))
    }

    @Test
    fun mismatchInLaterWriteOrGuardCannotPartiallyCommit() = onCore {
        val handle = open(main())
        advance(handle, 16)
        val original = readRam(handle)
        val first = NativeCore.readMemory(handle, SAFE_EWRAM, 4)
        val second = NativeCore.readMemory(handle, SAFE_IWRAM, 4)
        assertFalse(NativeCore.compareAndWriteMemory(handle, longArrayOf(SAFE_EWRAM, SAFE_IWRAM),
            arrayOf(first, changed(second)), arrayOf(changed(first), second)))
        assertRamEquals(original, readRam(handle))

        val staleGuard = changed(NativeCore.readMemory(handle, EWRAM, 4))
        assertFalse(NativeCore.compareAndWriteMemory(handle, longArrayOf(SAFE_EWRAM, EWRAM),
            arrayOf(first, staleGuard), arrayOf(changed(first), staleGuard)))
        assertRamEquals(original, readRam(handle))
    }

    @Test
    fun rejectsInvalidRegionsLimitsAndShapesBeforeAnyWrite() = onCore {
        val handle = open(main())
        advance(handle, 16)
        val original = readRam(handle)
        val one = byteArrayOf(0)
        val two = byteArrayOf(0, 0)
        val first = NativeCore.readMemory(handle, SAFE_EWRAM, 2)
        fun reject(addresses: LongArray, expected: Array<ByteArray>, replacement: Array<ByteArray>) {
            expectIllegalArgument { NativeCore.compareAndWriteMemory(handle, addresses, expected, replacement) }
            assertRamEquals(original, readRam(handle))
        }
        for (address in listOf(-1L, Long.MAX_VALUE, 0x1_02000000L, 0L, 0x04000000L,
            0x05000000L, 0x06000000L, 0x07000000L, 0x08000000L, 0x0E000000L,
            0x02040000L, 0x03008000L)) {
            // A valid first write ensures that a late invalid address cannot cause a partial commit.
            reject(longArrayOf(SAFE_EWRAM, address), arrayOf(first, one), arrayOf(changed(first), one))
        }
        reject(longArrayOf(0x0203FFFF), arrayOf(two), arrayOf(two))
        reject(longArrayOf(0x03007FFF), arrayOf(two), arrayOf(two))
        reject(longArrayOf(SAFE_EWRAM), arrayOf(byteArrayOf()), arrayOf(byteArrayOf()))
        reject(longArrayOf(SAFE_EWRAM), arrayOf(one), arrayOf(two))
        reject(longArrayOf(SAFE_EWRAM, SAFE_IWRAM), arrayOf(one), arrayOf(one))
        reject(longArrayOf(SAFE_EWRAM), arrayOf(one), emptyArray())
        reject(longArrayOf(), emptyArray(), emptyArray())
        reject(longArrayOf(SAFE_EWRAM, SAFE_EWRAM), arrayOf(first, first), arrayOf(changed(first), first))
        reject(longArrayOf(SAFE_EWRAM, SAFE_EWRAM + 1), arrayOf(first, first), arrayOf(changed(first), first))
        reject(LongArray(65) { SAFE_EWRAM + it }, Array(65) { one }, Array(65) { one })
        val oversized = ByteArray(16 * 1024 + 1)
        reject(longArrayOf(SAFE_EWRAM), arrayOf(oversized), arrayOf(oversized))
        // Each individual range fits; their sum still exceeds the transaction limit.
        val limit = ByteArray(16 * 1024)
        reject(longArrayOf(SAFE_EWRAM, SAFE_IWRAM), arrayOf(limit, one), arrayOf(limit, one))
        @Suppress("UNCHECKED_CAST")
        val nullElement = arrayOfNulls<ByteArray>(1) as Array<ByteArray>
        reject(longArrayOf(SAFE_EWRAM), nullElement, arrayOf(one))
    }

    @Test
    fun editedExperimentCanSaveReopenAndRestoreWithoutChangingMain() = onCore {
        val store = SessionStore(File(directory, "sessions"))
        val main = store.main(sha(rom.readBytes()))
        val mainHandle = open(main)
        advance(mainHandle, 16)
        assertTrue(NativeCore.saveState(mainHandle, main.stateFile.absolutePath))
        val baseline = NativeCore.readMemory(mainHandle, SAFE_EWRAM, 6)
        close(mainHandle)
        val originalFiles = fileHashes(main.directory)
        val originalRom = sha(rom.readBytes())
        val experiment = store.fork(main, "Memory transaction experiment")
        var handle = open(experiment)
        assertTrue(NativeCore.loadState(handle, experiment.stateFile.absolutePath))
        val edited = changed(baseline)
        assertTrue(NativeCore.compareAndWriteMemory(handle, longArrayOf(SAFE_EWRAM), arrayOf(baseline), arrayOf(edited)))
        advance(handle, 16)
        assertArrayEquals(edited, NativeCore.readMemory(handle, SAFE_EWRAM, 6))
        assertTrue(NativeCore.saveState(handle, experiment.stateFile.absolutePath))
        close(handle)
        assertEquals(originalFiles, fileHashes(main.directory))

        handle = open(experiment)
        assertTrue(NativeCore.loadState(handle, experiment.stateFile.absolutePath))
        assertArrayEquals("Edited RAM survives branch save and reopen", edited, NativeCore.readMemory(handle, SAFE_EWRAM, 6))
        assertTrue(NativeCore.loadState(handle, experiment.checkpointFile.absolutePath))
        assertArrayEquals("The preserved experiment origin restores the original value", baseline,
            NativeCore.readMemory(handle, SAFE_EWRAM, 6))
        close(handle)
        assertEquals("Experiment writes, saves and recovery never alter main", originalFiles, fileHashes(main.directory))
        assertEquals(originalRom, sha(rom.readBytes()))
    }

    private fun main() = SessionStore(File(directory, "sessions")).main(sha(rom.readBytes()))
    private fun open(session: GameSession): Long = NativeCore.open(rom.absolutePath, session.saveFile.absolutePath).also {
        assertTrue("Original diagnostic must open", it != 0L)
        handles += it
    }
    private fun close(handle: Long) { try { NativeCore.close(handle) } finally { handles -= handle } }
    private fun advance(handle: Long, count: Int) {
        val audio = ShortArray(4096)
        repeat(count) { NativeCore.runFrame(handle, 0); NativeCore.drainAudio(handle, audio) }
    }
    private fun readRam(handle: Long) = NativeCore.readMemory(handle, EWRAM, EWRAM_SIZE) to
        NativeCore.readMemory(handle, IWRAM, IWRAM_SIZE)
    private fun assertRamEquals(expected: Pair<ByteArray, ByteArray>, actual: Pair<ByteArray, ByteArray>) {
        assertArrayEquals("All EWRAM bytes must match", expected.first, actual.first)
        assertArrayEquals("All IWRAM bytes must match", expected.second, actual.second)
    }
    private fun changed(bytes: ByteArray) = bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fileHashes(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to sha(it.readBytes()) }
    private fun expectIllegalArgument(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Malformed RAM transaction must throw IllegalArgumentException")
    }
    private fun expectIllegalState(action: () -> Unit) {
        try { action() } catch (_: IllegalStateException) { return }
        throw AssertionError("Closed core must reject RAM transaction")
    }
    private fun <T> onCore(action: () -> T): T = try {
        owner.submit(Callable { action() }).get(30, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) { throw failure.cause ?: failure }

    private companion object {
        const val EWRAM = 0x02000000L
        const val IWRAM = 0x03000000L
        const val EWRAM_SIZE = 256 * 1024
        const val IWRAM_SIZE = 32 * 1024
        const val SAFE_EWRAM = 0x02020000L
        const val SAFE_IWRAM = 0x03001000L
    }
}
