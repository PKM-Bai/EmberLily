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

    /** Releases keys without advancing a frame; returns the previous mask for verification. */
    external fun releaseKeys(handle: Long): Int

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

    /** Side-effect-free copy of the 0x60 LCD register bytes; call on the emulation owner between frames. */
    external fun readVideoRegisters(handle: Long): ByteArray

    /**
     * Compares every expected range, then commits all replacements under one core
     * lock without advancing emulation. Returns false on any byte mismatch, with
     * no memory changed. Invalid arguments throw IllegalArgumentException; a closed
     * or poisoned handle throws IllegalStateException.
     *
     * Accepts 1-64 nonoverlapping ranges wholly within canonical EWRAM or IWRAM,
     * at most 16 KiB total. Every expected/replacement pair must have the same
     * nonzero length. Equal pairs are read-only guards. ROM, IO, save/video buses,
     * mirrors, zero-length and overlapping ranges are rejected before any write.
     * Do not mutate the input arrays until this synchronous call returns.
     *
     * This primitive establishes byte-level atomicity only. The host must validate
     * semantic commands, ROM/session/branch and state versions, enforce experiment
     * permissions where needed, and preserve a recovery point before calling it.
     * It never invokes game functions or validates game-rule legality.
     */
    external fun compareAndWriteMemory(
        handle: Long,
        addresses: LongArray,
        expected: Array<ByteArray>,
        replacement: Array<ByteArray>,
    ): Boolean

    external fun coreVersion(): String
}
