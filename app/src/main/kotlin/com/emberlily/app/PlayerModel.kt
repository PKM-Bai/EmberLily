package com.emberlily.app

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import com.emberlily.app.emulation.FrameBuffer
import com.emberlily.app.emulation.GameInput
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.SnapshotCodec
import com.emberlily.app.fe.UnitIdentity
import com.emberlily.app.fe.HpEditRequest
import com.emberlily.app.fe.FeResearchCommands
import com.emberlily.app.fe.FeCapability
import com.emberlily.app.fe.RomProfiles
import com.emberlily.app.fe.FeScene
import com.emberlily.app.fe.PrepTradeContext
import com.emberlily.app.fe.PrepTradeRequest
import com.emberlily.app.fe.PrepTradeCommands
import com.emberlily.app.fe.PrepTradeRunner
import com.emberlily.app.graphics.Fe8WideRenderer
import com.emberlily.app.graphics.GbaVideoSnapshot
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.ImportedRom
import com.emberlily.app.storage.RomFiles
import com.emberlily.app.storage.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PlayerState(
    val name: String = "尚未打开游戏",
    val loaded: Boolean = false,
    val running: Boolean = false,
    val busy: Boolean = false,
    val experiment: Boolean = false,
    val branchLabel: String = "主进度",
    val hasState: Boolean = false,
    val hasRecent: Boolean = false,
    val message: String = "选择本地 .gba 文件开始",
    val error: String? = null,
    val hasResearchRecovery: Boolean = false,
    val prepTrade: PrepTradeContext? = null,
    val inventoryMessage: String? = null,
)

data class GameDisplayState(val expandedMap: Boolean = true, val wideActive: Boolean = false, val supportsExpansion: Boolean = false,
    val width: Int = 240, val height: Int = 160)

/** All core and storage operations are serialized with frame execution. */
class PlayerModel(application: Application) : AndroidViewModel(application) {
    val input = GameInput()
    val frames = FrameBuffer()
    private val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val exports = Executors.newSingleThreadExecutor()
    private val exporting = AtomicBoolean(false)
    private val companionRuntime = CompanionRuntime { work ->
        try { executor.execute(work) } catch (e: RejectedExecutionException) {
            if (!executor.isShutdown) throw e
        }
    }
    val companion = companionRuntime.state
    private var frameNumber = 0L
    private val pending = AtomicBoolean(false)
    private val tradeCancellation = AtomicLong(0)
    private val foreground = AtomicBoolean(false)
    private val prefs = application.getSharedPreferences("player", 0)
    private val displayMutable = MutableStateFlow(GameDisplayState(expandedMap = prefs.getBoolean("expandedMap", true)))
    val gameDisplay = displayMutable.asStateFlow()
    private val wideRenderer = Fe8WideRenderer()
    private val romDirectory = File(application.filesDir, "roms")
    private val sessions = SessionStore(File(application.filesDir, "sessions"))
    private val mutable = MutableStateFlow(PlayerState(hasRecent = prefs.contains("rom")))
    val state = mutable.asStateFlow()
    private var core = 0L
    private var rom: ImportedRom? = null
    private var session: GameSession? = null
    private var playing = false
    private var audio: AudioTrack? = null
    private val pixels = IntArray(240 * 160)
    private val samples = ShortArray(4096)

    init {
        // Native GBA frame period, independent of companion screen refresh rate.
        executor.scheduleWithFixedDelay({ frame() }, 0, 1, TimeUnit.MILLISECONDS)
    }

    private var nextFrame = 0L
    private fun frame() {
        if (core == 0L || !playing || !foreground.get()) return
        val now = System.nanoTime()
        if (now < nextFrame) return
        nextFrame = maxOf(nextFrame + 16_742_706L, now - 16_742_706L)
        try {
            NativeCore.runFrame(core, input.keys())
            frameNumber++
            if (NativeCore.copyFrame(core, pixels)) publishGameFrame()
            val count = NativeCore.drainAudio(core, samples)
            if (count > 0) audio?.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
            if (frameNumber % 4L == 0L) sampleCompanion()
            companionRuntime.recordFrame(System.nanoTime() - now)
        } catch (e: Exception) {
            companionRuntime.clear("游戏已中断，请恢复进度后重新读取")
            playing = false
            input.releaseAll()
            mutable.value = mutable.value.copy(running = false, error = "运行中断：${e.message}")
        }
    }

