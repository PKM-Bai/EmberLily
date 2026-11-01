package com.emberlily.app.fe

import java.util.Collections

/** Equal byte arrays are read-only transaction guards; all arrays are copied at the boundary. */
class MemoryPatch(val address: Long, before: ByteArray, after: ByteArray) {
    init {
        MemoryRange(address, before.size)
        require(before.size == after.size) { "Patch lengths differ" }
    }
    private val expected = before.copyOf()
    private val replacement = after.copyOf()
    val before: ByteArray get() = expected.copyOf()
    val after: ByteArray get() = replacement.copyOf()
    val length: Int get() = expected.size
}

data class HpEditRequest(
    val observedStamp: SnapshotStamp,
    val identity: UnitIdentity,
    val classId: Int,
    val expectedHp: Int,
    val targetHp: Int,
)

class HpEditPlan(patches: List<MemoryPatch>, val beforeHp: Int, val afterHp: Int, val unitAddress: Long) {
    val patches: List<MemoryPatch> = Collections.unmodifiableList(patches.toList())
}

/**
 * Plans a bounded runtime edit. The host owns experiment permission, latest published identity,
 * pause/checkpoint, atomic compare/write, revision, reread and persistence. A raw decoder creates
 * fresh generation=0 identities, so generation is deliberately checked against the published model
 * by the host, never against decodeRaw. No game function or persistent ROM edit happens here.
 */
object Fe8ResearchCommands {
    fun planHp(request: HpEditRequest, fresh: MemorySnapshot, decoded: FeBattleSnapshot): HpEditPlan {
        val observed = request.observedStamp
        val current = fresh.stamp
        require(observed.sameContext(current) && observed.frame <= current.frame) { "游戏上下文已改变，请重新选择单位" }
        require(decoded.stamp == current) { "解码结果与当前快照不一致" }
        val profile = RomProfiles.identify(current.romSha256)
        require(profile != null && current.adapterId == profile.id && current.adapterVersion == profile.version) { "当前构建没有已核验的 HP 字段" }
        require(request.identity.context == observed.copy(frame = 0, revision = 0)) { "单位引用不属于当前上下文" }
        require(request.identity.chapter == decoded.chapter) { "章节已改变" }
        require(decoded.scene == FeScene.MAP && decoded.phase == 0) { "只允许在玩家回合的空闲战场编辑" }
        val unit = decoded.units.singleOrNull { it.slot == request.identity.slot }
            ?: throw IllegalArgumentException("单位已离开当前部署槽")
        val expectedAddress = when (unit.slot) {
            in 1..0x3E -> Fe8JLayout.PLAYER_UNITS + (unit.slot - 1) * Fe8JLayout.UNIT_SIZE
            in 0x41..0x54 -> Fe8JLayout.NPC_UNITS + (unit.slot - 0x41) * Fe8JLayout.UNIT_SIZE
            in 0x81..0xB2 -> Fe8JLayout.ENEMY_UNITS + (unit.slot - 0x81) * Fe8JLayout.UNIT_SIZE
            else -> throw IllegalArgumentException("未知部署槽")
        }
        require(unit.address == expectedAddress) { "单位地址与部署槽不一致" }
        require(unit.identity.characterAddress == request.identity.characterAddress && unit.classId == request.classId) { "单位或职业已改变" }
        require(fresh.u32(unit.address) == request.identity.characterAddress && fresh.u8(unit.address + 0x0B) == unit.slot &&
            fresh.u32(unit.address + 4) == Fe8JLayout.CLASS_TABLE + request.classId * Fe8JLayout.CLASS_SIZE) { "单位内存标识已改变" }
        require(unit.hp == request.expectedHp && fresh.u8(unit.address + 0x13) == request.expectedHp) { "当前 HP 已改变，请重新读取" }
        require(fresh.u8(unit.address + 0x12) == unit.maxHp && fresh.u32(unit.address + 0x0C) == unit.state &&
            fresh.s8(unit.address + 0x10) == unit.x && fresh.s8(unit.address + 0x11) == unit.y) { "单位状态与快照不一致" }
        val width = decoded.mapWidth ?: throw IllegalArgumentException("地图尺寸缺失")
        val height = decoded.mapHeight ?: throw IllegalArgumentException("地图尺寸缺失")
        require(unit.maxHp in 1..127 && unit.hp in 1..unit.maxHp && unit.state and (0x1L or 0x4L or 0x8L or 0x10L or 0x20L or 0x40L) == 0L &&
            unit.x in 0 until width && unit.y in 0 until height) {
            "死亡、离场、隐藏、救出或移动中的单位不能执行此编辑"
        }
        require(request.targetHp in 1..unit.maxHp && request.targetHp != unit.hp) { "目标 HP 必须在 1 到最大 HP 之间，且与当前值不同" }
        requireStablePlayerIdle(fresh)

        val before = fresh.readBytes(unit.address, Fe8JLayout.UNIT_SIZE)
        val after = before.copyOf().also { it[0x13] = request.targetHp.toByte() }
        fun guard(address: Long, length: Int): MemoryPatch {
            val bytes = fresh.readBytes(address, length)
            return MemoryPatch(address, bytes, bytes)
        }
        return HpEditPlan(listOf(
            MemoryPatch(unit.address, before, after),
            guard(Fe8JLayout.PROC_POOL, Fe8JLayout.PROC_COUNT * Fe8JLayout.PROC_SIZE),
            guard(Fe8JLayout.PROC_ROOTS, 8 * 4),
            guard(Fe8JLayout.BM_STATE, (Fe8JLayout.PLAY_STATE - Fe8JLayout.BM_STATE).toInt() + 0x4C),
            guard(Fe8JLayout.MAP_SIZE, 0x1C),
            guard(Fe8JLayout.ACTIVE_UNIT, 4),
        ), unit.hp, request.targetHp, unit.address)
    }

