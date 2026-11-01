package com.emberlily.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.emulation.FrameBuffer
import com.emberlily.app.fe.*
import com.emberlily.app.graphics.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native rendering verification, using an isolated core and local state copy. */
@RunWith(AndroidJUnit4::class)
class LocalWideMapTest {
    @Test fun reconstructsRealMapAndPreservesNativeFrameWithoutWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("wideTest") == "true")
        val stateName = args.getString("wideState") ?: error("wideState must name a local large-map state")
        require(Regex("[a-zA-Z0-9_-]+\\.state").matches(stateName))
        val viewportWidth = (args.getString("wideWidth") ?: "1920").toInt()
        val viewportHeight = (args.getString("wideHeight") ?: "1080").toInt()
        require(viewportWidth in 240..4096 && viewportHeight in 160..4096)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceRom = File(context.getExternalFilesDir(null), "targets/r04.gba")
        val sourceState = File(context.filesDir, "local-probes/$stateName")
        val originalRomHash = hash(sourceRom.readBytes())
        require(originalRomHash == RomProfiles.FE8J_VOICE_SHA256)
        val stateHash = hash(sourceState.readBytes())
        val directory = File(context.cacheDir, "wide-map-${UUID.randomUUID()}").apply { check(mkdir()) }
        val rom = sourceRom.copyTo(File(directory, "input.gba"))
        val state = sourceState.copyTo(File(directory, "input.state"))
        var handle = 0L
        try {
            handle = NativeCore.open(rom.absolutePath, File(directory, "battery.sav").absolutePath)
            assertTrue(NativeCore.loadState(handle, state.absolutePath))
            val renderer = Fe8WideRenderer()
            val session = UUID.randomUUID().toString()
            val pixels = IntArray(240 * 160)
            var accepted = 0
            var last: WideFrame? = null
            val failures = linkedMapOf<String, Int>()
            val elapsed = ArrayList<Double>()
            val pipeline = ArrayList<Double>()
            val frameBuffer = FrameBuffer()
            repeat(180) { index ->
                NativeCore.runFrame(handle, 0)
                val pipelineStart = System.nanoTime()
                assertTrue(NativeCore.copyFrame(handle, pixels))
                val before = NativeCore.readMemory(handle, 0x02000000, 0x40000)
                val stamp = SnapshotStamp(originalRomHash, session, "main", 0, index.toLong(), 0, "fe8j-voice-r04")
                val snapshot = MemorySnapshot(stamp, listOf(MemorySegment(0x02000000, before),
                    MemorySegment(0x03000000, NativeCore.readMemory(handle, 0x03000000, 0x8000))))
                val video = GbaVideoSnapshot(NativeCore.readMemory(handle, 0x06000000, 0x18000),
                    NativeCore.readMemory(handle, 0x05000000, 0x400), NativeCore.readMemory(handle, 0x07000000, 0x400),
                    NativeCore.readVideoRegisters(handle))
                val nativeHash = hashInts(pixels)
                val start = System.nanoTime()
                val wide = renderer.render(snapshot, video, pixels, viewportWidth, viewportHeight)
                elapsed += (System.nanoTime() - start) / 1e6
                if (wide == null) frameBuffer.publish(pixels) else frameBuffer.publish(wide.pixels, wide.width, wide.height)
                pipeline += (System.nanoTime() - pipelineStart) / 1e6
                assertEquals(nativeHash, hashInts(pixels))
                assertArrayEquals(before, NativeCore.readMemory(handle, 0x02000000, 0x40000))
                if (wide != null) {
                    accepted++
                    assertTrue(wide.width > 240 || wide.height > 160)
                    for (y in 0 until 160) assertArrayEquals(pixels.copyOfRange(y * 240, (y + 1) * 240),
                        wide.pixels.copyOfRange((wide.originalY + y) * wide.width + wide.originalX,
                            (wide.originalY + y) * wide.width + wide.originalX + 240))
                    last = wide
                } else renderer.lastFailure?.let { failures[it] = (failures[it] ?: 0) + 1 }
            }
            assertTrue("Expansion must remain stable on a verified idle map; accepted=$accepted/180; $failures", accepted >= 170)
            val output = checkNotNull(last)
            val evidence = File(context.filesDir, "local-probes/wide-${stateName.removeSuffix(".state")}.png")
            val bitmap = Bitmap.createBitmap(output.pixels, output.width, output.height, Bitmap.Config.ARGB_8888)
            evidence.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val sorted = elapsed.drop(30).sorted()
            val pipelineSorted = pipeline.drop(30).sorted()
            println("Wide map ${output.width}x${output.height}; native@${output.originalX},${output.originalY}; accepted=$accepted/180; render p95=${sorted[(sorted.size * .95).toInt()]} ms; capture/render/publish p95=${pipelineSorted[(pipelineSorted.size * .95).toInt()]} ms; extra sprites=${output.drawnSprites}")
            assertEquals(originalRomHash, hash(sourceRom.readBytes()))
            assertEquals(stateHash, hash(sourceState.readBytes()))
        } finally { if (handle != 0L) NativeCore.close(handle); directory.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hashInts(pixels: IntArray) = pixels.fold(1) { value, pixel -> value * 31 + pixel }
}
