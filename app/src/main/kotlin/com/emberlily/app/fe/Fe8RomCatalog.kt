package com.emberlily.app.fe

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest

/** Static FE8J table locations verified separately for the two registered inputs; not FE8U addresses. */
object Fe8JLayout {
    const val BM_STATE = 0x0202BCACL
    const val PLAY_STATE = 0x0202BCECL
    const val PLAYER_UNITS = 0x0202BE48L
    const val ENEMY_UNITS = 0x0202CFB8L
    const val NPC_UNITS = 0x0202DDC8L
    const val UNIT_SIZE = 0x48
    const val ACTIVE_UNIT = 0x03004DF0L
    const val MAP_SIZE = 0x0202E4D0L
    const val MAP_UNIT_ROWS = 0x0202E4D4L
    const val MAP_TERRAIN_ROWS = 0x0202E4D8L
    const val PROC_POOL = 0x02024E68L
    const val PROC_COUNT = 64
    const val PROC_SIZE = 0x6C
    const val PROC_ROOTS = 0x02026A70L
    const val MAP_TASK_SCRIPT = 0x085C5DE8L
    const val BMAP_MAIN_SCRIPT = 0x085C2670L
    const val PLAYER_PHASE_SCRIPT = 0x085C2F58L
    const val PLAYER_IDLE_CURSOR = 0x085C2FD8L
    const val PLAYER_IDLE_CALLBACK = 0x0801C5A9L
    const val PLAYER_MOVEMENT_CURSOR = 0x085C3020L
    const val PLAYER_MOVEMENT_CALLBACK = 0x0801C985L
    const val CHARACTER_TABLE = 0x088582BCL // First record is character ID 1, not a zero sentinel.
    const val CHARACTER_SIZE = 0x34
    const val CLASS_TABLE = 0x0885B668L
    const val CLASS_SIZE = 0x54
    const val ITEM_TABLE = 0x0885E068L
    const val ITEM_SIZE = 0x24
    const val TEXT_TABLE = 0x0814D08CL
    const val CONVOY = 0x0203A818L
    val captureRanges = listOf(MemoryRange(0x02000000, 0x40000), MemoryRange(0x03000000, 0x8000))
}

/** Stores no distributable game data; bytes are supplied from a fingerprinted local ROM at runtime. */
class FeRomCatalog private constructor(val profile: RomProfile, private val rom: ByteArray) {
    private val layout = profile.layout
    private val names = mutableMapOf<Int, FeName>()
    private val treeBase = u32(layout.huffmanBaseLiteral)
    private val treeRoot = u32(u32(layout.huffmanRootLiteral))

    fun contains(address: Long, size: Int): Boolean = address >= 0x08000000L && size > 0 &&
        address - 0x08000000L <= rom.size.toLong() - size
    fun u8(address: Long): Int { require(contains(address, 1)); return rom[(address - 0x08000000L).toInt()].toInt() and 255 }
    fun u16(address: Long): Int { require(contains(address, 2)); return u8(address) or (u8(address + 1) shl 8) }
    fun u32(address: Long): Long { require(contains(address, 4)); return (0..3).fold(0L) { all, i -> all or (u8(address + i).toLong() shl (8 * i)) } }

    fun characterId(address: Long): Int {
        require(address >= layout.characterTable && (address - layout.characterTable) % layout.characterSize == 0L)
        val record = ((address - layout.characterTable) / layout.characterSize).toInt()
        require(record in 0..254 && contains(address, layout.characterSize))
        return u8(address + 4).also { require(it == record + layout.characterFirstId) { "Character table identity mismatch" } }
    }

    fun classId(address: Long): Int {
        require(address >= layout.classTable && (address - layout.classTable) % layout.classSize == 0L)
        val record = ((address - layout.classTable) / layout.classSize).toInt()
        require(record in 0..255 && contains(address, layout.classSize))
        return u8(address + 4).also { require(it == record + layout.classFirstId) { "Class table identity mismatch" } }
    }