    private fun requireStablePlayerIdle(fresh: MemorySnapshot) {
        require(fresh.u8(Fe8JLayout.BM_STATE + 1) == 0) { "游戏正在处理事件或动作" }
        require(fresh.u8(Fe8JLayout.BM_STATE + 2) == 0 && fresh.u8(Fe8JLayout.BM_STATE + 4) == 0) { "地图图形或选择状态尚未空闲" }
        val size = checkNotNull(RomProfiles.identify(fresh.stamp.romSha256)).identity.size
        val processes = Fe8Processes.read(fresh) { it % 4L == 0L && it >= 0x08000000 && it <= 0x08000000L + size - 8 }
        val blocked = setOf(0x085B98A0L, 0x085B98D8L, 0x085C3248L) // map event, battle event, map fade
        require(processes.none { it.script in blocked }) { "事件、战斗或地图转场仍在运行" }
        val renderer = processes.singleOrNull { it.script == Fe8JLayout.MAP_TASK_SCRIPT }
        require(renderer != null && renderer.lock == 0) { "地图渲染尚未空闲" }
        val player = processes.singleOrNull { it.script == Fe8JLayout.PLAYER_PHASE_SCRIPT }
        require(player != null && player.lock == 0 && fresh.u32(player.address + 4) == Fe8JLayout.PLAYER_IDLE_CURSOR &&
            fresh.u32(player.address + 0x0C) == Fe8JLayout.PLAYER_IDLE_CALLBACK && fresh.s16(player.address + 0x24) == 0 &&
            fresh.u32(player.address + 0x18) == 0L) { "当前不在已核验的空闲玩家阶段" }
        val parentAddress = fresh.u32(player.address + 0x14)
        val parent = processes.singleOrNull { it.address == parentAddress && it.script == Fe8JLayout.BMAP_MAIN_SCRIPT }
        require(parent != null && parent.lock == 1 && fresh.u32(parent.address + 0x18) == player.address) { "玩家阶段的逻辑父进程不一致" }
    }
}
