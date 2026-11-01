package com.emberlily.app.graphics

import com.emberlily.app.fe.Fe8JLayout
import com.emberlily.app.fe.Fe8Processes
import com.emberlily.app.fe.MemorySnapshot
import com.emberlily.app.fe.RomProfiles
import com.emberlily.app.fe.SnapshotStamp
import kotlin.math.ceil

/** One frame's video memory, copied by the same owner that captured RAM and the original frame. */
class GbaVideoSnapshot(vram: ByteArray, palette: ByteArray, oam: ByteArray, registers: ByteArray) {
    internal val vram = vram.copyOf()
    internal val palette = palette.copyOf()
    internal val oam = oam.copyOf()
    internal val registers = registers.copyOf()
    init { require(vram.size == 0x18000 && palette.size == 0x400 && oam.size == 0x400 && registers.size == 0x60) }
}

data class WideFrame(
    val width: Int, val height: Int, val pixels: IntArray,
    val originalX: Int, val originalY: Int, val cameraX: Int, val cameraY: Int,
    val matchedTiles: Int, val drawnSprites: Int,
)

/**
 * FE8 R04 map expansion. The original 240x160 rectangle is copied verbatim. Additional pixels
 * come from the live map's metatiles, loaded BG/OBJ tiles and palettes; never stretched or guessed.
 * Unknown scenes or inconsistent reconstruction return null. No RAM/ROM/video input is modified.
 */
class Fe8WideRenderer {
    private data class Candidate(val stamp: SnapshotStamp, val ram: ByteArray)
    private data class Sprite(val address: Long, val x: Int, val y: Int, val width: Int, val height: Int, val attributes: Int)
    private var previous: Candidate? = null
    /** Diagnostic only; callers continue displaying the original frame when reconstruction fails. */
    var lastFailure: String? = null
        private set

    fun reset() { previous = null; lastFailure = null }

    fun render(ram: MemorySnapshot, video: GbaVideoSnapshot, originalArgb: IntArray,
        viewportWidth: Int, viewportHeight: Int): WideFrame? {
        require(originalArgb.size == 240 * 160)
        if (ram.stamp.romSha256 != RomProfiles.FE8J_VOICE_SHA256 || viewportWidth <= 0 || viewportHeight <= 0) {
            reset(); return null
        }
        if (!isIdleMap(ram) || !validVideo(video)) { reset(); return null }
        val current = Candidate(ram.stamp, ram.readBytes(0x02000000, 0x40000))
        val old = previous?.takeIf { it.stamp.sameContext(ram.stamp) && it.stamp.frame + 1 == ram.stamp.frame }
        previous = current
        // RAM after VBlank can already describe the next frame. Match both coherent candidates
        // against this frame's actual BG tilemap and OAM, never reuse old rendered scenery.
        val result = runCatching { rebuild(current.ram, video, originalArgb, viewportWidth, viewportHeight) }
        val frame = result.getOrNull()
            ?: old?.let { runCatching { rebuild(it.ram, video, originalArgb, viewportWidth, viewportHeight) }.getOrNull() }
        lastFailure = if (frame != null) null else result.exceptionOrNull()?.let {
            "${it.message} at line ${it.stackTrace.firstOrNull { trace -> trace.className.startsWith(javaClass.name) }?.lineNumber}"
        }
        return frame
    }

