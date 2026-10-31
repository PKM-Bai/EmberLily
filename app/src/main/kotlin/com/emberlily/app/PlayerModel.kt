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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
)

/** All core and storage operations are serialized with frame execution. */
class PlayerModel(application: Application) : AndroidViewModel(application) {
    val input = GameInput()
    val frames = FrameBuffer()
    private val executor = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val pending = AtomicBoolean(false)
    private val foreground = AtomicBoolean(false)
    private val prefs = application.getSharedPreferences("player", 0)
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
            if (NativeCore.copyFrame(core, pixels)) frames.publish(pixels)
            val count = NativeCore.drainAudio(core, samples)
            if (count > 0) audio?.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            playing = false
            input.releaseAll()
            mutable.value = mutable.value.copy(running = false, error = "运行中断：${e.message}")
        }
    }

    private fun command(action: () -> Unit) {
        if (!pending.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(busy = true, error = null)
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
        playing = false
        input.releaseAll()
        saveActive()
        closeCore()
        mutable.value = mutable.value.copy(loaded = false, running = false)
        val next = NativeCore.open(targetRom.file.absolutePath, target.saveFile.absolutePath)
        check(next != 0L) { "模拟器无法打开文件，请核对 ROM" }
        core = next
        rom = targetRom
        session = target
        try {
            check(!target.stateFile.isFile || NativeCore.loadState(core, target.stateFile.absolutePath)) {
                "续玩状态无法读取，原文件已保留；请核对核心版本和会话文件"
            }
        } catch (e: Exception) { invalidateCore(); throw e }
        frames.clear()
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
            if (audio == null) "游戏已打开，当前设备音频不可用" else "游戏已打开")
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
        frames.clear()
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
        val oldAudio = audio; audio = null
        runCatching { oldAudio?.pause(); oldAudio?.flush() }
        runCatching { oldAudio?.release() }
        val handle = core
        core = 0L
        mutable.value = mutable.value.copy(loaded = false, running = false)
        if (handle != 0L) NativeCore.close(handle)
    }

    private fun invalidateCore() {
        playing = false
        input.releaseAll()
        frames.clear()
        // Do not save an uncertain state over the last good resume or manual checkpoint.
        runCatching { closeCore() }
    }

    override fun onCleared() {
        foreground.set(false)
        executor.execute { runCatching { saveActive() }; closeCore() }
        executor.shutdown()
    }
}
