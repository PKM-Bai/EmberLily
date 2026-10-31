package com.emberlily.app.emulation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional local inputs, supplied with instrumentation argument:
 * -e targets fe8-jp.gba,fe8-cn.gba,fe7-jp.gba,fe7-cn.gba,heaven.gba
 *
 * Files belong under targetContext.getExternalFilesDir(null)/targets. Without
 * the argument this test is explicitly skipped. Each source is copied to its
 * own cache session; no user save or source ROM is opened for writing.
 *
 * Passing establishes only core rendering and state round-trip behavior for
 * the logged SHA-256. It does not verify Fire Emblem semantics, in-game menu
 * saves, adapters, chapter progress, translations or complete compatibility.
 */
@RunWith(AndroidJUnit4::class)
class LocalTargetSmokeTest {
    @Test
    fun suppliedLocalTargetsRenderAndRestore() {
        val argument = InstrumentationRegistry.getArguments().getString("targets")
        if (argument.isNullOrBlank()) Log.i(TAG, "SKIP no targets argument; local ROM compatibility is untested")
        assumeTrue("Optional local ROM smoke requires an explicit targets argument", !argument.isNullOrBlank())
        val names = requireNotNull(argument).split(',').map(String::trim)
        require(names.isNotEmpty() && names.size <= 16 && names.all { NAME.matches(it) }) {
            "targets must contain 1-16 comma-separated .gba basenames, without directory components"
        }
        require(names.distinct().size == names.size) { "targets contains duplicate basenames" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null)) { "External app files are unavailable" }
        val inputs = File(external, "targets").canonicalFile
        val owner = Executors.newSingleThreadExecutor { task -> Thread(task, "local-target-smoke-owner") }
        try {
            owner.submit(Callable {
                Log.i(TAG, "BEGIN core=${NativeCore.coreVersion()} targets=${names.joinToString(",")} scope=core-smoke-only")
                val failures = mutableListOf<Throwable>()
                for (name in names) {
                    try {
                        smokeTarget(File(inputs, name), inputs, context.cacheDir)
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
                Log.i(TAG, "SUMMARY passed=${names.size - failures.size} failed=${failures.size} semanticCompatibility=UNTESTED")
                if (failures.isNotEmpty()) {
                    val summary = AssertionError("${failures.size}/${names.size} local target core smoke checks failed; see $TAG")
                    failures.forEach(summary::addSuppressed)
                    throw summary
                }
            }).get(180, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            owner.shutdownNow()
        }
    }

    /** All JNI operations, including final cleanup, run on the single owner thread. */
    private fun smokeTarget(supplied: File, inputs: File, cache: File) {
        val name = supplied.name
        val directory = File(cache, "local-target-smoke-${UUID.randomUUID()}")
        val rom = File(directory, "input.gba")
        val battery = File(directory, "battery.sav")
        val state = File(directory, "resume.state")
        var handle = 0L
        var originalSha: String? = null
        var ramSha: String? = null
        var frames = 0
        var failure: Throwable? = null

        fun recordFailure(error: Throwable) {
            val first = failure
            if (first == null) failure = error else first.addSuppressed(error)
        }

        fun closeCurrent() {
            val current = handle
            handle = 0L
            if (current != 0L) NativeCore.close(current)
        }

        try {
            require(supplied.canonicalFile.parentFile == inputs && supplied.isFile) {
                "Explicitly requested target is missing or outside targets: $name"
            }
            originalSha = sha256(supplied)
            Log.i(TAG, "INPUT target=$name bytes=${supplied.length()} sha256=$originalSha")
            check(directory.mkdir()) { "Cannot create isolated cache session for $name" }
            supplied.copyTo(rom)
            assertEquals("Copied ROM identity: $name", originalSha, sha256(rom))
            check(rom.setReadOnly()) { "Cannot make the copied input read-only: $name" }
            handle = NativeCore.open(rom.absolutePath, battery.absolutePath)
            assertTrue("Core must open $name", handle != 0L)
            val audio = ShortArray(4096)
            val pixels = IntArray(240 * 160)

            // Press/release Start twice, without assuming a game-specific menu.
            repeat(360) { frame ->
                advance(handle, audio, if (frame in 120..122 || frame in 240..242) START else 0)
                frames++
            }
            var nonUniform = hasNonUniformFrame(handle, pixels)
            // A single exact frame may fall on a fade. Allow a bounded transition.
            var extra = 0
            while (!nonUniform && extra < 180) {
                advance(handle, audio, 0)
                frames++
                extra++
                nonUniform = hasNonUniformFrame(handle, pixels)
            }
            assertTrue("Expected a nonuniform rendered frame after $frames frames: $name", nonUniform)
            Log.i(TAG, "FRAME target=$name frames=$frames distinctColors=${pixels.toSet().size}")

            val snapshot = NativeCore.readMemory(handle, EWRAM, EWRAM_BYTES)
            assertEquals(EWRAM_BYTES, snapshot.size)
            ramSha = sha256(snapshot)
            assertTrue("State save must succeed: $name", NativeCore.saveState(handle, state.absolutePath))
            assertArrayEquals("Saving must not advance EWRAM: $name", snapshot, NativeCore.readMemory(handle, EWRAM, EWRAM_BYTES))
            repeat(120) {
                advance(handle, audio, 0)
                frames++
            }
            val changed = !snapshot.contentEquals(NativeCore.readMemory(handle, EWRAM, EWRAM_BYTES))
            assertTrue("State restore must succeed: $name", NativeCore.loadState(handle, state.absolutePath))
            assertArrayEquals("Restore must recover all 256 KiB of EWRAM: $name", snapshot, NativeCore.readMemory(handle, EWRAM, EWRAM_BYTES))
            closeCurrent()

            handle = NativeCore.open(rom.absolutePath, battery.absolutePath)
            assertTrue("Core must reopen $name", handle != 0L)
            assertTrue("Reopened core must load the saved state: $name", NativeCore.loadState(handle, state.absolutePath))
            assertArrayEquals("Reopening must recover all 256 KiB of EWRAM: $name", snapshot, NativeCore.readMemory(handle, EWRAM, EWRAM_BYTES))
            closeCurrent()
            Log.i(TAG, "RESTORE target=$name live=true reopened=true ramChangedDuring120=$changed ramSha256=$ramSha")
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            try {
                closeCurrent()
            } catch (error: Throwable) {
                recordFailure(error)
            }
            try {
                if (originalSha != null) {
                    assertEquals("Original ROM must remain unchanged: $name", originalSha, sha256(supplied))
                    if (rom.isFile) assertEquals("Emulated ROM must remain unchanged: $name", originalSha, sha256(rom))
                }
                for (artifact in listOf(battery, state)) {
                    if (artifact.isFile) Log.i(TAG, "OUTPUT target=$name file=${artifact.name} bytes=${artifact.length()} sha256=${sha256(artifact)}")
                }
            } catch (error: Throwable) {
                recordFailure(error)
            }
            // Only this test's newly created cache directory is removed.
            if (directory.exists() && !directory.deleteRecursively()) {
                recordFailure(AssertionError("Cannot remove owned test cache directory for $name"))
            }
        }
        val error = failure
        val status = if (error == null) "PASS" else "FAIL"
        Log.i(TAG, "RESULT target=$name status=$status romSha256=$originalSha frames=$frames ramSha256=$ramSha scope=core-smoke-only")
        if (error != null) {
            Log.i(TAG, "FAILURE target=$name", error)
            throw AssertionError("Local target core smoke failed: $name", error)
        }
    }

    private fun advance(handle: Long, audio: ShortArray, keys: Int) {
        NativeCore.runFrame(handle, keys)
        val count = NativeCore.drainAudio(handle, audio)
        assertTrue("Audio result must fit its stereo buffer", count in 0..audio.size && count % 2 == 0)
    }

    private fun hasNonUniformFrame(handle: Long, pixels: IntArray): Boolean {
        assertTrue("Completed frame must be available", NativeCore.copyFrame(handle, pixels))
        return pixels.any { it != pixels[0] }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val TAG = "LocalTargetSmoke"
        const val START = 1 shl 3
        const val EWRAM = 0x02000000L
        const val EWRAM_BYTES = 256 * 1024
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.[gG][bB][aA]")
    }
}