    private fun publishGameFrame() {
        val wide = if (displayMutable.value.expandedMap && rom?.sha256 == RomProfiles.FE8J_VOICE_SHA256 &&
            companionRuntime.hasContext && companion.value.battle?.scene == FeScene.MAP) runCatching {
            val ram = companionRuntime.capture(core, frameNumber, publishContext = false)
            val video = GbaVideoSnapshot(NativeCore.readMemory(core, 0x06000000, 0x18000),
                NativeCore.readMemory(core, 0x05000000, 0x400), NativeCore.readMemory(core, 0x07000000, 0x400),
                NativeCore.readVideoRegisters(core))
            wideRenderer.render(ram, video, pixels, frames.viewportWidth, frames.viewportHeight)
        }.getOrNull() else { wideRenderer.reset(); null }
        if (wide == null) frames.publish(pixels) else frames.publish(wide.pixels, wide.width, wide.height)
        displayMutable.value = displayMutable.value.copy(wideActive = wide != null,
            width = wide?.width ?: 240, height = wide?.height ?: 160)
    }

    fun setExpandedMap(enabled: Boolean) = command {
        wideRenderer.reset()
        displayMutable.value = displayMutable.value.copy(expandedMap = enabled, wideActive = false, width = 240, height = 160)
        prefs.edit().putBoolean("expandedMap", enabled).apply()
        if (core != 0L && NativeCore.copyFrame(core, pixels)) publishGameFrame()
    }

    private fun clearGameFrame() {
        wideRenderer.reset()
        displayMutable.value = displayMutable.value.copy(wideActive = false, width = 240, height = 160)
        frames.clear()
    }

