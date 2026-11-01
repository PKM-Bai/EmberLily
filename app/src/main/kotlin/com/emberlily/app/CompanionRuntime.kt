package com.emberlily.app

import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.*
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.ImportedRom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class CompanionState(
    val profile: RomProfile? = null,
    val battle: FeBattleSnapshot? = null,
    val pinned: UnitIdentity? = null,
    val message: String = "打开游戏后读取战局",
    val captureMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val latencyMs: Double = 0.0,
    val captured: Long = 0,
    val discarded: Long = 0,
    val frameP95Ms: Double = 0.0,
    val frameOverruns: Long = 0,
) {
    val comparison: FeUnit? get() = battle?.units?.firstOrNull { it.identity == pinned }
}

/** Called by the emulation owner only. Workers consume copies and never touch the core. */
class CompanionRuntime(private val postOwner: (() -> Unit) -> Unit) : AutoCloseable {
    private data class Adapter(val profile: RomProfile, val catalog: Fe8RomCatalog, val decoder: Fe8Decoder)
    private data class Job(val snapshot: MemorySnapshot, val adapter: Adapter?, val started: Long, val captureMs: Double)
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicReference<Job?>(null)
    private val working = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val gate = SnapshotGate()
    private val mutable = MutableStateFlow(CompanionState())
    val state = mutable.asStateFlow()
    private var adapter: Adapter? = null
    private var base: SnapshotStamp? = null
    private var sceneKey: Pair<FeScene, Int?>? = null
    private var lastSnapshot: MemorySnapshot? = null
    private var captureCount = 0L
    private var discarded = 0L
    private val captures = TimingWindow()
    private val decodes = TimingWindow()
    private val latencies = TimingWindow()
    private val frameTimes = TimingWindow()
    private var overruns = 0L

    fun open(rom: ImportedRom, session: GameSession) {
        clear("正在读取适配资料")
        val profile = RomProfiles.identify(rom.sha256)
        adapter = profile?.let {
            val catalog = Fe8RomCatalog.load(it, rom.file.readBytes())
            Adapter(it, catalog, Fe8Decoder(it, catalog))
        }
        base = SnapshotStamp(rom.sha256, UUID.randomUUID().toString(), session.id, 0, 0, 0,
            profile?.id ?: "gba-raw", profile?.version ?: 1)
        gate.reset(base!!)
        mutable.value = CompanionState(profile = profile, message = if (profile == null)
            "此文件尚未适配火纹信息，可继续普通 GBA 游玩" else "等待有效战局")
    }

    fun clear(message: String = "打开游戏后读取战局") {
        base = null
        adapter = null
        gate.clear()
        queued.set(null)
        lastSnapshot = null
        sceneKey = null
        captureCount = 0
        discarded = 0
        captures.clear(); decodes.clear(); latencies.clear(); frameTimes.clear(); overruns = 0
        mutable.value = CompanionState(message = message)
    }

    fun invalidate(message: String, written: Boolean = false) {
        val previous = base ?: return
        base = previous.copy(epoch = previous.epoch + 1, revision = previous.revision + if (written) 1 else 0)
        gate.reset(base!!)
        queued.set(null)
        lastSnapshot = null
        sceneKey = null
        mutable.value = mutable.value.copy(battle = null, pinned = null, message = message)
    }

    fun pin(identity: UnitIdentity?) {
        val found = mutable.value.battle?.units?.any { it.identity == identity } == true
        mutable.value = mutable.value.copy(pinned = identity?.takeIf { found })
    }

    fun accepts(stamp: SnapshotStamp): Boolean = gate.acceptContext(stamp)
    val hasContext: Boolean get() = base != null

    fun recordFrame(workNanos: Long) {
        frameTimes.add(workNanos / 1e6)
        if (workNanos > 16_742_706L) overruns++
    }

