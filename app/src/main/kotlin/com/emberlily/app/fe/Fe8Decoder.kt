package com.emberlily.app.fe

import java.util.Collections

internal data class FeProcess(val address: Long, val script: Long, val lock: Int)

/** Walks live roots; stale records in a released pool slot must never establish scene validity. */
internal object FeProcesses {
    fun read(snapshot: MemorySnapshot, layout: FeLayout = FeLayouts.fe8j, validScript: (Long) -> Boolean): List<FeProcess> {
        val found = ArrayList<FeProcess>()
        val seen = HashSet<Long>()
        fun visit(address: Long) {
            if (address == 0L) return
            val offset = address - layout.procPool
            require(offset >= 0 && offset < layout.procCount * layout.procSize && offset % layout.procSize == 0L) {
                "Process pointer is outside the aligned pool"
            }
            require(seen.add(address)) { "Process graph has duplicate or cyclic ownership" }
            require(seen.size <= layout.procCount)
            val script = snapshot.u32(address)
            require(validScript(script)) { "Active process has an invalid ROM script" }
            found += FeProcess(address, script, snapshot.u8(address + 0x28))
            visit(snapshot.u32(address + 0x18))
            visit(snapshot.u32(address + 0x20))
        }
        repeat(8) { visit(snapshot.u32(layout.procRoots + it * 4L)) }
        return found
    }
}

/** One instance belongs to a single ordered analysis worker. decodeRaw does not modify lifetime tracking. */
class FeDecoder(private val profile: RomProfile, private val catalog: FeRomCatalog) {
    private val lifetimes = UnitLifetimeTracker()
    private val layout = profile.layout

    init { require(profile.id == catalog.profile.id && profile.version == catalog.profile.version &&
        profile.identity == catalog.profile.identity && profile.layout == catalog.profile.layout && profile.nameEncoding == catalog.profile.nameEncoding) }

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
            val processes = FeProcesses.read(snapshot, layout) { it % 4L == 0L && catalog.contains(it, 8) }
            val scripts = Collections.unmodifiableSet(processes.map { it.script }.toSet())
            val map = processes.singleOrNull { it.script == layout.mapTaskScript }
            // Preparation can be opened from the world map, where battle row tables are stale.
            // Only the independently verified preparation-trade process establishes this context.
            val preparation = PrepTradeCommands.inspect(snapshot)
            if (map == null && preparation == null) {
                return FeBattleSnapshot(stamp, FeScene.UNAVAILABLE, "当前不在已确认的战场或准备交换场景", activeProcScripts = scripts)
            }
            val width = if (preparation == null) snapshot.u16(layout.mapSize) else 0
            val height = if (preparation == null) snapshot.u16(layout.mapSize + 2) else 0
            if (preparation == null) require(width in 1..255 && height in 1..255) { "地图尺寸无效" }
            if (preparation == null) for (layer in listOf(layout.mapUnitRows, layout.mapTerrainRows)) {
                val rows = snapshot.u32(layer)
                require(rows % 4L == 0L && rows in 0x02000000L..0x0203FFFFL && snapshot.contains(rows, height * 4)) {
                    "地图行表无效"
                }
                repeat(height) { row ->
                    val cells = snapshot.u32(rows + row * 4L)
                    require(cells in 0x02000000L..0x0203FFFFL && snapshot.contains(cells, width)) { "地图行数据无效" }
                }
            }
            val chapter = snapshot.u8(layout.playState + layout.chapterOffset)
            val phase = snapshot.u8(layout.playState + layout.phaseOffset)
            val turn = snapshot.u16(layout.playState + layout.turnOffset)
            // Opening events legitimately run at turn zero; their Proc/game locks separately forbid edits.
            require(chapter != 255 && phase in setOf(0, 0x40, 0x80)) { "章节或阵营状态无效" }
            val cursorX = snapshot.s16(layout.bmState + layout.cursorOffset)
            val cursorY = snapshot.s16(layout.bmState + layout.cursorOffset + 2)
            val cursor = if (cursorX in 0 until width && cursorY in 0 until height) MapPosition(cursorX, cursorY) else null
            val units = ArrayList<FeUnit>()
            fun readUnits(base: Long, count: Int, firstSlot: Int) {
                repeat(count) { index ->
                    val address = base + index * layout.unitSize
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
            layout.units.forEach { readUnits(it.address, it.count, it.firstSlot) }
            val active = snapshot.u32(layout.activeUnit)
            // gActiveUnit survives cancellation. BM_FLAG_1 marks movement selection; exact Proc state
            // limits this to the verified selector rather than claiming every action/menu mode.
            val player = processes.singleOrNull { it.script == layout.playerPhaseScript }
            val movement = layout.movement
            val selectingMovement = preparation == null && movement != null && phase == 0 && snapshot.u8(layout.bmState + 1) == 0 &&
                snapshot.u8(layout.bmState + 4) and 2 != 0 && player != null && player.lock == 0 &&
                snapshot.u32(player.address + 4) == movement.cursor &&
                snapshot.u32(player.address + 0x0C) == movement.callback
            val convoy = layout.convoyAddress?.let { address ->
                require(layout.convoyCapacity in 1..100 && snapshot.contains(address, layout.convoyCapacity * 2)) { "仓库内存无效" }
                FeConvoySnapshot(layout.convoyCapacity, Collections.unmodifiableList((0 until layout.convoyCapacity).map {
                    catalog.item(snapshot.u16(address + it * 2L), it)
                }))
            }
            FeBattleSnapshot(stamp, if (preparation != null) FeScene.PREPARATION else FeScene.MAP,
                chapter = chapter, turn = turn, phase = phase, cursor = cursor,
                mapWidth = width.takeIf { preparation == null }, mapHeight = height.takeIf { preparation == null },
                units = Collections.unmodifiableList(units),
                selectedSlot = if (selectingMovement) units.firstOrNull { it.address == active }?.slot else null,
                mapProcLock = map?.lock, activeProcScripts = scripts, convoy = convoy)
        } catch (failure: IllegalArgumentException) {
            FeBattleSnapshot(stamp, FeScene.INVALID, "当前状态无法可靠解析：${failure.message ?: "缺失内存或结构无效"}")
        }
    }
}

/** Source compatibility for M1 callers. All engine-specific data now comes from profile.layout. */
typealias Fe8Decoder = FeDecoder

internal object Fe8Processes {
    fun read(snapshot: MemorySnapshot, validScript: (Long) -> Boolean): List<FeProcess> =
        FeProcesses.read(snapshot, FeLayouts.fe8j, validScript)
}