    fun recordName(address: Long): FeName = name(u16(address))

    fun item(raw: Int, slot: Int): FeItemSlot {
        require(raw in 0..65535 && slot in 0..99)
        val id = raw and 255
        if (id == 0) return FeItemSlot(slot, raw, 0, 0, FeName(0, "空"), null)
        val address = layout.itemTable + id * layout.itemSize
        if (!contains(address, layout.itemSize) || u8(address + 6) != id) {
            return FeItemSlot(slot, raw, id, raw ushr 8, FeName(0, null, "未识别物品 #$id"), null)
        }
        return FeItemSlot(slot, raw, id, raw ushr 8, recordName(address), u8(address + 0x14),
            FeItemDetails(u8(address + 7), u32(address + 8), u8(address + 0x15), u8(address + 0x16),
                u8(address + 0x17), u8(address + 0x18), u8(address + 0x19)))
    }

    @Synchronized fun name(id: Int): FeName = names.getOrPut(id) {
        if (id == 0) FeName(0, null, "无名称")
        else if (profile.nameEncoding == FeNameEncoding.UNVERIFIED_CUSTOM) FeName(id, null, "此中文构建的自定义字库映射尚未解析")
        else runCatching {
            val bytes = decodeNameBytes(id)
            require(bytes.isNotEmpty() && bytes.none { (it.toInt() and 255) < 0x20 }) { "Name contains unparsed text commands" }
            val decoder = Charset.forName("Shift_JIS").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            FeName(id, decoder.decode(ByteBuffer.wrap(bytes)).toString())
        }.getOrElse { FeName(id, null, "名称编码或控制符尚未解析") }
    }

    private fun decodeNameBytes(id: Int): ByteArray {
        require(id in 1..65535)
        val encodedPointer = u32(layout.textTable + id * 4L)
        val output = ArrayList<Byte>(64)
        if (encodedPointer and 0x80000000L != 0L) {
            val address = encodedPointer and 0x7FFFFFFFL
            for (offset in 0 until 512) {
                val byte = u8(address + offset)
                if (byte == 0) return output.toByteArray()
                output += byte.toByte()
            }
            throw IllegalArgumentException("Unterminated uncompressed name")
        }
        require(contains(encodedPointer, 1) && contains(treeBase, 4) && contains(treeRoot, 4))
        var node = treeRoot
        for (bitIndex in 0 until 32768) {
            val bit = (u8(encodedPointer + bitIndex / 8) ushr (bitIndex % 8)) and 1
            node = treeBase + u16(node + bit * 2L) * 4L
            val value = u32(node)
            if (value and 0x80000000L != 0L) {
                val low = (value and 255).toInt()
                if (low == 0) return output.toByteArray()
                output += low.toByte()
                val high = ((value ushr 8) and 255).toInt()
                if (high != 0) output += high.toByte()
                require(output.size <= 512) { "Name text exceeds its bound" }
                node = treeRoot
            }
        }
        throw IllegalArgumentException("Name Huffman stream exceeds its bound")
    }

    companion object {
        fun load(profile: RomProfile, romBytes: ByteArray): FeRomCatalog {
            require(RomProfiles.identify(profile.identity.sha256)?.let { it.id == profile.id && it.version == profile.version &&
                it.identity == profile.identity && it.layout == profile.layout && it.nameEncoding == profile.nameEncoding } == true) { "Unregistered ROM adapter" }
            require(romBytes.size == profile.identity.size) { "ROM size does not match profile" }
            val copy = romBytes.copyOf()
            val sha = MessageDigest.getInstance("SHA-256").digest(copy).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha == profile.identity.sha256) { "ROM bytes do not match profile" }
            return FeRomCatalog(profile, copy)
        }
    }
}

/** Source compatibility for the M1 host and replay tools. */
typealias Fe8RomCatalog = FeRomCatalog
