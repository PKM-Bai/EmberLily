package com.emberlily.app.fe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * ELSNAP1: 8-byte magic, BE version/body-size, bounded body, SHA-256(header+body).
 * Body strings are length-prefixed ASCII; counters/addresses are BE. Captured GBA bytes
 * retain their original order and are decoded little-endian by MemorySnapshot.
 * A replay capture is diagnostic data, not an executable emulator save state.
 */
object SnapshotCodec {
    private val magic = byteArrayOf(69, 76, 83, 78, 65, 80, 49, 0)
    private const val VERSION = 1
    private const val HEADER_BYTES = 16
    private const val DIGEST_BYTES = 32
    const val MAX_ENCODED_BYTES = MemorySnapshot.MAX_BYTES + 2048

    fun encode(snapshot: MemorySnapshot): ByteArray {
        val bodyBuffer = ByteArrayOutputStream(snapshot.byteCount + 512)
        DataOutputStream(bodyBuffer).use { out ->
            val stamp = snapshot.stamp
            out.write(stamp.romSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            out.ascii(stamp.sessionId)
            out.ascii(stamp.branchId)
            out.writeLong(stamp.epoch)
            out.writeLong(stamp.frame)
            out.writeLong(stamp.revision)
            out.ascii(stamp.adapterId)
            out.writeInt(stamp.adapterVersion)
            out.writeInt(snapshot.ranges.size)
            for (range in snapshot.ranges) {
                out.writeLong(range.address)
                out.writeInt(range.length)
                out.write(snapshot.readBytes(range.address, range.length))
            }
        }
        val body = bodyBuffer.toByteArray()
        val result = ByteArrayOutputStream(body.size + HEADER_BYTES + DIGEST_BYTES)
        DataOutputStream(result).use { out ->
            out.write(magic)
            out.writeInt(VERSION)
            out.writeInt(body.size)
            out.write(body)
            out.write(digest(result.toByteArray()))
        }
        return result.toByteArray().also { require(it.size <= MAX_ENCODED_BYTES) }
    }

    /** Reads a finite artifact and leaves the caller's stream open. No declared length can cause an unbounded allocation. */
    fun decode(input: InputStream, expectedRomSha256: String? = null): MemorySnapshot {
        val bounded = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                require(bounded.size() < MAX_ENCODED_BYTES) { "Snapshot artifact is too large" }
                bounded.write(next)
            } else {
                require(bounded.size() + count <= MAX_ENCODED_BYTES) { "Snapshot artifact is too large" }
                bounded.write(chunk, 0, count)
            }
        }
        return decode(bounded.toByteArray(), expectedRomSha256)
    }

    fun decode(encoded: ByteArray, expectedRomSha256: String? = null): MemorySnapshot {
        require(encoded.size in (HEADER_BYTES + DIGEST_BYTES + 1)..MAX_ENCODED_BYTES) { "Invalid snapshot artifact size" }
        val authenticated = encoded.copyOfRange(0, encoded.size - DIGEST_BYTES)
        require(MessageDigest.isEqual(digest(authenticated), encoded.copyOfRange(authenticated.size, encoded.size))) {
            "Snapshot artifact checksum mismatch"
        }
        try {
            DataInputStream(ByteArrayInputStream(authenticated)).use { input ->
                val actualMagic = ByteArray(magic.size).also(input::readFully)
                require(actualMagic.contentEquals(magic) && input.readInt() == VERSION) { "Unsupported snapshot format" }
                require(input.readInt() == authenticated.size - HEADER_BYTES) { "Snapshot size declaration is inconsistent" }
                val sha = ByteArray(32).also(input::readFully).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(expectedRomSha256 == null || expectedRomSha256 == sha) { "Snapshot belongs to a different ROM" }
                val session = input.ascii(36)
                val branch = input.ascii(36)
                val epoch = input.readLong()
                val frame = input.readLong()
                val revision = input.readLong()
                val adapterId = input.ascii(64)
                val stamp = SnapshotStamp(sha, session, branch, epoch, frame, revision, adapterId, input.readInt())
                val count = input.readInt()
                require(count in 1..MemorySnapshot.MAX_SEGMENTS) { "Invalid segment count" }
                var total = 0L
                val segments = ArrayList<MemorySegment>(count)
                repeat(count) {
                    val address = input.readLong()
                    val length = input.readInt()
                    MemoryRange(address, length)
                    total += length
                    require(total <= MemorySnapshot.MAX_BYTES && length <= input.available()) { "Invalid segment size" }
                    segments += MemorySegment(address, ByteArray(length).also(input::readFully))
                }
                require(input.available() == 0) { "Unexpected trailing snapshot data" }
                return MemorySnapshot(stamp, segments)
            }
        } catch (failure: java.io.IOException) {
            throw IllegalArgumentException("Truncated snapshot artifact", failure)
        }
    }

    private fun DataOutputStream.ascii(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeShort(bytes.size)
        write(bytes)
    }
    private fun DataInputStream.ascii(maximum: Int): String {
        val length = readUnsignedShort()
        require(length in 1..maximum && length <= available()) { "Invalid snapshot metadata string" }
        val bytes = ByteArray(length).also(::readFully)
        require(bytes.all { it.toInt() in 0..127 }) { "Snapshot metadata must be ASCII" }
        return String(bytes, Charsets.US_ASCII)
    }
    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Holds immutable decoded captures. Seeking is explicit and never writes to a live core. */
class SnapshotReplay(captures: List<MemorySnapshot>) {
    private val captures = captures.toList()
    var position: Int = 0
        private set
    val size: Int get() = captures.size
    val current: MemorySnapshot get() = captures[position]

    init {
        require(captures.isNotEmpty() && captures.size <= 1024) { "Replay requires 1..1024 snapshots" }
        require(captures.sumOf { it.byteCount.toLong() } <= 32L * 1024 * 1024) { "Replay exceeds 32 MiB" }
        val first = captures.first().stamp
        require(captures.all { it.stamp.romSha256 == first.romSha256 && it.stamp.sessionId == first.sessionId &&
            it.stamp.branchId == first.branchId && it.stamp.adapterId == first.adapterId && it.stamp.adapterVersion == first.adapterVersion }) {
            "A replay cannot mix ROMs, sessions, branches or adapters"
        }
    }

    fun seek(index: Int): MemorySnapshot {
        require(index in captures.indices) { "Replay index is out of bounds" }
        position = index
        return current
    }
}
