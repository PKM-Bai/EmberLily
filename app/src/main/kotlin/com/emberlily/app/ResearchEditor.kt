package com.emberlily.app

import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.HpEditPlan
import com.emberlily.app.fe.MemorySnapshot
import com.emberlily.app.storage.GameSession
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Runs only between frames on the core owner. Recovery is separate from the player's manual checkpoint. */
object ResearchEditor {
    fun checkpoint(session: GameSession) = File(session.directory, "research-before.state")
    private fun candidate(session: GameSession) = File(session.directory, "research-candidate.state")
    private fun marker(session: GameSession) = File(session.directory, "research-incomplete")

    /** A failed persistence/rollback must never silently reopen an edited continuation. */
    fun recoverPending(handle: Long, session: GameSession, recordRecovery: Boolean = true): Boolean {
        if (!marker(session).exists()) return false
        require(session.id != "main") { "主进度存在异常研究标记，请保留文件并检查" }
        val source = candidate(session).takeIf { it.isFile } ?: checkpoint(session)
        check(source.isFile && NativeCore.loadState(handle, source.absolutePath)) { "未完成研究的恢复点无法读取，请保留实验文件" }
        saveState(handle, session.stateFile)
        if (recordRecovery) runCatching {
            File(session.directory, "research.jsonl").appendText(JSONObject().put("kind", "recover_incomplete_edit")
                .put("branch", session.id).put("timeMillis", System.currentTimeMillis()).toString() + "\n")
        }
        check(marker(session).delete()) { "未完成研究的标记无法清除" }
        candidate(session).delete()
        return true
    }

    fun editHp(handle: Long, session: GameSession, snapshot: MemorySnapshot, plan: HpEditPlan) {
        require(session.id != "main" && session.id == snapshot.stamp.branchId && session.romSha256 == snapshot.stamp.romSha256) {
            "研究编辑只允许作用于当前独立实验分支"
        }
        check(!marker(session).exists()) { "请先恢复上次未完成的研究编辑" }
        saveState(handle, candidate(session))
        check(marker(session).createNewFile()) { "无法建立研究恢复标记" }
        val patches = plan.patches
        if (!NativeCore.compareAndWriteMemory(handle, patches.map { it.address }.toLongArray(),
            patches.map { it.before }.toTypedArray(), patches.map { it.after }.toTypedArray())) {
            check(marker(session).delete()) { "未写入，但研究恢复标记无法清除" }
            candidate(session).delete()
            error("游戏状态已变化，未写入；请重新选择单位")
        }
        try {
            check(NativeCore.readMemory(handle, plan.unitAddress + 0x13, 1)[0].toInt() and 255 == plan.afterHp) {
                "修改后重读不一致"
            }
            // Persist the edited continuation before reporting success. Both files belong to this branch.
            saveState(handle, session.stateFile)
            val record = JSONObject().put("kind", "unit_hp").put("stage", "memory_applied").put("rom", session.romSha256)
                .put("branch", session.id).put("session", snapshot.stamp.sessionId)
                .put("epoch", snapshot.stamp.epoch).put("frame", snapshot.stamp.frame)
                .put("revision", snapshot.stamp.revision).put("address", plan.unitAddress + 0x13)
                .put("before", plan.beforeHp).put("after", plan.afterHp).put("timeMillis", System.currentTimeMillis())
            File(session.directory, "research.jsonl").appendText(record.toString() + "\n")
            Files.move(candidate(session).toPath(), checkpoint(session).toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            check(marker(session).delete()) { "研究已写入但恢复标记无法清除" }
        } catch (failure: Exception) {
            // Roll back an unverified/unrecorded edit, including battery state; the caller closes on rollback failure.
            recoverPending(handle, session)
            throw failure
        }
    }

    fun restore(handle: Long, session: GameSession) {
        require(session.id != "main" && checkpoint(session).isFile) { "当前实验没有编辑前恢复点" }
        check(!marker(session).exists()) { "请先恢复上次未完成的研究编辑" }
        check(!candidate(session).exists() || candidate(session).delete()) { "无法清理未使用的研究候选恢复点" }
        // Use the same recovery marker for interrupted restore persistence.
        check(marker(session).createNewFile()) { "无法建立研究恢复标记" }
        recoverPending(handle, session, recordRecovery = false)
        File(session.directory, "research.jsonl").appendText(JSONObject().put("kind", "restore_before_edit")
            .put("branch", session.id).put("timeMillis", System.currentTimeMillis()).toString() + "\n")
    }

    private fun saveState(handle: Long, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".pending")
        try {
            check(NativeCore.saveState(handle, temporary.absolutePath)) { "研究状态保存失败，原文件已保留" }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
