package com.emberlily.app.fe

import java.util.Collections

internal data class Fe8Process(val address: Long, val script: Long, val lock: Int)

/** Walks live roots; stale records in a released pool slot must never establish scene validity. */
internal object Fe8Processes {
    fun read(snapshot: MemorySnapshot, validScript: (Long) -> Boolean): List<Fe8Process> {
        val found = ArrayList<Fe8Process>()
        val seen = HashSet<Long>()
        fun visit(address: Long) {
            if (address == 0L) return
            val offset = address - Fe8JLayout.PROC_POOL
            require(offset >= 0 && offset < Fe8JLayout.PROC_COUNT * Fe8JLayout.PROC_SIZE && offset % Fe8JLayout.PROC_SIZE == 0L) {
                "Process pointer is outside the aligned pool"
            }
            require(seen.add(address)) { "Process graph has duplicate or cyclic ownership" }
            require(seen.size <= Fe8JLayout.PROC_COUNT)
            val script = snapshot.u32(address)
            require(validScript(script)) { "Active process has an invalid ROM script" }
            found += Fe8Process(address, script, snapshot.u8(address + 0x28))
            visit(snapshot.u32(address + 0x18))
            visit(snapshot.u32(address + 0x20))
        }
        repeat(8) { visit(snapshot.u32(Fe8JLayout.PROC_ROOTS + it * 4L)) }
        return found
    }
}

/** One instance belongs to a single ordered analysis worker. decodeRaw does not modify lifetime tracking. */
class Fe8Decoder(private val profile: RomProfile, private val catalog: Fe8RomCatalog) {
    private val lifetimes = UnitLifetimeTracker()

    init { require(profile.id == catalog.profile.id && profile.identity == catalog.profile.identity) }

    fun decode(snapshot: MemorySnapshot): FeBattleSnapshot {
        val raw = decodeRaw(snapshot)
        if (raw.scene != FeScene.MAP && raw.scene != FeScene.PREPARATION) {
            lifetimes.invalidate()
            return raw
        }
        val identities = lifetimes.identify(snapshot.stamp, checkNotNull(raw.chapter), raw.units.map {
            UnitLifetimeTracker.Observation(it.slot, it.identity.characterAddress)
        })
        return raw.copy(units = Collections.unmodifiableList(raw.units.map { it.copy(identity = identities.getValue(it.slot)) }))
    }

