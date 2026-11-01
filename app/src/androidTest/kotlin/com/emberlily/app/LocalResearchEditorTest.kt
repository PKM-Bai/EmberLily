package com.emberlily.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.Fe8Decoder
import com.emberlily.app.fe.Fe8JLayout
import com.emberlily.app.fe.Fe8ResearchCommands
import com.emberlily.app.fe.Fe8RomCatalog
import com.emberlily.app.fe.FeUnit
import com.emberlily.app.fe.HpEditPlan
import com.emberlily.app.fe.HpEditRequest
import com.emberlily.app.fe.MemorySegment
import com.emberlily.app.fe.MemorySnapshot
import com.emberlily.app.fe.RomProfile
import com.emberlily.app.fe.RomProfiles
import com.emberlily.app.fe.SnapshotStamp
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.SessionStore
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with -e fe8ResearchTest true. Optional filenames:
 * fe8ResearchRom=r04.gba (externalFiles/targets), fe8ResearchIdle=r04-09.state,
 * fe8ResearchDialog=r04-07.state, fe8ResearchMove=r04-08.state (files/local-probes).
 * Inputs are copied to unique cache storage. Only that disposable SessionStore's
 * main and fork are used; the UI's files/sessions directory is never accessed.
 */
@RunWith(AndroidJUnit4::class)
class LocalResearchEditorTest {
    private lateinit var directory: File
    private lateinit var rom: File
    private lateinit var main: GameSession
    private lateinit var branch: GameSession
    private lateinit var profile: RomProfile
    private lateinit var catalog: Fe8RomCatalog
    private lateinit var blockedStates: List<File>
    private lateinit var mainFiles: Map<String, String>
    private lateinit var originalUnit: FeUnit
    private var manualCheckpointHash = ""
    private val inputHashes = linkedMapOf<File, String>()
    private var handle = 0L
    private var epoch = 0L
    private val sessionId = UUID.randomUUID().toString()

