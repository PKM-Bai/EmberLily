package com.emberlily.app.fe

import java.util.Collections

/** Exact native-menu participants; list positions are never UI sort positions. */
data class PrepTradeUnit(val address: Long, val slot: Int, val characterAddress: Long,
    val classAddress: Long, val state: Long, val items: List<Int>)

data class PrepTradeContext(val stamp: SnapshotStamp, val procAddress: Long, val parentAddress: Long,
    val chapter: Int, val left: PrepTradeUnit, val right: PrepTradeUnit,
    val cursorSlot: Int, val selectedSlot: Int, val helpSlot: Int, val idle: Boolean) {
    val ready: Boolean get() = idle && selectedSlot == 255 && helpSlot == 255
    fun request(leftSlot: Int, rightSlot: Int) = PrepTradeRequest(this, leftSlot, rightSlot)
}

data class PrepTradeRequest(val observed: PrepTradeContext, val leftSlot: Int, val rightSlot: Int)
data class PrepTradePlan(val request: PrepTradeRequest, val source: Int, val target: Int,
    val afterLeft: List<Int>, val afterRight: List<Int>)

/** A narrow R04 native preparation-trade contract, not a general inventory RAM writer. */
object PrepTradeCommands {
    const val SCRIPT = 0x08A94D60L
    const val IDLE_CURSOR = 0x08A94D88L
    const val IDLE_CALLBACK = 0x0809DDF9L
    const val PARENT_SCRIPT = 0x08A946E8L

    /** Also reports selection/help states for read-only UI; ready is deliberately stricter. */
    fun inspect(snapshot: MemorySnapshot): PrepTradeContext? = runCatching {
        if (snapshot.stamp.romSha256 != RomProfiles.FE8J_VOICE_SHA256) return null
        val layout = FeLayouts.fe8j
        val processes = FeProcesses.read(snapshot, layout) { it % 4L == 0L && it in 0x08000000L..0x0911900CL }
        val proc = processes.singleOrNull { it.script == SCRIPT } ?: return null
        val parentAddress = snapshot.u32(proc.address + 0x14)
        val parent = processes.singleOrNull { it.address == parentAddress } ?: return null
        fun unit(address: Long): PrepTradeUnit {
            val slot = snapshot.u8(address + 0x0B)
            require(slot in 1..62 && layout.unitAddress(slot) == address)
            val character = snapshot.u32(address)
            val clazz = snapshot.u32(address + 4)
            require(character >= layout.characterTable && (character - layout.characterTable) % layout.characterSize == 0L &&
                character < layout.classTable && clazz >= layout.classTable &&
                (clazz - layout.classTable) % layout.classSize == 0L && clazz < layout.itemTable)
            val items = (0..4).map { snapshot.u16(address + 0x1E + it * 2L) }
            require(items.all { it == 0 || it and 255 != 0 })
            require(items.dropWhile { it != 0 }.all { it == 0 }) { "物品空槽尚未压紧" }
            return PrepTradeUnit(address, slot, character, clazz, snapshot.u32(address + 0x0C),
                Collections.unmodifiableList(items))
        }
        val left = unit(snapshot.u32(proc.address + 0x2C))
        val right = unit(snapshot.u32(proc.address + 0x30))
        require(left.address != right.address)
        val cursor = snapshot.u32(proc.address + 0x34).toInt()
        val selected = snapshot.u32(proc.address + 0x38).toInt()
        val help = snapshot.u32(proc.address + 0x3C).toInt()
        fun slotValid(slot: Int) = slot in 0..4 || slot in 8..12
        require(slotValid(cursor) && (selected == 255 || slotValid(selected)) && (help == 255 || slotValid(help)))
        PrepTradeContext(snapshot.stamp, proc.address, parentAddress,
            snapshot.u8(layout.playState + layout.chapterOffset), left, right, cursor, selected, help,
            proc.lock == 0 && parent.script == PARENT_SCRIPT && parent.lock == 1 &&
                snapshot.u8(layout.bmState + 4) and 0x40 == 0 &&
                snapshot.u16(proc.address + 0x24) == 0 && snapshot.u32(proc.address + 4) == IDLE_CURSOR &&
                snapshot.u32(proc.address + 0x0C) == IDLE_CALLBACK)
    }.getOrNull()

    fun plan(request: PrepTradeRequest, fresh: PrepTradeContext): PrepTradePlan {
        require(request.observed.stamp.sameContext(fresh.stamp)) { "交换请求已失效，请重新选择" }
        require(sameParticipants(request.observed, fresh) && request.observed.left.items == fresh.left.items &&
            request.observed.right.items == fresh.right.items) { "角色或物品已变化，请重新选择" }
        require(fresh.ready) { "请先在上屏准备交换画面取消选择或帮助框" }
        require(request.leftSlot in 0..4 && request.rightSlot in 0..4) { "物品槽无效" }
        val a = fresh.left.items[request.leftSlot]
        val b = fresh.right.items[request.rightSlot]
        require(a != b) { "请选择两个不同的物品，或一个物品与空位" }
        require(a != 0 || request.leftSlot == fresh.left.items.count { it != 0 }) { "请选择左侧第一个空位" }
        require(b != 0 || request.rightSlot == fresh.right.items.count { it != 0 }) { "请选择右侧第一个空位" }
        val left = fresh.left.items.toMutableList().apply { this[request.leftSlot] = b }
        val right = fresh.right.items.toMutableList().apply { this[request.rightSlot] = a }
        fun packed(items: List<Int>) = items.filter { it != 0 }.let { it + List(5 - it.size) { 0 } }
        return PrepTradePlan(request, if (a != 0) request.leftSlot else 8 + request.rightSlot,
            if (a != 0) 8 + request.rightSlot else request.leftSlot, packed(left), packed(right))
    }