    fun decodeRaw(snapshot: MemorySnapshot): FeBattleSnapshot {
        val stamp = snapshot.stamp
        if (stamp.romSha256 != profile.identity.sha256 || stamp.adapterId != profile.id || stamp.adapterVersion != profile.version) {
            return FeBattleSnapshot(stamp, FeScene.INVALID, "快照与当前游戏配置不匹配")
        }
        return try {
            val processes = Fe8Processes.read(snapshot) { it % 4L == 0L && catalog.contains(it, 8) }
            val scripts = Collections.unmodifiableSet(processes.map { it.script }.toSet())
            val map = processes.singleOrNull { it.script == Fe8JLayout.MAP_TASK_SCRIPT }
                ?: return FeBattleSnapshot(stamp, FeScene.UNAVAILABLE, "当前不在已确认的战场场景", activeProcScripts = scripts)
            val width = snapshot.u16(Fe8JLayout.MAP_SIZE)
            val height = snapshot.u16(Fe8JLayout.MAP_SIZE + 2)
            require(width in 1..255 && height in 1..255) { "地图尺寸无效" }
            for (layer in listOf(Fe8JLayout.MAP_UNIT_ROWS, Fe8JLayout.MAP_TERRAIN_ROWS)) {
                val rows = snapshot.u32(layer)
                require(rows % 4L == 0L && rows in 0x02000000L..0x0203FFFFL && snapshot.contains(rows, height * 4)) {
                    "地图行表无效"
                }
                repeat(height) { row ->
                    val cells = snapshot.u32(rows + row * 4L)
                    require(cells in 0x02000000L..0x0203FFFFL && snapshot.contains(cells, width)) { "地图行数据无效" }
                }
            }
            val chapter = snapshot.u8(Fe8JLayout.PLAY_STATE + 0x0E)
            val phase = snapshot.u8(Fe8JLayout.PLAY_STATE + 0x0F)
            val turn = snapshot.u16(Fe8JLayout.PLAY_STATE + 0x10)
            // Opening events legitimately run at turn zero; their Proc/game locks separately forbid edits.
            require(chapter != 255 && phase in setOf(0, 0x40, 0x80)) { "章节或阵营状态无效" }
            val cursorX = snapshot.s16(Fe8JLayout.BM_STATE + 0x14)
            val cursorY = snapshot.s16(Fe8JLayout.BM_STATE + 0x16)
            val cursor = if (cursorX in 0 until width && cursorY in 0 until height) MapPosition(cursorX, cursorY) else null
            val units = ArrayList<FeUnit>()
            fun readUnits(base: Long, count: Int, firstSlot: Int) {
                repeat(count) { index ->
                    val address = base + index * Fe8JLayout.UNIT_SIZE
                    val character = snapshot.u32(address)
                    if (character == 0L) return@repeat
                    val characterId = catalog.characterId(character)
                    val classAddress = snapshot.u32(address + 4)
                    val classId = catalog.classId(classAddress)
                    val slot = snapshot.u8(address + 0x0B)
                    require(slot == firstSlot + index) { "单位所在槽与部署编号不符" }
                    val maxHp = snapshot.s8(address + 0x12)
                    val hp = snapshot.s8(address + 0x13)
                    require(maxHp > 0 && hp in 0..maxHp) { "单位生命值不一致" }
                    units += FeUnit(
                        UnitIdentity(stamp.copy(frame = 0, revision = 0), chapter, slot, 0, character),
                        slot, address, characterId, classId, catalog.recordName(character), catalog.recordName(classAddress),
                        slot and 0xC0, snapshot.s8(address + 0x10), snapshot.s8(address + 0x11),
                        snapshot.s8(address + 8), snapshot.u8(address + 9), maxHp, hp,
                        snapshot.s8(address + 0x14), snapshot.s8(address + 0x15), snapshot.s8(address + 0x16),
                        snapshot.s8(address + 0x17), snapshot.s8(address + 0x18), snapshot.s8(address + 0x19),
                        snapshot.u32(address + 0x0C),
                        Collections.unmodifiableList((0..4).map { catalog.item(snapshot.u16(address + 0x1E + it * 2L), it) }),
                    )
                }
            }
            readUnits(Fe8JLayout.PLAYER_UNITS, 62, 1)
            readUnits(Fe8JLayout.ENEMY_UNITS, 50, 0x81)
            readUnits(Fe8JLayout.NPC_UNITS, 20, 0x41)
            val active = snapshot.u32(Fe8JLayout.ACTIVE_UNIT)
            // gActiveUnit survives cancellation. BM_FLAG_1 marks movement selection; exact Proc state
            // limits this to the verified selector rather than claiming every action/menu mode.
            val player = processes.singleOrNull { it.script == Fe8JLayout.PLAYER_PHASE_SCRIPT }
            val selectingMovement = phase == 0 && snapshot.u8(Fe8JLayout.BM_STATE + 1) == 0 &&
                snapshot.u8(Fe8JLayout.BM_STATE + 4) and 2 != 0 && player != null && player.lock == 0 &&
                snapshot.u32(player.address + 4) == Fe8JLayout.PLAYER_MOVEMENT_CURSOR &&
                snapshot.u32(player.address + 0x0C) == Fe8JLayout.PLAYER_MOVEMENT_CALLBACK
            FeBattleSnapshot(stamp, FeScene.MAP, chapter = chapter, turn = turn, phase = phase, cursor = cursor,
                mapWidth = width, mapHeight = height, units = Collections.unmodifiableList(units),
                selectedSlot = if (selectingMovement) units.firstOrNull { it.address == active }?.slot else null,
                mapProcLock = map.lock, activeProcScripts = scripts)
        } catch (failure: IllegalArgumentException) {
            FeBattleSnapshot(stamp, FeScene.INVALID, "当前状态无法可靠解析：${failure.message ?: "缺失内存或结构无效"}")
        }
    }
}
