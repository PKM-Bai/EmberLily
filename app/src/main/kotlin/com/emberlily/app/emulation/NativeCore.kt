package com.emberlily.app.emulation

/**
 * Synchronous GBA core. The host owns the single emulation thread and calls every
 * method between frames. Handles are opaque tokens, never native addresses.
 */
object NativeCore {
    init {
        System.loadLibrary("emberlily")
    }

    /** Returns zero on an unreadable ROM or unwritable branch save. No implicit save paths. */
    external fun open(romPath: String, savePath: String): Long

    /** Flushes battery data and releases the core. Repeated close is harmless. */
    external fun close(handle: Long)

    /** Uses GBA key bits: A, B, Select, Start, Right, Left, Up, Down, R, L. */
    external fun runFrame(handle: Long, keys: Int)

    /** Copies 240 × 160 opaque Android ARGB pixels; false until a frame has run. */
    external fun copyFrame(handle: Long, pixels: IntArray): Boolean

    /** 32768 Hz, interleaved stereo PCM16. Returns shorts copied, always even. */
    external fun drainAudio(handle: Long, samples: ShortArray): Int

    /**
     * Writes an EmberLily state containing CPU/RAM, battery data and RTC. Success
     * also means the open branch's battery file has been flushed for branch copies.
     * This internal format is not an mGBA .ss0 import/export interface.
     */
    external fun saveState(handle: Long, path: String): Boolean

    /** Restores both emulation and battery data, writing only the open branch's save. */
    external fun loadState(handle: Long, path: String): Boolean

    /**
     * Copies at most 1 MiB from one canonical BIOS/RAM/video/ROM region. Mirrors,
     * IO registers and cartridge save buses are excluded. Invalid ranges throw.
     * Multiple calls form one snapshot only when no frame runs between them.
     */
    external fun readMemory(handle: Long, address: Long, size: Int): ByteArray

    external fun coreVersion(): String
}
