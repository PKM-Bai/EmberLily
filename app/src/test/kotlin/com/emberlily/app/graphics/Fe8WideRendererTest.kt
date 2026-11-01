package com.emberlily.app.graphics

import com.emberlily.app.fe.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class Fe8WideRendererTest {
    private class Fixture(val width: Int = 22, val height: Int = 12, cameraX: Int = 0, cameraY: Int = 0) {
        val ew = ByteArray(0x40000)
        val iw = ByteArray(0x8000)
        val vram = ByteArray(0x18000)
        val palette = ByteArray(0x400)
        val oam = ByteArray(0x400)
        val regs = ByteArray(0x60)
        val original = IntArray(240 * 160) { 0xFF000000.toInt() or it }
        val stamp = SnapshotStamp(RomProfiles.FE8J_VOICE_SHA256, "01234567-89ab-cdef-0123-456789abcdef", "main", 1, 100, 0, RomProfiles.fe8jVoice.id)
        init {
            val parent = Fe8JLayout.PROC_POOL
            val player = parent + Fe8JLayout.PROC_SIZE
            val map = player + Fe8JLayout.PROC_SIZE
            write(Fe8JLayout.PROC_ROOTS + 8, parent)
            write(Fe8JLayout.PROC_ROOTS + 16, map)
            write(parent, Fe8JLayout.BMAP_MAIN_SCRIPT)
            write(parent + 0x18, player)
            write(parent + 0x28, 1, 1)
            write(player, Fe8JLayout.PLAYER_PHASE_SCRIPT)
            write(player + 4, Fe8JLayout.PLAYER_IDLE_CURSOR)
            write(player + 0x0C, Fe8JLayout.PLAYER_IDLE_CALLBACK)
            write(player + 0x14, parent)
            write(map, Fe8JLayout.MAP_TASK_SCRIPT)
            write(Fe8JLayout.MAP_SIZE, width.toLong(), 2)
            write(Fe8JLayout.MAP_SIZE + 2, height.toLong(), 2)
            write(Fe8JLayout.BM_STATE + 0x0C, cameraX.toLong(), 2)
            write(Fe8JLayout.BM_STATE + 0x0E, cameraY.toLong(), 2)
            write(Fe8JLayout.BM_STATE + 0x24, (cameraX / 16).toLong(), 2)
            write(Fe8JLayout.BM_STATE + 0x26, (cameraY / 16).toLong(), 2)
            write(0x0202E4E4, 0x0202E500)
            write(0x0202E4D4, 0x0202E600)
            repeat(height) { y ->
                write(0x02032F88 + y * 4L, 0x02032F88 + (height + 1) * 4L + y * width * 2L)
                write(0x0202E500 + y * 4L, 0x0202EA00L + y * width)
                write(0x0202E600 + y * 4L, 0x0202EE00L + y * width)
                repeat(width) { x -> write(0x0202EA00 + y * width + x.toLong(), 1, 1) }
            }
            put(regs, 0, 0x1F00)
            put(regs, 0x0C, 0x0E02)
            put(regs, 0x0E, 0x0F0B)
            put(regs, 0x1C, cameraX % 16)
            put(regs, 0x1E, cameraY % 16)
            repeat(1024) { put(vram, 0x7800 + it * 2, 0x6000) }
            vram.fill(0x11, 0x8000, 0x8020)
            put(palette, (6 * 16 + 1) * 2, 0x03E0) // Solid green terrain.
            repeat(128) { put(oam, it * 8, 0x0200) }
        }
        fun write(at: Long, value: Long, count: Int = 4) {
            val buffer = if (at >= 0x03000000) iw else ew
            val base = if (at >= 0x03000000) 0x03000000 else 0x02000000
            repeat(count) { buffer[(at - base).toInt() + it] = (value ushr (8 * it)).toByte() }
        }
        fun put(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
        }
        fun sprite(x: Int, y: Int, onscreen: Boolean) {
            val handle = 0x0203A020L
            write(0x0203A014, handle)
            write(handle + 4, x.toLong(), 2); write(handle + 6, y.toLong(), 2)
            write(handle + 8, 0xC080, 2)
            write(Fe8JLayout.PLAYER_UNITS, Fe8JLayout.CHARACTER_TABLE)
            write(Fe8JLayout.PLAYER_UNITS + 0x10, (x / 16).toLong(), 1)
            write(Fe8JLayout.PLAYER_UNITS + 0x11, (y / 16).toLong(), 1)
            write(Fe8JLayout.PLAYER_UNITS + 0x3C, handle)
            write(0x0202EE00 + (y / 16) * width + (x / 16).toLong(), 1, 1)
            vram.fill(0x22, 0x11000, 0x11800)
            put(palette, (256 + 12 * 16 + 2) * 2, 0x7C00)
            if (onscreen) {
                put(oam, 0, y); put(oam, 2, 0x4000 + x); put(oam, 4, 0xC880)
            }
        }
        fun capture(stamp: SnapshotStamp = this.stamp) = MemorySnapshot(stamp, listOf(MemorySegment(0x02000000, ew), MemorySegment(0x03000000, iw)))
        fun video() = GbaVideoSnapshot(vram, palette, oam, regs)
        fun render(renderer: Fe8WideRenderer = Fe8WideRenderer(), stamp: SnapshotStamp = this.stamp, viewportWidth: Int = 1920, viewportHeight: Int = 1080) =
            renderer.render(capture(stamp), video(), original, viewportWidth, viewportHeight)
    }

    @Test fun expandsRealMapCoordinatesAndPreservesEveryOriginalPixelAndInput() {
        val f = Fixture()
        f.sprite(256, 64, false)
        val inputs = listOf(f.ew, f.iw, f.vram, f.palette, f.oam, f.regs)
        val copies = inputs.map { it.copyOf() }
        val raw = f.original.copyOf()
        val frame = checkNotNull(f.render())
        assertEquals(285, frame.width); assertEquals(160, frame.height)
        assertEquals(0, frame.originalX); assertEquals(0, frame.originalY)
        assertEquals(651, frame.matchedTiles); assertEquals(1, frame.drawnSprites)
        assertEquals(0xFF00FF00.toInt(), frame.pixels[20 * frame.width + 284])
        assertEquals(0xFF0000FF.toInt(), frame.pixels[64 * frame.width + 256])
        repeat(160) { y -> assertArrayEquals(raw.copyOfRange(y * 240, y * 240 + 240), frame.pixels.copyOfRange(y * frame.width, y * frame.width + 240)) }
        inputs.zip(copies).forEach { (actual, expected) -> assertArrayEquals(expected, actual) }
        assertArrayEquals(raw, f.original)
    }

    @Test fun clampsViewportAtBothMapEdgesAndKeepsSquarePixels() {
        val right = checkNotNull(Fixture(cameraX = 112).render())
        assertEquals(45, right.originalX)
        assertEquals(0, right.originalY)
        val middle = checkNotNull(Fixture(cameraX = 48).render())
        assertEquals(22, middle.originalX)
        val portrait = checkNotNull(Fixture(height = 20, cameraY = 160).render(viewportWidth = 3, viewportHeight = 4))
        assertEquals(240, portrait.width); assertEquals(320, portrait.height); assertEquals(160, portrait.originalY)
        assertNull(Fixture(15, 10).render())
    }

    @Test fun requiresMatchingTilemapOamAndOffscreenUnitHandles() {
        val tile = Fixture(); tile.put(tile.vram, 0x7800, 0x6001); assertNull(tile.render())
        val sprite = Fixture(); sprite.sprite(64, 64, true); assertNotNull(sprite.render())
        sprite.put(sprite.oam, 4, 0xC881); assertNull(sprite.render())
        val missing = Fixture(); missing.sprite(256, 64, false)
        missing.write(Fe8JLayout.PLAYER_UNITS + 0x3C, 0); assertNull(missing.render())
        val badPointer = Fixture(); badPointer.write(0x02032F88, 0x08000000); assertNull(badPointer.render())
    }

    @Test fun unfamiliarGamesMenusWindowsAndColourEffectsUseOriginalFrame() {
        val f = Fixture()
        assertNull(f.render(stamp = f.stamp.copy(romSha256 = RomProfiles.FE8J_SHA256)))
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.write(Fe8JLayout.BM_STATE + 4, 1, 1) },
            { it.write(Fe8JLayout.PLAY_STATE + 0x0F, 0x80, 1) },
            { it.write(Fe8JLayout.PROC_POOL + Fe8JLayout.PROC_SIZE + 0x0C, 0x0801C985) },
            { it.put(it.regs, 0, 0x3F00) },
            { it.put(it.regs, 0x50, 0x48) },
            { it.put(it.regs, 0x0E, 0x0F8B) },
        )
        changes.forEach { change -> val sample = Fixture(); change(sample); assertNull(sample.render()) }
    }

    @Test fun unadaptedWeatherAndNonemptyMapEffectsFallBackAndDiscardPriorCandidate() {
        for (weather in listOf(1, 2, 3, 4, 5, 6, 7, 255)) {
            val f = Fixture(); val renderer = Fe8WideRenderer()
            assertNotNull(f.render(renderer))
            f.write(Fe8JLayout.PLAY_STATE + 0x15, weather.toLong(), 1)
            assertNull("Unadapted weather $weather", f.render(renderer, f.stamp.copy(frame = 101)))
        }
        val overlay = Fixture(); overlay.put(overlay.vram, 0x7000, 0x4280)
        assertNull(overlay.render())
        val nonemptyTileZero = Fixture(); nonemptyTileZero.vram[0] = 0x11
        assertNull(nonemptyTileZero.render())
        val disabled = Fixture(); disabled.put(disabled.regs, 0, 0x1B00)
        disabled.put(disabled.vram, 0x7000, 0x4280)
        assertNotNull(disabled.render()) // Unused BG2 data does not hide an active layer.
    }

    @Test fun priorRamCandidateOnlyMatchesWithinTheSameTimeline() {
        val f = Fixture(); val renderer = Fe8WideRenderer()
        assertNotNull(f.render(renderer))
        f.write(0x02032F88 + (f.height + 1) * 4L, 4, 2)
        f.write(0x02030B88 + 8, 1, 2)
        assertNotNull(f.render(renderer, f.stamp.copy(frame = 101))) // The old map still matches this hardware frame.
        assertNull(f.render(renderer, f.stamp.copy(frame = 102, epoch = 2)))
        renderer.reset()
        assertNull(f.render(renderer))
    }

    @Test fun videoCaptureCopiesEveryInputArray() {
        val f = Fixture(); val capture = f.video()
        f.vram.fill(0); f.palette.fill(0); f.oam.fill(0); f.regs.fill(0)
        assertNotNull(Fe8WideRenderer().render(f.capture(), capture, f.original, 1920, 1080))
        assertThrows(IllegalArgumentException::class.java) { GbaVideoSnapshot(ByteArray(0), f.palette, f.oam, f.regs) }
    }

    @Test fun transparentTerrainUsesBackdropAndFlippedTilesPreservePixelOrder() {
        val f = Fixture()
        f.vram.fill(0, 0x8000, 0x8020)
        f.put(f.palette, 0, 0x001F)
        f.put(f.palette, 6 * 16 * 2, 0x7C00)
        var frame = checkNotNull(f.render())
        assertEquals(0xFFFF0000.toInt(), frame.pixels[20 * frame.width + 284])
        f.vram[0x8000] = 0x01 // One green texel at top left.
        repeat(4) { f.write(0x02030B88 + it * 2L, 0x0C00, 2) }
        repeat(1024) { f.put(f.vram, 0x7800 + it * 2, 0x6C00) }
        frame = checkNotNull(f.render())
        assertEquals(0xFF00FF00.toInt(), frame.pixels[7 * frame.width + 247])
        assertEquals(0xFFFF0000.toInt(), frame.pixels[240])
    }

    @Test fun localR04CaptureMatchesHardwareBeforeRejectingTheSmallMap() {
        val path = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Local game bytes are not redistributed", !path.isNullOrBlank())
        val file = File(path, "r04-09.state")
        val state = file.readBytes().copyOfRange(128, file.length().toInt())
        val capture = MemorySnapshot(Fixture().stamp, listOf(
            MemorySegment(0x02000000, state.copyOfRange(0x21000, 0x61000)),
            MemorySegment(0x03000000, state.copyOfRange(0x19000, 0x21000))))
        val video = GbaVideoSnapshot(state.copyOfRange(0x1000, 0x19000), state.copyOfRange(0x800, 0xC00),
            state.copyOfRange(0xC00, 0x1000), state.copyOfRange(0x400, 0x460))
        // This small map returns before composition. Real captured RAM/BG/OAM establish
        // alignment; output pixel comparisons use Android Bitmap in the instrumented test.
        val original = IntArray(240 * 160) { 0xFF000000.toInt() or it }
        val renderer = Fe8WideRenderer()
        assertNull(renderer.render(capture, video, original, 1920, 1080))
        assertTrue(renderer.lastFailure, renderer.lastFailure?.startsWith("The whole map already fits") == true)
    }

    @Test fun localR04ChapterTwoAddsFourActualOffscreenEnemies() {
        val path = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Local game bytes are not redistributed", !path.isNullOrBlank())
        val file = File(path, "r04-wide-agent-ch2-07.state")
        assumeTrue("Capture the registered chapter-two sample to exercise real expansion", file.isFile)
        val bytes = file.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("a3e90c83fce327cca8670c16f9e394ffbaf8ee01f7b203c951a1be28d4e70d7a", sha)
        val state = bytes.copyOfRange(128, bytes.size)
        val capture = MemorySnapshot(Fixture().stamp, listOf(
            MemorySegment(0x02000000, state.copyOfRange(0x21000, 0x61000)),
            MemorySegment(0x03000000, state.copyOfRange(0x19000, 0x21000))))
        val video = GbaVideoSnapshot(state.copyOfRange(0x1000, 0x19000), state.copyOfRange(0x800, 0xC00),
            state.copyOfRange(0xC00, 0x1000), state.copyOfRange(0x400, 0x460))
        val original = IntArray(240 * 160) { 0xFF000000.toInt() or it }
        val renderer = Fe8WideRenderer()
        val frame = renderer.render(capture, video, original, 1000, 1000)
        assertNotNull(renderer.lastFailure, frame)
        frame!!
        assertEquals(240, frame.width); assertEquals(240, frame.height)
        assertEquals(0, frame.originalX); assertEquals(0, frame.originalY)
        assertEquals(651, frame.matchedTiles); assertEquals(4, frame.drawnSprites)
        assertArrayEquals(original, frame.pixels.copyOfRange(0, original.size))
        // Same red-faction texel in four independently positioned real SMS sprites below y=160.
        for ((x, y) in listOf(166 to 192, 150 to 224, 102 to 176, 118 to 224)) {
            assertEquals("Missing offscreen enemy pixel at $x,$y", 0xFFE71010.toInt(), frame.pixels[y * frame.width + x])
        }
    }

    @Test fun localR04ScrollAndSelectionUseTheCurrentNativeRectangle() {
        val path = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Local game bytes are not redistributed", !path.isNullOrBlank())
        val samples = listOf(
            Triple("idle-south", "452ea03c54da9ac978f49f2ef3318ef86f304f3a49c5d20919cab9cbe5eee24a", 80),
            Triple("idle-north", "69ba9d33fc6f87d7f352213fab805369c490bae4384efa31148cbd017b475d38", 0),
            Triple("selection", "dac4c61cc1bf9907996239fc0ad53606183c6a5413d0d755319089ee9ec755f7", -1),
            Triple("cancel-idle", "541cfef96399ba79aa54fcb994b188c24146aaada31a15db102fe1a0c83226d7", 0),
        )
        assumeTrue("Capture the chapter-two scroll/selection samples", samples.all { File(path, "r04-wide-agent-ch2-${it.first}.state").isFile })
        for ((label, expectedSha, nativeY) in samples) {
            val bytes = File(path, "r04-wide-agent-ch2-$label.state").readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(label, expectedSha, sha)
            val state = bytes.copyOfRange(128, bytes.size)
            val capture = MemorySnapshot(Fixture().stamp, listOf(
                MemorySegment(0x02000000, state.copyOfRange(0x21000, 0x61000)),
                MemorySegment(0x03000000, state.copyOfRange(0x19000, 0x21000))))
            val video = GbaVideoSnapshot(state.copyOfRange(0x1000, 0x19000), state.copyOfRange(0x800, 0xC00),
                state.copyOfRange(0xC00, 0x1000), state.copyOfRange(0x400, 0x460))
            val original = IntArray(240 * 160) { 0xFF000000.toInt() or it }
            val renderer = Fe8WideRenderer()
            val frame = renderer.render(capture, video, original, 1000, 1000)
            if (nativeY < 0) {
                assertNull("Movement overlay must stay native", frame)
            } else {
                assertNotNull("$label ${renderer.lastFailure}", frame)
                frame!!
                assertEquals(label, nativeY, frame.originalY)
                assertEquals(label, nativeY, frame.cameraY)
                assertEquals(label, if (nativeY == 80) 9 else 4, frame.drawnSprites)
                assertArrayEquals(label, original, frame.pixels.copyOfRange(nativeY * 240, (nativeY + 160) * 240))
            }
        }
    }

    @Test fun localR04ChapterThreeExpandsHorizontallyAndDrawsActualOffscreenAllies() {
        val path = System.getenv("EMBERLILY_FE8_REPLAY_DIR")
        assumeTrue("Local game bytes are not redistributed", !path.isNullOrBlank())
        val samples = listOf(
            Triple("world02", "6421e14e39f2347209bdce2bfe58a8322955a803957dd622750d58eb37ca6ca6", 0),
            Triple("idle-left", "e9e6dc58ba9fab4dd75c93b9471df18d8c1a81f5ba116d8ad89ee71ff28e98d3", 0),
            Triple("idle-right", "36184e2fc77517a7850e913d764d9766e16153cf3953e079eff18ac8692b9bda", 32),
            Triple("selection", "139c6e8d57975516eb5bb1fc776928136a1040c283d1b3699f998147ffc2f689", -1),
            Triple("cancel-idle", "e045e0b1c4ff54b77fe0a28a1b20f004268581780b4232b40525a09b1f326d04", 0),
        )
        assumeTrue("Capture the chapter-three horizontal samples", samples.all { File(path, "r04-wide-agent-ch3-${it.first}.state").isFile })
        for ((label, expectedSha, nativeX) in samples) {
            val bytes = File(path, "r04-wide-agent-ch3-$label.state").readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(label, expectedSha, sha)
            val state = bytes.copyOfRange(128, bytes.size)
            val capture = MemorySnapshot(Fixture().stamp, listOf(
                MemorySegment(0x02000000, state.copyOfRange(0x21000, 0x61000)),
                MemorySegment(0x03000000, state.copyOfRange(0x19000, 0x21000))))
            val video = GbaVideoSnapshot(state.copyOfRange(0x1000, 0x19000), state.copyOfRange(0x800, 0xC00),
                state.copyOfRange(0xC00, 0x1000), state.copyOfRange(0x400, 0x460))
            val original = IntArray(240 * 160) { 0xFF000000.toInt() or it }
            val renderer = Fe8WideRenderer()
            val frame = renderer.render(capture, video, original, 1920, 1080)
            if (nativeX < 0) {
                assertNull("Movement overlay must stay native", frame)
                continue
            }
            assertNotNull("$label ${renderer.lastFailure}", frame)
            frame!!
            assertEquals(label, 272, frame.width); assertEquals(label, 160, frame.height)
            assertEquals(label, nativeX, frame.originalX); assertEquals(label, 0, frame.originalY)
            assertEquals(label, nativeX, frame.cameraX); assertEquals(label, 64, frame.cameraY)
            assertEquals(label, 651, frame.matchedTiles)
            assertEquals(label, if (nativeX == 32) 4 else 0, frame.drawnSprites)
            repeat(160) { y ->
                assertArrayEquals(label, original.copyOfRange(y * 240, (y + 1) * 240),
                    frame.pixels.copyOfRange(y * 272 + nativeX, y * 272 + nativeX + 240))
            }
            if (nativeX == 32) {
                for ((pixel, color) in listOf((22 to 123) to 0xFF29A5FF.toInt(), (6 to 108) to 0xFF29A5FF.toInt(),
                    (22 to 98) to 0xFF94BDEF.toInt(), (20 to 65) to 0xFF3952E7.toInt())) {
                    assertEquals("Missing offscreen ally at $pixel", color, frame.pixels[pixel.second * 272 + pixel.first])
                }
            }
        }
    }
}