    private fun isIdleMap(ram: MemorySnapshot): Boolean = runCatching {
        if (ram.u8(Fe8JLayout.PLAY_STATE + 0x0F) != 0 || ram.u8(Fe8JLayout.BM_STATE + 1) != 0 ||
            ram.u8(Fe8JLayout.BM_STATE + 2) != 0 || ram.u8(Fe8JLayout.BM_STATE + 4) != 0) return false
        // Rain/snow/particles, clouds and night effects need their own layer/scanline handling.
        // WEATHER_FINE is the only currently adapted value; unknown weather also falls back.
        if (ram.u8(Fe8JLayout.PLAY_STATE + 0x15) != 0) return false
        val processes = Fe8Processes.read(ram) {
            it in 0x08000000L..(0x08000000L + RomProfiles.fe8jVoice.identity.size - 8) && it % 4 == 0L
        }
        if (processes.any { it.script in setOf(0x085B98A0L, 0x085B98D8L, 0x085C3248L) }) return false
        val map = processes.singleOrNull { it.script == Fe8JLayout.MAP_TASK_SCRIPT } ?: return false
        val player = processes.singleOrNull { it.script == Fe8JLayout.PLAYER_PHASE_SCRIPT } ?: return false
        val parent = processes.singleOrNull { it.address == ram.u32(player.address + 0x14) && it.script == Fe8JLayout.BMAP_MAIN_SCRIPT }
            ?: return false
        map.lock == 0 && player.lock == 0 && parent.lock == 1 && ram.u32(parent.address + 0x18) == player.address &&
            ram.u32(player.address + 4) == Fe8JLayout.PLAYER_IDLE_CURSOR &&
            ram.u32(player.address + 0x0C) == Fe8JLayout.PLAYER_IDLE_CALLBACK &&
            ram.u32(player.address + 0x18) == 0L && ram.s16(player.address + 0x24) == 0
    }.getOrDefault(false)

    private fun validVideo(video: GbaVideoSnapshot): Boolean {
        val disp = u16(video.registers, 0)
        val bg = u16(video.registers, 0x0E)
        val blend = u16(video.registers, 0x50)
        // Mode 0 / BG3 / OBJ, no forced blank, windows, mosaic, 8bpp, or unsupported BG3/OBJ colour effects.
        return disp and 0xE087 == 0 && disp and 0x1800 == 0x1800 && bg and 0xC0C0 == 0 && bg and 3 == 3 &&
            (blend and 0xC0 == 0 || blend and 0x18 == 0) && emptyOverlay(video, disp)
    }

    private fun emptyOverlay(video: GbaVideoSnapshot, display: Int): Boolean {
        // BG2 carries movement/range and map effects. Only its verified idle, transparent tile-0
        // state is adapted; do not silently remove an unfamiliar layer from the expanded area.
        if (display and 0x0400 == 0) return true
        val control = u16(video.registers, 0x0C)
        if (control and 0xC0C0 != 0) return false
        val tilemap = ((control ushr 8) and 31) * 0x800
        val tiles = ((control ushr 2) and 3) * 0x4000
        if ((0 until 32).any { video.vram[tiles + it] != 0.toByte() }) return false
        return (0 until 1024).all { u16(video.vram, tilemap + it * 2) and 0x3FF == 0 }
    }

