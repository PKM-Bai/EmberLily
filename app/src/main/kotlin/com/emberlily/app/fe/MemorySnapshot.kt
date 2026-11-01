package com.emberlily.app.fe

import java.util.Collections

/** Identity is assigned by the emulation owner, never inferred from RAM contents. */
data class SnapshotStamp(
    val romSha256: String,
    val sessionId: String,
    val branchId: String,
    val epoch: Long,
    val frame: Long,
    val revision: Long,
    val adapterId: String = "gba-raw",
    val adapterVersion: Int = 1,
) {
    init {
        require(SHA.matches(romSha256)) { "Expected a canonical ROM SHA-256" }
        require(UUID.matches(sessionId)) { "Expected a session UUID" }
        require(branchId == "main" || UUID.matches(branchId)) { "Expected main or a branch UUID" }
        require(epoch >= 0 && frame >= 0 && revision >= 0) { "Snapshot counters must be nonnegative" }
        require(ADAPTER.matches(adapterId) && adapterVersion > 0) { "Invalid adapter identity" }
    }

    /** Frames can advance within a context. Restores and semantic invalidations change epoch. */
    fun sameContext(other: SnapshotStamp): Boolean =
        romSha256 == other.romSha256 && sessionId == other.sessionId && branchId == other.branchId &&
            epoch == other.epoch && revision == other.revision &&
            adapterId == other.adapterId && adapterVersion == other.adapterVersion

    companion object {
        private val SHA = Regex("[0-9a-f]{64}")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ADAPTER = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

data class MemoryRange(val address: Long, val length: Int) {
    init {
        require(length > 0) { "Memory range cannot be empty" }
        val end = address + length.toLong()
        require(address in 0x02000000L..0x0203FFFFL && end <= 0x02040000L ||
            address in 0x03000000L..0x03007FFFL && end <= 0x03008000L) {
            "Snapshots accept canonical EWRAM or IWRAM only"
        }
    }
    val endExclusive: Long get() = address + length
}

/** The constructor and every byte-returning method copy, so captures cannot change underneath a decoder. */
class MemorySegment(val address: Long, bytes: ByteArray) {
    val range = MemoryRange(address, bytes.size)
    private val data = bytes.copyOf()
    val length: Int get() = data.size
    fun copyBytes(): ByteArray = data.copyOf()
    internal fun read(offset: Int, length: Int): ByteArray = data.copyOfRange(offset, offset + length)
    internal fun byteAt(offset: Int): Int = data[offset].toInt() and 255
}

/** A bounded RAM capture. Static ROM data belongs to a separately fingerprinted catalog. */
class MemorySnapshot(val stamp: SnapshotStamp, segments: List<MemorySegment>) {
    private val contents: List<MemorySegment>
    val ranges: List<MemoryRange>
    val byteCount: Int

    init {
        require(segments.size in 1..MAX_SEGMENTS) { "Snapshot requires 1..$MAX_SEGMENTS segments" }
        contents = segments.sortedBy { it.address }.toList()
        require(contents.zipWithNext().all { (a, b) -> a.range.endExclusive <= b.address }) {
            "Snapshot segments overlap or repeat"
        }
        val total = contents.sumOf { it.length.toLong() }
        require(total <= MAX_BYTES) { "Snapshot exceeds the RAM capture limit" }
        byteCount = total.toInt()
        ranges = Collections.unmodifiableList(contents.map { it.range })
    }

    private fun locate(address: Long, length: Int): Pair<MemorySegment, Int> {
        require(address >= 0 && length > 0 && address <= Long.MAX_VALUE - length) { "Invalid read range" }
        val segment = contents.firstOrNull { address >= it.address && address + length <= it.range.endExclusive }
            ?: throw IllegalArgumentException("Read is outside a captured segment: 0x${address.toString(16)}+$length")
        return segment to (address - segment.address).toInt()
    }

    fun readBytes(address: Long, length: Int): ByteArray {
        val (segment, offset) = locate(address, length)
        return segment.read(offset, length)
    }

    fun contains(address: Long, length: Int): Boolean =
        address >= 0 && length > 0 && address <= Long.MAX_VALUE - length &&
            contents.any { address >= it.address && address + length <= it.range.endExclusive }

    fun u8(address: Long): Int {
        val (segment, offset) = locate(address, 1)
        return segment.byteAt(offset)
    }
    fun s8(address: Long): Int = u8(address).toByte().toInt()
    fun u16(address: Long): Int {
        val (segment, offset) = locate(address, 2)
        return segment.byteAt(offset) or (segment.byteAt(offset + 1) shl 8)
    }
    fun s16(address: Long): Int = u16(address).toShort().toInt()
    fun u32(address: Long): Long {
        val (segment, offset) = locate(address, 4)
        return (0..3).fold(0L) { result, index -> result or (segment.byteAt(offset + index).toLong() shl (index * 8)) }
    }

    companion object {
        const val MAX_SEGMENTS = 8
        const val MAX_BYTES = 1024 * 1024
    }
}

/** Publish and command guards share context rules, while commands tolerate intervening frames. */
class SnapshotGate {
    private var current: SnapshotStamp? = null

    /** Only the owner may reset after open/restore/branch changes; this never advances a game. */
    @Synchronized fun reset(stamp: SnapshotStamp) { current = stamp }
    @Synchronized fun clear() { current = null }
    @Synchronized fun advance(stamp: SnapshotStamp): Boolean {
        val previous = current ?: return false
        if (!previous.sameContext(stamp) || stamp.frame < previous.frame) return false
        current = stamp
        return true
    }
    @Synchronized fun acceptExact(stamp: SnapshotStamp): Boolean = current == stamp

    /** This is only a context check: a command must still revalidate its target and expected bytes. */
    @Synchronized fun acceptContext(stamp: SnapshotStamp): Boolean =
        current?.let { it.sameContext(stamp) && stamp.frame <= it.frame } ?: false
}