    private fun command(action: () -> Unit) {
        if (!pending.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(busy = true, error = null, inventoryMessage = null)
        executor.execute {
            try { action() }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "操作失败，请重试") }
            finally {
                mutable.value = mutable.value.copy(busy = false, running = playing && foreground.get())
                pending.set(false)
            }
        }
    }

    fun importRom(uri: Uri) = command {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "GBA 游戏"
        val imported = resolver.openInputStream(uri)?.use { RomFiles.import(it, name, romDirectory) }
            ?: error("无法读取所选文件，请重新选择")
        open(imported, sessions.main(imported.sha256))
    }

    fun resumeRecent() = command {
        val sha = prefs.getString("rom", null) ?: error("尚无最近游戏")
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "最近游戏记录无效，请重新导入" }
        val file = File(romDirectory, "$sha.gba")
        check(file.isFile) { "最近游戏文件不存在，请重新导入" }
        val branchId = prefs.getString("session", "main")
        val branch = sessions.list(sha).firstOrNull { it.id == branchId } ?: sessions.main(sha)
        open(ImportedRom(sha, prefs.getString("name", "GBA 游戏")!!, file), branch)
    }

    private fun open(targetRom: ImportedRom, target: GameSession) {
        companionRuntime.clear("正在切换游戏会话")
        playing = false
        input.releaseAll()
        saveActive()
        closeCore()
        mutable.value = mutable.value.copy(loaded = false, running = false)
        val next = NativeCore.open(targetRom.file.absolutePath, target.saveFile.absolutePath)
        check(next != 0L) { "模拟器无法打开文件，请核对 ROM" }
        core = next
        rom = targetRom
        displayMutable.value = displayMutable.value.copy(supportsExpansion = targetRom.sha256 == RomProfiles.FE8J_VOICE_SHA256)
        session = target
        try {
            check(ResearchEditor.recoverPending(core, target) || !target.stateFile.isFile || NativeCore.loadState(core, target.stateFile.absolutePath)) {
                "续玩状态无法读取，原文件已保留；请核对核心版本和会话文件"
            }
        } catch (e: Exception) { invalidateCore(); throw e }
        clearGameFrame()
        frameNumber = 0L
        runCatching { companionRuntime.open(targetRom, target) }.onFailure {
            companionRuntime.clear("适配资料无法读取：${it.message}")
        }
        sampleCompanion()
        audio = runCatching {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(32768).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            AudioTrack.Builder().setAudioFormat(format)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(32768, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT), 8192)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        }.getOrNull()
        prefs.edit().putString("rom", targetRom.sha256).putString("name", targetRom.name)
            .putString("session", target.id).apply()
        nextFrame = System.nanoTime()
        playing = true
        mutable.value = PlayerState(targetRom.name, true, foreground.get(), true, target.id != "main",
            if (target.id == "main") "主进度" else target.label, target.checkpointFile.isFile, true,
            if (audio == null) "游戏已打开，当前设备音频不可用" else "游戏已打开",
            hasResearchRecovery = target.id != "main" && ResearchEditor.checkpoint(target).isFile)
    }

    private fun saveActive(manualCheckpoint: Boolean = false) {
        if (core == 0L) {
            check(!manualCheckpoint) { "请先打开游戏" }
            return
        }
        val target = session ?: return
        val destination = if (manualCheckpoint) target.checkpointFile else target.stateFile
        val temporary = File(target.directory, if (manualCheckpoint) "checkpoint.pending" else "resume.pending")
        try {
            check(NativeCore.saveState(core, temporary.absolutePath)) {
                if (manualCheckpoint) "保存恢复点失败，原恢复点已保留" else "保存续玩状态失败，原文件已保留"
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
            if (manualCheckpoint) mutable.value = mutable.value.copy(hasState = true)
        } finally { temporary.delete() }
    }

    fun togglePause() = command {
        check(core != 0L)
        playing = !playing
        input.releaseAll()
        nextFrame = System.nanoTime()
        if (!playing) { audio?.pause(); audio?.flush() } else audio?.play()
        mutable.value = mutable.value.copy(message = if (playing) "已继续" else "已暂停")
    }

    fun save() = command {
        saveActive(manualCheckpoint = true)
        // A manual save is also the latest known continuation point. Both writes
        // run between the same two frames; later autosaves only replace resume.
        saveActive()
        mutable.value = mutable.value.copy(message = "已保存当前分支的恢复点")
    }

    fun restore() = command {
        val target = session ?: error("请先打开游戏")
        check(core != 0L && target.checkpointFile.isFile) { "当前分支还没有恢复点" }
        input.releaseAll()
        val restored = try { NativeCore.loadState(core, target.checkpointFile.absolutePath) }
            catch (e: Exception) { invalidateCore(); throw e }
        check(restored) { "恢复失败，当前状态与原文件已保留" }
        if (companionRuntime.hasContext) companionRuntime.invalidate("已读档，重新读取战局")
        else companionRuntime.open(checkNotNull(rom), target)
        sampleCompanion()
        clearGameFrame()
        audio?.pause(); audio?.flush(); if (playing) audio?.play()
        nextFrame = System.nanoTime()
        mutable.value = mutable.value.copy(message = if (playing) "已恢复当前分支" else "已恢复当前分支，继续运行后刷新画面")
    }

    fun fork() = command {
        val source = session ?: error("请先打开游戏")
        val game = rom ?: error("请先打开游戏")
        saveActive()
        val branch = sessions.fork(source, "实验 ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
        open(game, branch)
        mutable.value = mutable.value.copy(message = "已进入独立实验分支，主进度保留")
    }

    fun returnToMain() = command {
        val game = rom ?: error("请先打开游戏")
        open(game, sessions.main(game.sha256))
        mutable.value = mutable.value.copy(message = "已返回主进度，实验分支保留")
    }

    private fun sampleCompanion() {
        runCatching {
            val snapshot = companionRuntime.sample(core, frameNumber)
            mutable.value = mutable.value.copy(prepTrade = snapshot?.let(PrepTradeCommands::inspect))
        }.onFailure {
            mutable.value = mutable.value.copy(prepTrade = null)
            companionRuntime.clear("战局捕获失败：${it.message}")
        }
    }

    fun cancelTrade() { tradeCancellation.incrementAndGet() }

    fun tradeItems(request: PrepTradeRequest) {
        val cancellation = tradeCancellation.get()
        command {
            fun report(message: String) {
                mutable.value = mutable.value.copy(message = message, inventoryMessage = message)
            }
            check(core != 0L && foreground.get()) { "请先回到当前游戏" }
            check(companionRuntime.accepts(request.observed.stamp)) { "交换请求已失效，请重新选择" }
            val inputRevision = input.idleRevision() ?: error("请先松开实体按键或触摸手柄")
            val wasPlaying = playing
            playing = false
            audio?.pause(); audio?.flush()
            report("正在通过游戏准备菜单交换物品")
            var verified = false
            try {
                PrepTradeRunner(
                    capture = { companionRuntime.capture(core, frameNumber, publishContext = false) },
                    advance = { keys ->
                        NativeCore.runFrame(core, keys)
                        frameNumber++
                        NativeCore.drainAudio(core, samples)
                    },
                    interrupted = { tradeCancellation.get() != cancellation || !foreground.get() || input.keys() != 0 || input.revision() != inputRevision },
                ).execute(request)
                verified = true
                NativeCore.releaseKeys(core)
                companionRuntime.invalidate("物品交换已完成，重新读取", written = true)
                report("物品已交换，正在保存当前分支续玩状态")
                try { saveActive() } catch (e: Exception) { error("物品已交换，但续玩状态保存失败：${e.message}") }
                report("已通过游戏交换物品，并保存当前分支续玩状态")
            } catch (e: Exception) {
                report(if (verified) "物品已交换；续玩保存未完成，请检查错误后重新保存"
                    else "交换未完成确认；自动操作已停止，请检查上屏物品与菜单选择")
                throw e
            } finally {
                // Native menu actions may have partially advanced before cancellation; never claim rollback.
                try { NativeCore.releaseKeys(core) } catch (e: Exception) { invalidateCore(); throw e }
                playing = wasPlaying
                sampleCompanion()
                if (NativeCore.copyFrame(core, pixels)) publishGameFrame()
                nextFrame = System.nanoTime()
                if (playing && foreground.get()) audio?.play()
            }
        }
    }

    fun pinUnit(identity: UnitIdentity?) { executor.execute { companionRuntime.pin(identity) } }

    fun editHp(request: HpEditRequest) = command {
        val target = session ?: error("请先打开游戏")
        check(target.id != "main") { "请先创建独立实验分支" }
        check(companion.value.profile?.supports(FeCapability.RESEARCH_EDIT) == true) { "当前构建的研究写入尚未通过验证" }
        check(companionRuntime.accepts(request.observedStamp) && companion.value.battle?.units?.any {
            it.identity == request.identity
        } == true) { "所选单位已失效，请重新选择" }
        val snapshot = companionRuntime.capture(core, frameNumber)
        val plan = FeResearchCommands.planHp(request, snapshot, companionRuntime.decodeFresh(snapshot))
        playing = false
        input.releaseAll()
        audio?.pause(); audio?.flush()
        try { ResearchEditor.editHp(core, target, snapshot, plan) }
        catch (e: Exception) {
            // The storage layer attempted rollback; close without autosaving any uncertain live state.
            invalidateCore()
            throw e
        }
        companionRuntime.invalidate("研究编辑已完成，重新读取", written = true)
        sampleCompanion()
        mutable.value = mutable.value.copy(message = "实验 HP：${plan.beforeHp} → ${plan.afterHp}；已暂停，可恢复编辑前状态",
            hasResearchRecovery = true)
    }

    fun restoreResearch() = command {
        val target = session ?: error("请先打开游戏")
        check(core != 0L && target.id != "main" && ResearchEditor.checkpoint(target).isFile) { "当前实验没有编辑前恢复点" }
        playing = false
        input.releaseAll()
        audio?.pause(); audio?.flush()
        try { ResearchEditor.restore(core, target) } catch (e: Exception) { invalidateCore(); throw e }
        if (companionRuntime.hasContext) companionRuntime.invalidate("已恢复编辑前状态")
        else companionRuntime.open(checkNotNull(rom), target)
        clearGameFrame()
        sampleCompanion()
        mutable.value = mutable.value.copy(message = "已恢复编辑前状态，游戏保持暂停")
    }

    fun exportSnapshot(uri: Uri) = command {
        check(core != 0L) { "请先打开游戏" }
        val capture = companionRuntime.capture(core, frameNumber)
        check(exporting.compareAndSet(false, true)) { "上一个快照仍在导出，请稍候" }
        mutable.value = mutable.value.copy(message = "正在导出 RAM 快照")
        exports.execute {
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(SnapshotCodec.encode(capture))
                } ?: error("无法写入所选位置，请重新选择")
            }
            exporting.set(false)
            try { executor.execute {
                if (companionRuntime.accepts(capture.stamp)) {
                    mutable.value = mutable.value.copy(message = if (result.isSuccess) "已导出所选分支的 RAM 快照（不含 ROM）"
                        else "快照导出失败，请重新选择保存位置", error = mutable.value.error ?: result.exceptionOrNull()?.message)
                }
            } } catch (e: RejectedExecutionException) { if (!executor.isShutdown) throw e }
        }
    }

    fun foreground(active: Boolean) {
        foreground.set(active)
        if (!active) input.releaseAll()
        executor.execute {
            if (!active) {
                runCatching { saveActive() }.onFailure { mutable.value = mutable.value.copy(error = it.message) }
                audio?.pause(); audio?.flush()
            } else { nextFrame = System.nanoTime(); if (playing) audio?.play() }
            mutable.value = mutable.value.copy(running = playing && active)
        }
    }

    private fun closeCore() {
        mutable.value = mutable.value.copy(prepTrade = null)
        wideRenderer.reset()
        displayMutable.value = displayMutable.value.copy(wideActive = false, supportsExpansion = false, width = 240, height = 160)
        companionRuntime.clear()
        val oldAudio = audio; audio = null
        runCatching { oldAudio?.pause(); oldAudio?.flush() }
        runCatching { oldAudio?.release() }
        val handle = core
        core = 0L
        mutable.value = mutable.value.copy(loaded = false, running = false, prepTrade = null)
        if (handle != 0L) NativeCore.close(handle)
    }

    private fun invalidateCore() {
        playing = false
        input.releaseAll()
        clearGameFrame()
        // Do not save an uncertain state over the last good resume or manual checkpoint.
        runCatching { closeCore() }
    }

    override fun onCleared() {
        foreground.set(false)
        executor.execute { try { runCatching { saveActive() }; closeCore() } finally { companionRuntime.close() } }
        executor.shutdown()
        exports.shutdown()
    }
}