    /** Fresh capture is also used by commands; all regions are copied between the same two frames. */
    fun capture(handle: Long, frame: Long): MemorySnapshot {
        val stamp = (base ?: error("当前会话没有快照上下文")).copy(frame = frame)
        val snapshot = MemorySnapshot(stamp, Fe8JLayout.captureRanges.map {
            MemorySegment(it.address, NativeCore.readMemory(handle, it.address, it.length))
        })
        check(gate.advance(stamp)) { "快照上下文已失效" }
        lastSnapshot = snapshot
        return snapshot
    }

    /** Commands use a separate tracker and compare semantic fields again; never call the worker's decoder. */
    fun decodeFresh(snapshot: MemorySnapshot): FeBattleSnapshot {
        val current = adapter ?: error("当前构建尚未适配")
        return Fe8Decoder(current.profile, current.catalog).decode(snapshot)
    }

    fun sample(handle: Long, frame: Long) {
        if (base == null || closed.get()) return
        val start = System.nanoTime()
        val snapshot = capture(handle, frame)
        captureCount++
        if (queued.getAndSet(Job(snapshot, adapter, start, (System.nanoTime() - start) / 1e6)) != null) discarded++
        startWorker()
    }

    private fun startWorker() {
        if (!working.compareAndSet(false, true)) return
        try { worker.execute {
            try {
                while (!closed.get()) {
                    val job = queued.getAndSet(null) ?: break
                    val start = System.nanoTime()
                    val result = runCatching { job.adapter?.decoder?.decode(job.snapshot) }
                    val decodeMs = (System.nanoTime() - start) / 1e6
                    postOwner {
                        if (!gate.acceptExact(job.snapshot.stamp)) return@postOwner
                        val battle = result.getOrNull()
                        if (result.isFailure) {
                            mutable.value = mutable.value.copy(battle = null, pinned = null,
                                message = "战局读取失败：${result.exceptionOrNull()?.message}")
                            return@postOwner
                        }
                        val nextKey = battle?.let { it.scene to it.chapter }
                        if (sceneKey != null && sceneKey != nextKey) {
                            invalidate("场景已变化，重新读取")
                            sceneKey = nextKey
                            // The same immutable bytes can be decoded in the new epoch even
                            // if the player paused since capture; no extra game frame is needed.
                            val replay = MemorySnapshot(base!!.copy(frame = job.snapshot.stamp.frame),
                                job.snapshot.ranges.map { MemorySegment(it.address, job.snapshot.readBytes(it.address, it.length)) })
                            gate.advance(replay.stamp)
                            lastSnapshot = replay
                            queued.set(job.copy(snapshot = replay))
                            startWorker()
                            return@postOwner
                        }
                        sceneKey = nextKey
                        val pin = mutable.value.pinned?.takeIf { id -> battle?.units?.any { it.identity == id } == true }
                        captures.add(job.captureMs); decodes.add(decodeMs); latencies.add((System.nanoTime() - job.started) / 1e6)
                        mutable.value = mutable.value.copy(battle = battle, pinned = pin,
                            message = battle?.reason ?: if (battle == null) "此构建尚未适配火纹信息" else "战局已同步",
                            captureMs = captures.p95(), decodeMs = decodes.p95(), latencyMs = latencies.p95(),
                            captured = captureCount, discarded = discarded, frameP95Ms = frameTimes.p95(), frameOverruns = overruns)
                    }
                }
            } finally {
                working.set(false)
                if (queued.get() != null && !closed.get()) startWorker()
            }
        } } catch (e: RejectedExecutionException) {
            working.set(false)
            if (!closed.get()) throw e
        }
    }

    override fun close() { closed.set(true); clear(); worker.shutdown() }
}

/** Bounded diagnostic window; all access stays on the owner. */
private class TimingWindow {
    private val values = ArrayDeque<Double>()
    fun clear() = values.clear()
    fun add(value: Double) { if (values.size == 120) values.removeFirst(); values.addLast(value) }
    fun p95(): Double = if (values.isEmpty()) 0.0 else values.sorted()[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]
}