    private fun rebuild(ram: ByteArray, video: GbaVideoSnapshot, original: IntArray, vw: Int, vh: Int): WideFrame {
        fun u8(address: Long): Int { require(address in 0x02000000..0x0203FFFF); return ram[(address - 0x02000000).toInt()].toInt() and 255 }
        fun r16(address: Long): Int = u8(address) or (u8(address + 1) shl 8)
        fun r32(address: Long): Long = r16(address).toLong() or (r16(address + 2).toLong() shl 16)
        fun s16(address: Long): Int = r16(address).toShort().toInt()
        val mapW = r16(Fe8JLayout.MAP_SIZE)
        val mapH = r16(Fe8JLayout.MAP_SIZE + 2)
        require(mapW in 15..128 && mapH in 10..128)
        val cameraX = s16(Fe8JLayout.BM_STATE + 0x0C)
        val cameraY = s16(Fe8JLayout.BM_STATE + 0x0E)
        require(cameraX in 0..(mapW * 16 - 240) && cameraY in 0..(mapH * 16 - 160))
        val originX = r16(Fe8JLayout.BM_STATE + 0x24) * 16
        val originY = r16(Fe8JLayout.BM_STATE + 0x26) * 16
        val scrollX = u16(video.registers, 0x1C) and 255
        val scrollY = u16(video.registers, 0x1E) and 255
        require(((cameraX - originX) and 255) == scrollX && ((cameraY - originY) and 255) == scrollY)
        val mapRows = LongArray(mapH) { y -> r32(BASE_TILES + y * 4L).also {
            require(it >= BASE_TILES + (mapH + 1) * 4 && it + mapW * 2 <= BASE_TILES_END && it % 2 == 0L)
        } }
        val fogTable = r32(0x0202E4E4)
        val fogRows = LongArray(mapH) { y -> r32(fogTable + y * 4L).also {
            require(it >= 0x02000000L && it + mapW <= 0x02040000L)
        } }
        fun descriptor(x: Int, y: Int): Int {
            val tileX = x / 16; val tileY = y / 16
            require(tileX in 0 until mapW && tileY in 0 until mapH)
            val cell = r16(mapRows[tileY] + tileX * 2L)
            require(cell in 0..0xFFC && cell % 4 == 0)
            val quad = (y / 8 and 1) * 2 + (x / 8 and 1)
            return (r16(TILESET_CONFIG + (cell + quad) * 2L) + if (u8(fogRows[tileY] + tileX) != 0) 0x6000 else 0xB000) and 65535
        }
        val bgControl = u16(video.registers, 0x0E)
        val bgScreen = ((bgControl ushr 8) and 31) * 0x800
        val bgChar = ((bgControl ushr 2) and 3) * 0x4000
        // Every hardware BG3 tile touched by the original screen must agree, including partial edge tiles.
        var matched = 0
        for (sy in 0..160 step 8) for (sx in 0..240 step 8) {
            val x = minOf(sx, 239); val y = minOf(sy, 159)
            val row = ((scrollY + y) / 8) and 31; val col = ((scrollX + x) / 8) and 31
            require(descriptor(cameraX + x, cameraY + y) == u16(video.vram, bgScreen + (row * 32 + col) * 2))
            matched++
        }
        val sprites = ArrayList<Sprite>()
        val seen = HashSet<Long>()
        var pointer = r32(SMS_HEAD)
        while (pointer != 0L) {
            require(pointer in (SMS_HEAD + 12)..(SMS_END - 12) && (pointer - SMS_HEAD) % 12 == 0L && seen.add(pointer))
            val config = u8(pointer + 0x0B)
            if (config and 0x80 != 0) {
                // A hovered unit is drawn by the native MU animator instead of SMS. Until that
                // separate animation is adapted, its whole possible footprint must be native.
                val x = s16(pointer + 4) - cameraX; val y = s16(pointer + 6) - cameraY
                require(x >= 8 && x + 24 <= 240 && y >= 16 && y + 16 <= 160)
            }
            if (config and 0x80 == 0) {
                require(config and 0x40 == 0) // Shake uses the game's clock, not a fabricated animation.
                val kind = config and 15
                require(kind in 0..5)
                val shape = kind % 3
                val x = s16(pointer + 4) - if (shape == 2) 8 else 0
                val y = s16(pointer + 6) - if (shape != 0) 16 else 0
                val sprite = Sprite(pointer, x, y, if (shape == 2) 32 else 16, if (shape == 0) 16 else 32,
                    (r16(pointer + 8) + if (kind >= 3) 0xC00 else 0x800) and 65535)
                val sx = s16(pointer + 4) - cameraX
                val sy = s16(pointer + 6) - cameraY
                if (sx in -16..240 && sy in -32..160) require(oamMatches(video.oam, sprite, cameraX, cameraY))
                sprites += sprite
            }
            pointer = r32(pointer)
        }
        // The game's SMS builder does not cull offscreen units. Reject stale/missing handles instead of showing empty ground.
        val unitMap = r32(0x0202E4D4)
        for ((base, count) in listOf(Fe8JLayout.PLAYER_UNITS to 62, Fe8JLayout.ENEMY_UNITS to 50, Fe8JLayout.NPC_UNITS to 20)) {
            repeat(count) { i ->
                val at = base + i * 0x48L
                if (r32(at) != 0L && r32(at + 0x0C) and 0x201 == 0L) {
                    val x = u8(at + 0x10).toByte().toInt(); val y = u8(at + 0x11).toByte().toInt()
                    if (x in 0 until mapW && y in 0 until mapH && u8(r32(unitMap + y * 4L) + x) != 0) require(r32(at + 0x3C) in seen)
                }
            }
        }
        val aspect = vw.toDouble() / vh
        val width = minOf(mapW * 16, maxOf(240, ceil(160 * aspect).toInt())).coerceAtMost(640)
        val height = minOf(mapH * 16, maxOf(160, ceil(240 / aspect).toInt())).coerceAtMost(480)
        require(width > 240 || height > 160) { "The whole map already fits in the original screen" }
        val worldX = (cameraX - (width - 240) / 2).coerceIn(0, mapW * 16 - width)
        val worldY = (cameraY - (height - 160) / 2).coerceIn(0, mapH * 16 - height)
        val originalX = cameraX - worldX; val originalY = cameraY - worldY
        val colors = IntArray(512) { argb(u16(video.palette, it * 2)) }
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            if (x in originalX until originalX + 240 && y in originalY until originalY + 160) continue
            val mapX = worldX + x; val mapY = worldY + y
            val desc = descriptor(mapX, mapY)
            var px = mapX and 7; var py = mapY and 7
            if (desc and 0x400 != 0) px = 7 - px
            if (desc and 0x800 != 0) py = 7 - py
            val address = bgChar + (desc and 0x3FF) * 32 + py * 4 + px / 2
            require(address < 0x10000)
            val index = (video.vram[address].toInt() ushr (px % 2 * 4)) and 15
            // A zero texel is transparent on every BG palette bank and reveals the backdrop.
            out[y * width + x] = colors[if (index == 0) 0 else (desc ushr 12) * 16 + index]
        }
        var drawn = 0
        // Lower priority numbers win; earlier SMS/OAM entries win equal-priority overlaps.
        val backToFront = sprites.withIndex().sortedWith(
            compareByDescending<IndexedValue<Sprite>> { (it.value.attributes ushr 10) and 3 }.thenByDescending { it.index })
        for ((_, sprite) in backToFront) {
            val left = sprite.x - worldX; val top = sprite.y - worldY
            if (left >= width || top >= height || left + sprite.width <= 0 || top + sprite.height <= 0) continue
            for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
                val dx = left + x; val dy = top + y
                if (dx !in 0 until width || dy !in 0 until height) continue
                if (dx in originalX until originalX + 240 && dy in originalY until originalY + 160) continue
                val tile = (sprite.attributes and 0x3FF) + (y / 8) * (if (u16(video.registers, 0) and 0x40 != 0) sprite.width / 8 else 32)
                val address = 0x10000 + (tile + x / 8) * 32 + (y and 7) * 4 + (x and 7) / 2
                require(address in 0x10000 until 0x18000)
                val index = (video.vram[address].toInt() ushr (x % 2 * 4)) and 15
                if (index != 0) out[dy * width + dx] = colors[256 + (sprite.attributes ushr 12) * 16 + index]
            }
            if (left < originalX || top < originalY || left + sprite.width > originalX + 240 || top + sprite.height > originalY + 160) drawn++
        }
        for (y in 0 until 160) original.copyInto(out, (originalY + y) * width + originalX, y * 240, (y + 1) * 240)
        return WideFrame(width, height, out, originalX, originalY, cameraX, cameraY, matched, drawn)
    }

    private fun oamMatches(oam: ByteArray, sprite: Sprite, cameraX: Int, cameraY: Int): Boolean {
        val shape = if (sprite.width == 16 && sprite.height == 32) 2 else 0
        val size = if (sprite.width == 16 && sprite.height == 16) 1 else 2
        for (i in 0 until 128) {
            val a = u16(oam, i * 8); val b = u16(oam, i * 8 + 2); val c = u16(oam, i * 8 + 4)
            if (a and 0x3F00 == 0 && a ushr 14 == shape && b ushr 14 == size && b and 0x3000 == 0 &&
                (a and 255) == ((sprite.y - cameraY) and 255) && (b and 511) == ((sprite.x - cameraX) and 511) && c == sprite.attributes) return true
        }
        return false
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < bytes.size)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }
    private fun argb(c: Int): Int {
        val r = c and 31; val g = (c ushr 5) and 31; val b = (c ushr 10) and 31
        return (0xFF shl 24) or (((r shl 3) or (r ushr 2)) shl 16) or (((g shl 3) or (g ushr 2)) shl 8) or ((b shl 3) or (b ushr 2))
    }
    private companion object {
        const val TILESET_CONFIG = 0x02030B88L
        const val BASE_TILES = 0x02032F88L
        const val BASE_TILES_END = 0x02033EF8L
        const val SMS_HEAD = 0x0203A014L
        const val SMS_END = 0x0203A4C4L
    }
}