    @Before
    fun createPrivateMainAndExperiment() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Set fe8ResearchTest=true to run private FE8 editing fixtures", arguments.getString("fe8ResearchTest") == "true")
        val romName = arguments.getString("fe8ResearchRom") ?: "r04.gba"
        val stateNames = listOf(arguments.getString("fe8ResearchIdle") ?: "r04-09.state",
            arguments.getString("fe8ResearchDialog") ?: "r04-07.state", arguments.getString("fe8ResearchMove") ?: "r04-08.state")
        require(ROM_NAME.matches(romName) && stateNames.all(STATE_NAME::matches) && stateNames.distinct().size == 3)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targets = child(requireNotNull(context.getExternalFilesDir(null)).canonicalFile, "targets")
        val probes = child(context.filesDir.canonicalFile, "local-probes")
        val romBytes = inputBytes(child(targets, romName), 0xC0, 32 * 1024 * 1024)
        val sha = sha256(romBytes)
        require(sha == RomProfiles.FE8J_SHA256 || sha == RomProfiles.FE8J_VOICE_SHA256) { "Unregistered FE8 ROM" }
        profile = checkNotNull(RomProfiles.identify(sha))
        val states = stateNames.map { name -> inputBytes(child(probes, name), 128, 1024 * 1024).also {
            require(String(it, 0, 8, Charsets.US_ASCII) == "ELSTATE2")
        } }
        directory = child(context.cacheDir.canonicalFile, "research-editor-${UUID.randomUUID()}")
        check(directory.mkdir())
        rom = File(directory, "input.gba").apply { writeBytes(romBytes) }
        val idleState = File(directory, "idle.state").apply { writeBytes(states[0]) }
        blockedStates = listOf(File(directory, "dialog.state").apply { writeBytes(states[1]) },
            File(directory, "move.state").apply { writeBytes(states[2]) })
        catalog = Fe8RomCatalog.load(profile, romBytes)
        val store = SessionStore(File(directory, "sessions"))
        main = store.main(sha)
        open(main)
        assertTrue(NativeCore.loadState(handle, idleState.absolutePath))
        assertTrue(NativeCore.saveState(handle, main.stateFile.absolutePath))
        assertTrue(NativeCore.saveState(handle, main.checkpointFile.absolutePath))
        closeCore()
        mainFiles = hashes(main.directory)
        branch = store.fork(main, "Private research fixture")
        manualCheckpointHash = sha256(branch.checkpointFile.readBytes())
        reopenBranch()
        originalUnit = decode(capture()).units.single { it.slot == TARGET_SLOT && it.characterId == TARGET_CHARACTER }
        assertEquals("The target must be Eirika in deployment slot 2", targetAddress, originalUnit.address)
        assertEquals("The accepted idle fixture must have the expected initial HP", 16, originalUnit.hp)
    }

    @After
    fun closeAndVerifyIsolation() {
        try { closeCore() }
        finally {
            try {
                if (::mainFiles.isInitialized) assertEquals("No experiment operation may alter main progress", mainFiles, hashes(main.directory))
                for ((file, hash) in inputHashes) assertEquals("Input fixture changed: ${file.name}", hash, sha256(file.readBytes()))
                if (::rom.isInitialized) assertEquals(profile.identity.sha256, sha256(rom.readBytes()))
            } finally {
                if (::directory.isInitialized) {
                    check(directory.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun editPersistsAcrossReopenAndRecoveryRestoresOriginalHpWithoutChangingMain() {
        val before = capture()
        val plan = plan(before, 15)
        assertEquals(16, plan.beforeHp)
        assertEquals(15, plan.afterHp)
        ResearchEditor.editHp(handle, branch, before, plan)
        assertEquals(15, hp())
        assertEquals(15, decode(capture()).units.single { it.slot == TARGET_SLOT && it.characterId == TARGET_CHARACTER }.hp)
        assertTrue(ResearchEditor.checkpoint(branch).isFile)
        assertEquals("Research recovery must not overwrite the manual checkpoint", manualCheckpointHash, sha256(branch.checkpointFile.readBytes()))
        reopenBranch()
        assertEquals("Edited continuation must persist on restart", 15, hp())
        ResearchEditor.restore(handle, branch)
        assertEquals(16, hp())
        assertRamEquals(before)
        reopenBranch()
        assertEquals("Restored continuation must persist on restart", 16, hp())
        assertRamEquals(before)
        val records = File(branch.directory, "research.jsonl").readLines().map(::JSONObject)
        assertEquals(listOf("unit_hp", "restore_before_edit"), records.map { it.getString("kind") })
        assertEquals(16, records.first().getInt("before"))
        assertEquals(15, records.first().getInt("after"))
        assertEquals(mainFiles, hashes(main.directory))
    }

    @Test
    fun dialogueAndMoveSelectionRejectPlansWithoutChangingRamOrRecoveryFiles() {
        for (state in blockedStates) {
            assertTrue(NativeCore.loadState(handle, state.absolutePath))
            epoch++
            val fresh = capture()
            val decoded = decode(fresh)
            val unit = decoded.units.firstOrNull { it.slot == TARGET_SLOT && it.characterId == TARGET_CHARACTER } ?: originalUnit.copy(identity = originalUnit.identity.copy(
                context = fresh.stamp.copy(frame = 0, revision = 0), chapter = decoded.chapter ?: originalUnit.identity.chapter,
            ))
            val request = HpEditRequest(fresh.stamp, unit.identity, unit.classId,
                fresh.u8(unit.address + 0x13), 15)
            val files = hashes(branch.directory)
            assertThrows("${state.name} must not permit an idle-map HP command", IllegalArgumentException::class.java) {
                Fe8ResearchCommands.planHp(request, fresh, decoded)
            }
            assertRamEquals(fresh)
            assertEquals(files, hashes(branch.directory))
            assertFalse(ResearchEditor.checkpoint(branch).exists())
        }
    }

    @Test
    fun recordingFailureRollsBackCommittedHpAndPersistsTheRollback() {
        val before = capture()
        val plan = plan(before, 15)
        // A directory cannot be opened as the append-only operation log. This
        // fails after compare/write and continuation save, without permissions tricks.
        assertTrue(File(branch.directory, "research.jsonl").mkdir())
        assertThrows(IOException::class.java) { ResearchEditor.editHp(handle, branch, before, plan) }
        assertEquals(16, hp())
        assertRamEquals(before)
        assertFalse("An unsuccessful first edit must not publish a new recovery point", ResearchEditor.checkpoint(branch).exists())
        assertFalse(File(branch.directory, "research-incomplete").exists())
        assertEquals(manualCheckpointHash, sha256(branch.checkpointFile.readBytes()))
        reopenBranch()
        assertEquals("A failed recorded edit must not resume the edited value", 16, hp())
        assertRamEquals(before)
    }

    @Test
    fun failedComparePreservesTheLastSuccessfulRecoveryAndContinuation() {
        val original = capture()
        ResearchEditor.editHp(handle, branch, original, plan(original, 15))
        val recoveryHash = sha256(ResearchEditor.checkpoint(branch).readBytes())
        val resumeHash = sha256(branch.stateFile.readBytes())
        val log = File(branch.directory, "research.jsonl")
        val logHash = sha256(log.readBytes())
        val stale = capture()
        val nextPlan = plan(stale, 14)
        // Deterministically invalidate the expected byte after planning, without
        // advancing a frame or letting the test touch another session's memory.
        writeHp(15, 13)
        val beforeRejectedWrite = capture()
        assertThrows(IllegalStateException::class.java) { ResearchEditor.editHp(handle, branch, stale, nextPlan) }
        assertRamEquals(beforeRejectedWrite)
        assertEquals(13, hp())
        assertEquals(recoveryHash, sha256(ResearchEditor.checkpoint(branch).readBytes()))
        assertEquals(resumeHash, sha256(branch.stateFile.readBytes()))
        assertEquals(logHash, sha256(log.readBytes()))
        assertFalse(File(branch.directory, "research-incomplete").exists())
        assertFalse(File(branch.directory, "research-candidate.state").exists())
        reopenBranch()
        assertEquals("Rejected work must not replace the last successful continuation", 15, hp())
    }

    @Test
    fun reopeningIncompleteWorkPrefersCandidateThenFallsBackToCheckpoint() {
        val original = capture()
        ResearchEditor.editHp(handle, branch, original, plan(original, 15))
        val recoveryHash = sha256(ResearchEditor.checkpoint(branch).readBytes())
        val candidate = File(branch.directory, "research-candidate.state")
        val marker = File(branch.directory, "research-incomplete")
        // Crash fixture: the previous continuation is HP 15, a candidate saves
        // that state, but an uncommitted HP 14 has reached resume.state.
        assertTrue(NativeCore.saveState(handle, candidate.absolutePath))
        assertTrue(marker.createNewFile())
        writeHp(15, 14)
        assertTrue(NativeCore.saveState(handle, branch.stateFile.absolutePath))
        closeCore()
        open(branch)
        assertTrue(ResearchEditor.recoverPending(handle, branch))
        assertEquals("Candidate 15 must win over older checkpoint 16 and incomplete resume 14", 15, hp())
        assertFalse(marker.exists())
        assertFalse(candidate.exists())
        assertEquals(recoveryHash, sha256(ResearchEditor.checkpoint(branch).readBytes()))
        reopenBranch()
        assertEquals("Recovered continuation must survive another reopen", 15, hp())

        // An interrupted explicit restore has a marker and the established
        // checkpoint, but no candidate. Opening must persist HP 16 before normal use.
        assertTrue(marker.createNewFile())
        closeCore()
        open(branch)
        assertTrue(ResearchEditor.recoverPending(handle, branch))
        assertEquals(16, hp())
        assertRamEquals(original)
        assertFalse(marker.exists())
        reopenBranch()
        assertEquals(16, hp())
        assertFalse(ResearchEditor.recoverPending(handle, branch))
        assertEquals(manualCheckpointHash, sha256(branch.checkpointFile.readBytes()))
    }

    private fun plan(fresh: MemorySnapshot, target: Int): HpEditPlan {
        val decoded = decode(fresh)
        val unit = decoded.units.single { it.slot == TARGET_SLOT && it.characterId == TARGET_CHARACTER }
        return Fe8ResearchCommands.planHp(HpEditRequest(fresh.stamp, unit.identity, unit.classId, unit.hp, target), fresh, decoded)
    }

    private fun decode(snapshot: MemorySnapshot) = Fe8Decoder(profile, catalog).decode(snapshot)

    private fun capture(): MemorySnapshot = MemorySnapshot(SnapshotStamp(profile.identity.sha256, sessionId,
        branch.id, epoch, 0, 0, profile.id, profile.version), Fe8JLayout.captureRanges.map {
        MemorySegment(it.address, NativeCore.readMemory(handle, it.address, it.length))
    })

    private fun assertRamEquals(snapshot: MemorySnapshot) {
        for (range in snapshot.ranges) assertArrayEquals(snapshot.readBytes(range.address, range.length),
            NativeCore.readMemory(handle, range.address, range.length))
    }

    private val targetAddress: Long get() = Fe8JLayout.PLAYER_UNITS + (TARGET_SLOT - 1) * Fe8JLayout.UNIT_SIZE

    private fun hp(): Int = NativeCore.readMemory(handle, targetAddress + 0x13, 1)[0].toInt() and 255

    private fun writeHp(expected: Int, replacement: Int) {
        assertTrue(NativeCore.compareAndWriteMemory(handle, longArrayOf(targetAddress + 0x13),
            arrayOf(byteArrayOf(expected.toByte())), arrayOf(byteArrayOf(replacement.toByte()))))
    }

    private fun open(session: GameSession) {
        check(handle == 0L)
        handle = NativeCore.open(rom.absolutePath, session.saveFile.absolutePath)
        check(handle != 0L)
    }

    private fun reopenBranch() {
        closeCore()
        open(branch)
        assertTrue(ResearchEditor.recoverPending(handle, branch) || NativeCore.loadState(handle, branch.stateFile.absolutePath))
        epoch++
    }

    private fun closeCore() {
        val old = handle
        handle = 0L
        if (old != 0L) NativeCore.close(old)
    }

    private fun hashes(directory: File): Map<String, String> = directory.listFiles()!!.filter(File::isFile)
        .associate { it.name to sha256(it.readBytes()) }

    private fun inputBytes(file: File, minimum: Int, maximum: Int): ByteArray {
        val length = file.length()
        require(file.isFile && length in minimum.toLong()..maximum.toLong()) { "Missing or oversized fixture: ${file.name}" }
        val bytes = DataInputStream(file.inputStream()).use { input ->
            ByteArray(length.toInt()).also { input.readFully(it); require(input.read() == -1) }
        }
        inputHashes[file] = sha256(bytes)
        return bytes
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        return File(parent, name).also { require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val TARGET_SLOT = 2
        private const val TARGET_CHARACTER = 1
        private val ROM_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.gba")
        private val STATE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.state")
    }
}
