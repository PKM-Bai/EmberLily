package com.emberlily.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.*
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.ImportedRom
import com.emberlily.app.storage.SessionStore
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Private FE7 integration; run separately for R02 and R06, never infer compatibility from AE7J.
 * Opt in: -e fe7Integration true -e fe7Target r02 (or r06).
 * Required decimal CSV expectations, transcribed from independently inspected real fixtures:
 *   fe7ExpectedMap=chapter,turn,cursorX,cursorY,width,height
 *   fe7ExpectedUnit=slot,characterId,classId,hp,maxHp,x,y,level,experience
 *   fe7ExpectedItems=rawSlot0,rawSlot1,rawSlot2,rawSlot3,rawSlot4 (decimal or 0x-prefixed)
 * Optional filenames (plain ASCII; targets/ is externalFiles, local-probes/ is internal files):
 *   fe7Rom=<target>.gba; fe7Map=<target>-idle.state; fe7Title=<target>-title.state;
 *   fe7Dialog=<target>-dialog.state; fe7Movement=<target>-movement.state.
 * fe7DialogScene defaults to MAP; only MAP or UNAVAILABLE is allowed, never INVALID.
 * fe7MovementSlot defaults to the independently declared expected unit slot; use -1 for a
 * separately verified tutorial-blocked selection that has not entered the free-movement callback.
 * The engine-switch test additionally needs fe8Rom=r04.gba and fe8Map=r04-09.state.
 *
 * No runFrame calls: live cores only restore captured observations, read and perform the explicit
 * HP transaction on a disposable experiment. Source ROMs/states and UI sessions are never written.
 * Read-only validation runs while capabilities are pending. The edit test requires the registered
 * profile's RESEARCH_EDIT capability; it never fabricates an enabled profile or suppresses failure.
 */
@RunWith(AndroidJUnit4::class)
class LocalFe7IntegrationTest {
    private data class Fixture(val profile: RomProfile, val rom: ImportedRom,
        val catalog: FeRomCatalog, val states: Map<String, File>)
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var store: SessionStore
    private lateinit var runtime: CompanionRuntime
    private lateinit var fe7: Fixture
    private lateinit var expectedMap: List<Int>
    private lateinit var expectedUnit: List<Int>
    private lateinit var expectedItems: List<Int>
    private val results = LinkedBlockingQueue<() -> Unit>()
    private val sourceHashes = linkedMapOf<File, String>()
    private val protectedFiles = linkedMapOf<File, Map<String, String>>()
    private var handle = 0L

    @Before fun preparePrivateFixtures() {
        assumeTrue("Set fe7Integration=true to run explicit FE7 fixtures", args.getString("fe7Integration") == "true")
        val target = args.getString("fe7Target") ?: error("fe7Target must be r02 or r06")
        val hash = when (target) {
            "r02" -> RomProfiles.FE7J_SHA256
            "r06" -> RomProfiles.FE7J_CHINESE_SHA256
            else -> error("Only independently registered R02/R06 may run this suite")
        }
        expectedMap = requiredCsv("fe7ExpectedMap", 6)
        expectedUnit = requiredCsv("fe7ExpectedUnit", 9)
        expectedItems = requiredCsv("fe7ExpectedItems", 5)
        require(expectedItems.all { it in 0..65535 })
        require(expectedUnit[0] in 1..255 && expectedUnit[3] in 1..expectedUnit[4] && expectedUnit[4] > 1)
        directory = child(context.cacheDir.canonicalFile, "fe7-integration-${UUID.randomUUID()}")
        check(directory.mkdir())
        store = SessionStore(File(directory, "sessions"))
        runtime = CompanionRuntime { callback -> results.add(callback) }
        fe7 = fixture(target, hash, args.getString("fe7Rom") ?: "$target.gba", linkedMapOf(
            "map" to (args.getString("fe7Map") ?: "$target-idle.state"),
            "title" to (args.getString("fe7Title") ?: "$target-title.state"),
            "dialog" to (args.getString("fe7Dialog") ?: "$target-dialog.state"),
            "movement" to (args.getString("fe7Movement") ?: "$target-movement.state"),
        ))
    }