    internal fun sameParticipants(a: PrepTradeContext, b: PrepTradeContext): Boolean =
        a.stamp.sameContext(b.stamp) && a.procAddress == b.procAddress && a.parentAddress == b.parentAddress && a.chapter == b.chapter &&
            a.left.copy(items = emptyList()) == b.left.copy(items = emptyList()) &&
            a.right.copy(items = emptyList()) == b.right.copy(items = emptyList())
}

/** Executes only native key presses. The core owner supplies atomic frame/capture operations. */
class PrepTradeRunner(private val capture: () -> MemorySnapshot, private val advance: (Int) -> Unit,
    private val interrupted: () -> Boolean) {
    private var advanced = 0
    fun execute(request: PrepTradeRequest): PrepTradeContext {
        advanced = 0
        fun observe() = checkNotNull(PrepTradeCommands.inspect(capture())) { "已离开可识别的准备交换画面" }
        var current = observe()
        val plan = PrepTradeCommands.plan(request, current)
        val initial = current
        fun guard(value: PrepTradeContext) {
            check(PrepTradeCommands.sameParticipants(initial, value) && value.idle && value.helpSlot == 255) {
                "交换场景或角色已变化，已停止自动操作"
            }
        }
        fun step(key: Int): PrepTradeContext {
            check(!interrupted()) { "交换已中断；请检查上屏当前选择" }
            check(++advanced <= 160) { "交换响应超时，已停止自动操作" }
            advance(key)
            return observe().also(::guard)
        }
        fun matches(value: PrepTradeContext, cursor: Int, selected: Int, left: List<Int>, right: List<Int>) =
            value.cursorSlot == cursor && value.selectedSlot == selected && value.left.items == left && value.right.items == right
        fun pulse(key: Int, cursor: Int, selected: Int, left: List<Int> = initial.left.items, right: List<Int> = initial.right.items) {
            val before = current
            var changed = false
            repeat(4) {
                if (!changed) {
                    current = step(key)
                    changed = matches(current, cursor, selected, left, right)
                    check(changed || matches(current, before.cursorSlot, before.selectedSlot, before.left.items, before.right.items)) {
                        "游戏反馈与交换预期不一致，已停止自动操作"
                    }
                }
            }
            check(changed) { "游戏未响应交换按键，已停止自动操作" }
            // Release before another press. Success is already committed if the final A applied;
            // an interruption after it must not turn a verified swap into an alleged rollback.
            repeat(2) {
                if (!interrupted()) {
                    current = step(0)
                    check(matches(current, cursor, selected, left, right)) { "交换后的游戏状态发生变化" }
                }
            }
        }
        guard(current)
        repeat(2) {
            current = step(0)
            check(matches(current, initial.cursorSlot, 255, initial.left.items, initial.right.items)) { "交换开始前选择已变化" }
        }
        fun navigate(target: Int) {
            repeat(12) {
                if (current.cursorSlot != target) {
                    val col = current.cursorSlot and 8
                    val row = current.cursorSlot and 7
                    val targetCol = target and 8
                    val key: Int
                    val next: Int
                    if (col != targetCol) {
                        val items = if (targetCol == 0) initial.left.items else initial.right.items
                        var count = items.count { it != 0 }
                        if (current.selectedSlot != 255 && targetCol != (current.selectedSlot and 8)) count = minOf(5, count + 1)
                        check(count > 0) { "目标角色没有可选择的物品" }
                        key = if (targetCol == 0) 32 else 16
                        next = targetCol + minOf(row, count - 1)
                    } else {
                        key = if (row < (target and 7)) 128 else 64
                        next = current.cursorSlot + if (key == 128) 1 else -1
                    }
                    pulse(key, next, current.selectedSlot)
                }
            }
            check(current.cursorSlot == target) { "无法定位目标物品槽" }
        }
        navigate(plan.source)
        val otherItems = if (plan.source and 8 == 0) initial.right.items else initial.left.items
        val otherCount = otherItems.count { it != 0 }
        val selectedCursor = ((plan.source + 8) and 8) + if (otherCount < 5) otherCount else plan.source and 7
        pulse(1, selectedCursor, plan.source)
        navigate(plan.target)
        val sourceCountAfter = (if (plan.source and 8 == 0) plan.afterLeft else plan.afterRight).count { it != 0 }
        val cursorAfter = if (sourceCountAfter == 0) (plan.source + 8) and 8
            else (plan.source and 8) + minOf(plan.source and 7, sourceCountAfter - 1)
        pulse(1, cursorAfter, 255, plan.afterLeft, plan.afterRight)
        check(matches(current, cursorAfter, 255, plan.afterLeft, plan.afterRight)) { "交换结果未能确认" }
        return current
    }
}