    @After fun closeAndVerifyIsolation() {
        try { if (::runtime.isInitialized) runtime.close() }
        finally {
            try { closeCore() }
            finally {
                try {
                    protectedFiles.forEach { (folder, expectedFiles) -> assertEquals("Unrelated progress changed: ${folder.name}", expectedFiles, hashes(folder)) }
                    sourceHashes.forEach { (file, hash) -> assertEquals("Source fixture changed: ${file.name}", hash, sha256(file.readBytes())) }
                } finally {
                    if (::directory.isInitialized) {
                        check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }

    @Test fun realScenesDecodeReplayAndRejectNonIdleEditing() {
        val main = seedMain(fe7)
        open(fe7, main)
        val idle = publish()
        val originalUnit = assertIdle(idle)
        runtime.pin(originalUnit.identity)
        assertNotNull(runtime.state.value.comparison)
        val oldRequest = request(idle, originalUnit)

        // Inject observed title RAM without the host restore hook to exercise automatic scene
        // invalidation. Production restore separately invalidates before sampling.
        load(fe7.states.getValue("title"))
        val titleBytes = runtime.capture(handle, 0)
        runtime.sample(handle, 0)
        awaitResult().invoke()
        assertNull("Scene transition clears stale units immediately", runtime.state.value.battle)
        assertNull(runtime.state.value.pinned)
        assertNull(runtime.state.value.comparison)
        assertFalse(runtime.accepts(idle.stamp))
        awaitResult().invoke() // Same bytes are redecoded in the new epoch without another capture/frame.
        assertEquals(FeScene.UNAVAILABLE, runtime.state.value.battle?.scene)
        assertRamEquals(titleBytes)

        val dialogScene = FeScene.valueOf(args.getString("fe7DialogScene") ?: "MAP")
        require(dialogScene == FeScene.MAP || dialogScene == FeScene.UNAVAILABLE)
        val scenes = linkedMapOf("title" to FeScene.UNAVAILABLE, "dialog" to dialogScene, "movement" to FeScene.MAP)
        for ((name, expectedScene) in scenes) {
            load(fe7.states.getValue(name))
            runtime.invalidate("Restore private $name fixture")
            val snapshot = runtime.capture(handle, 0)
            val decoded = FeDecoder(fe7.profile, fe7.catalog).decodeRaw(snapshot)
            assertEquals("$name scene: ${decoded.reason}", expectedScene, decoded.scene)
            if (name == "movement") {
                val observedSlot = args.getString("fe7MovementSlot")?.toInt() ?: expectedUnit[0]
                require(observedSlot == -1 || observedSlot in 1..0xB2)
                val expectedSlot: Int? = observedSlot.takeUnless { it == -1 }
                assertEquals("Only a verified free-movement callback identifies the selection", expectedSlot, decoded.selectedSlot)
            } else assertNull("Non-movement fixture cannot retain a selected unit", decoded.selectedSlot)

            val unit = decoded.units.firstOrNull { it.slot == originalUnit.slot } ?: originalUnit.copy(identity = originalUnit.identity.copy(
                context = snapshot.stamp.copy(frame = 0, revision = 0), chapter = decoded.chapter ?: originalUnit.identity.chapter))
            val blocked = HpEditRequest(snapshot.stamp, unit.identity, unit.classId,
                snapshot.u8(unit.address + 0x13), replacementHp(originalUnit))
            val files = hashes(main.directory)
            assertThrows("$name must reject the idle-map HP operation", IllegalArgumentException::class.java) {
                FeResearchCommands.planHp(blocked, snapshot, decoded)
            }
            val replay = SnapshotCodec.decode(ByteArrayInputStream(SnapshotCodec.encode(snapshot)), fe7.profile.identity.sha256)
            assertEquals(decoded, FeDecoder(fe7.profile, fe7.catalog).decodeRaw(replay))
            assertRamEquals(snapshot)
            assertEquals(files, hashes(main.directory))
        }
        load(fe7.states.getValue("map"))
        runtime.invalidate("Restore private idle fixture")
        val returned = publish()
        assertIdle(returned)
        assertFalse(runtime.accepts(oldRequest.observedStamp))
        assertThrows(IllegalArgumentException::class.java) {
            FeResearchCommands.planHp(oldRequest, runtime.capture(handle, 0), returned)
        }
    }

    @Test fun experimentalHpPersistsAndRestoresWithoutChangingMain() {
        assertTrue("Register real FE7 evidence before enabling this integration path; the test never enables capabilities",
            fe7.profile.supports(FeCapability.RESEARCH_EDIT))
        val main = seedMain(fe7)
        protectedFiles[main.directory] = hashes(main.directory)
        val branch = store.fork(main, "Private FE7 HP verification")
        val manualCheckpoint = sha256(branch.checkpointFile.readBytes())
        open(fe7, branch)
        val before = runtime.capture(handle, 0)
        val decoded = FeDecoder(fe7.profile, fe7.catalog).decodeRaw(before)
        val unit = assertIdle(decoded)
        val plan = FeResearchCommands.planHp(request(decoded, unit), before, decoded)
        ResearchEditor.editHp(handle, branch, before, plan)
        runtime.invalidate("Private HP transaction completed", written = true)
        assertHpOnlyChanged(before, unit.address + 0x13, plan.afterHp)
        assertEquals(manualCheckpoint, sha256(branch.checkpointFile.readBytes()))
        assertTrue(ResearchEditor.checkpoint(branch).isFile)
        open(fe7, branch)
        assertEquals("Edited continuation must survive restart", plan.afterHp, currentUnit().hp)
        ResearchEditor.restore(handle, branch)
        runtime.invalidate("Private research restore")
        assertRamEquals(before)
        assertEquals(unit.hp, currentUnit().hp)
        open(fe7, branch)
        assertEquals("Recovered continuation must survive restart", unit.hp, currentUnit().hp)
        assertRamEquals(before)
        val records = File(branch.directory, "research.jsonl").readLines().map(::JSONObject)
        assertEquals(listOf("unit_hp", "restore_before_edit"), records.map { it.getString("kind") })
        assertEquals(fe7.profile.identity.sha256, records.first().getString("rom"))
        assertEquals(branch.id, records.first().getString("branch"))
        assertEquals(unit.hp, records.first().getInt("before"))
        assertEquals(plan.afterHp, records.first().getInt("after"))
    }

    @Test fun fe8Fe7Fe8SwitchRejectsOldPinsRequestsAndCompletedWorkerResults() {
        val fe8 = fixture("r04", RomProfiles.FE8J_VOICE_SHA256, args.getString("fe8Rom") ?: "r04.gba",
            mapOf("map" to (args.getString("fe8Map") ?: "r04-09.state")))
        val main8 = seedMain(fe8)
        val main7 = seedMain(fe7)
        protectedFiles[main8.directory] = hashes(main8.directory)
        protectedFiles[main7.directory] = hashes(main7.directory)
        assertNotEquals(main8.directory.canonicalFile, main7.directory.canonicalFile)

        open(fe8, main8)
        val first8 = publish()
        val unit8 = first8.units.single { it.slot == 2 && it.characterId == 1 }
        assertEquals(16, unit8.hp) // Independently verified r04-09 Eirika fixture.
        val bytes8 = runtime.capture(handle, 0)
        val request8 = request(first8, unit8)
        runtime.pin(unit8.identity)
        assertNotNull(runtime.state.value.comparison)
        runtime.sample(handle, 0)
        val late8 = awaitResult()

        open(fe7, main7)
        val awaiting7 = runtime.state.value
        late8.invoke()
        assertEquals("Completed FE8 callback must not replace FE7 context", awaiting7, runtime.state.value)
        assertNull(runtime.state.value.pinned)
        assertNull(runtime.state.value.comparison)
        assertFalse(runtime.accepts(first8.stamp))
        val battle7 = publish()
        val unit7 = assertIdle(battle7)
        val bytes7 = runtime.capture(handle, 0)
        assertEquals(fe7.profile.id, runtime.state.value.profile?.id)
        assertEquals(fe7.profile.layout.unitAddress(unit7.slot), unit7.address)
        assertEquals(FeScene.INVALID, FeDecoder(fe7.profile, fe7.catalog).decodeRaw(bytes8).scene)
        runtime.pin(unit8.identity)
        assertNull(runtime.state.value.pinned)
        assertThrows(IllegalArgumentException::class.java) { FeResearchCommands.planHp(request8, bytes7, battle7) }
        val request7 = request(battle7, unit7)
        runtime.pin(unit7.identity)
        assertNotNull(runtime.state.value.comparison)
        runtime.sample(handle, 0)
        val late7 = awaitResult()

        open(fe8, main8)
        val awaiting8 = runtime.state.value
        late7.invoke()
        assertEquals("Completed FE7 callback must not replace reopened FE8 context", awaiting8, runtime.state.value)
        assertNull(runtime.state.value.pinned)
        assertFalse(runtime.accepts(first8.stamp))
        assertFalse(runtime.accepts(battle7.stamp))
        val returned8 = publish()
        assertEquals(fe8.profile.id, runtime.state.value.profile?.id)
        assertNotEquals(first8.stamp.sessionId, returned8.stamp.sessionId)
        assertEquals(unit8.copy(identity = returned8.units.single { it.slot == 2 }.identity), returned8.units.single { it.slot == 2 })
        assertRamEquals(bytes8)
        val fresh8 = runtime.capture(handle, 0)
        assertEquals(FeScene.INVALID, FeDecoder(fe8.profile, fe8.catalog).decodeRaw(bytes7).scene)
        assertThrows(IllegalArgumentException::class.java) { FeResearchCommands.planHp(request7, fresh8, returned8) }
        assertThrows(IllegalArgumentException::class.java) { FeResearchCommands.planHp(request8, fresh8, returned8) }
        assertRamEquals(bytes8)
    }

    private fun assertIdle(battle: FeBattleSnapshot): FeUnit {
        assertEquals("${fe7.profile.id}: ${battle.reason}", FeScene.MAP, battle.scene)
        assertEquals(expectedMap[0], battle.chapter); assertEquals(expectedMap[1], battle.turn)
        assertEquals(0, battle.phase)
        assertEquals(MapPosition(expectedMap[2], expectedMap[3]), battle.cursor)
        assertEquals(expectedMap[4], battle.mapWidth); assertEquals(expectedMap[5], battle.mapHeight)
        assertNull(battle.selectedSlot)
        val unit = battle.units.single { it.slot == expectedUnit[0] }
        assertEquals(expectedUnit, listOf(unit.slot, unit.characterId, unit.classId, unit.hp, unit.maxHp,
            unit.x, unit.y, unit.level, unit.experience))
        assertEquals(expectedItems, unit.items.map { it.raw })
        return unit
    }

    private fun request(battle: FeBattleSnapshot, unit: FeUnit) = HpEditRequest(battle.stamp, unit.identity, unit.classId, unit.hp, replacementHp(unit))
    private fun replacementHp(unit: FeUnit) = if (unit.hp > 1) unit.hp - 1 else 2
    private fun currentUnit() = FeDecoder(fe7.profile, fe7.catalog).decodeRaw(runtime.capture(handle, 0)).units.single { it.slot == expectedUnit[0] }
    private fun publish(): FeBattleSnapshot {
        runtime.sample(handle, 0)
        awaitResult().invoke()
        if (runtime.state.value.battle == null) awaitResult().invoke()
        return checkNotNull(runtime.state.value.battle)
    }
    private fun awaitResult(): () -> Unit = checkNotNull(results.poll(10, TimeUnit.SECONDS)) { "Companion worker did not publish within ten seconds" }
    private fun load(file: File) = assertTrue("State must belong to the exact ROM/core: ${file.name}", NativeCore.loadState(handle, file.absolutePath))
    private fun open(fixture: Fixture, session: GameSession) {
        closeCore()
        handle = NativeCore.open(fixture.rom.file.absolutePath, session.saveFile.absolutePath)
        check(handle != 0L)
        runtime.open(fixture.rom, session)
        if (session.stateFile.isFile) load(session.stateFile)
    }
    private fun seedMain(fixture: Fixture): GameSession {
        val main = store.main(fixture.profile.identity.sha256)
        open(fixture, main)
        load(fixture.states.getValue("map"))
        assertTrue(NativeCore.saveState(handle, main.stateFile.absolutePath))
        assertTrue(NativeCore.saveState(handle, main.checkpointFile.absolutePath))
        closeCore()
        return main
    }
    private fun closeCore() { val old = handle; handle = 0L; if (old != 0L) NativeCore.close(old) }
    private fun assertRamEquals(snapshot: MemorySnapshot) {
        snapshot.ranges.forEach { assertArrayEquals(snapshot.readBytes(it.address, it.length), NativeCore.readMemory(handle, it.address, it.length)) }
    }
    private fun assertHpOnlyChanged(snapshot: MemorySnapshot, address: Long, hp: Int) {
        snapshot.ranges.forEach { range ->
            val expected = snapshot.readBytes(range.address, range.length)
            if (address in range.address until range.endExclusive) expected[(address - range.address).toInt()] = hp.toByte()
            assertArrayEquals("Only the confirmed HP byte may change", expected, NativeCore.readMemory(handle, range.address, range.length))
        }
    }
    private fun fixture(label: String, expectedHash: String, romName: String, names: Map<String, String>): Fixture {
        require(ROM_NAME.matches(romName) && names.values.all(STATE_NAME::matches) && names.values.distinct().size == names.size)
        val targets = child(requireNotNull(context.getExternalFilesDir(null)).canonicalFile, "targets")
        val probes = child(context.filesDir.canonicalFile, "local-probes")
        val romBytes = inputBytes(child(targets, romName), 0xC0, 32 * 1024 * 1024)
        assertEquals("$label must use its independently registered fingerprint", expectedHash, sha256(romBytes))
        val profile = checkNotNull(RomProfiles.identify(expectedHash))
        val destination = child(directory, label).apply { check(mkdir()) }
        val rom = child(destination, "input.gba").apply { writeBytes(romBytes) }
        val states = names.mapValues { (kind, name) ->
            val bytes = inputBytes(child(probes, name), 128, 1024 * 1024)
            require(String(bytes, 0, 8, Charsets.US_ASCII) == "ELSTATE2")
            child(destination, "$kind.state").apply { writeBytes(bytes) }
        }
        return Fixture(profile, ImportedRom(expectedHash, "Private $label fixture", rom), FeRomCatalog.load(profile, romBytes), states)
    }
    private fun requiredCsv(key: String, count: Int): List<Int> = (args.getString(key) ?: error("Required independent expectation: $key"))
        .split(',').map { if (it.startsWith("0x")) it.substring(2).toInt(16) else it.toInt() }
        .also { require(it.size == count) { "$key requires exactly $count values" } }
    private fun inputBytes(file: File, min: Int, max: Int): ByteArray {
        val length = file.length()
        require(file.isFile && length in min.toLong()..max.toLong()) { "Missing/oversized local fixture: ${file.name}" }
        return DataInputStream(file.inputStream()).use { stream -> ByteArray(length.toInt()).also {
            stream.readFully(it); require(stream.read() == -1); sourceHashes[file] = sha256(it)
        } }
    }
    private fun hashes(folder: File): Map<String, String> = checkNotNull(folder.listFiles()).filter(File::isFile).associate { it.name to sha256(it.readBytes()) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        return File(parent, name).also { require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile) }
    }
    companion object {
        private val ROM_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.gba")
        private val STATE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.state")
    }
}
