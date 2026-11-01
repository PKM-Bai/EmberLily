package com.emberlily.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.emulation.NativeCore
import com.emberlily.app.fe.Fe8Decoder
import com.emberlily.app.fe.Fe8RomCatalog
import com.emberlily.app.fe.FeScene
import com.emberlily.app.fe.MemorySnapshot
import com.emberlily.app.fe.RomProfile
import com.emberlily.app.fe.RomProfiles
import com.emberlily.app.fe.SnapshotCodec
import com.emberlily.app.fe.SnapshotReplay
import com.emberlily.app.storage.GameSession
import com.emberlily.app.storage.ImportedRom
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Private, explicit local integration. Enable only with -e fe8SceneTest true.
 * Optional filenames: fe8SceneRom=r04.gba under externalFiles/targets,
 * fe8SceneMap=r04-09.state and fe8SceneOther=r04-02.state under files/local-probes.
 * Only the two registered FE8 ROM fingerprints are accepted. State inputs are
 * ELSTATE2; this test never opens the UI session store or writes its input files.
 *
 * Native loadState injects observed fixture RAM without invoking the host restore
 * hook in the scene-transition test. This intentionally isolates automatic scene
 * invalidation from the separately tested host restore epoch. It is not the
 * production restore protocol. No game frame is run anywhere in these tests.
 */
@RunWith(AndroidJUnit4::class)
class LocalFe8SceneTest {
    private lateinit var directory: File
    private lateinit var mapState: File
    private lateinit var otherState: File
    private lateinit var runtime: CompanionRuntime
    private lateinit var profile: RomProfile
    private lateinit var catalog: Fe8RomCatalog
    private val results = LinkedBlockingQueue<() -> Unit>()
    private val sources = linkedMapOf<File, String>()
    private var handle = 0L

    @Before
    fun prepareExplicitFixtures() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Set fe8SceneTest=true to run private FE8 scene fixtures", arguments.getString("fe8SceneTest") == "true")
        val romName = arguments.getString("fe8SceneRom") ?: "r04.gba"
        val mapName = arguments.getString("fe8SceneMap") ?: "r04-09.state"
        val otherName = arguments.getString("fe8SceneOther") ?: "r04-02.state"
        require(ROM_NAME.matches(romName) && STATE_NAME.matches(mapName) && STATE_NAME.matches(otherName)) {
            "Scene fixtures must be plain ASCII .gba/.state filenames"
        }
        require(mapName != otherName) { "Map and non-map fixtures must differ" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targets = child(requireNotNull(context.getExternalFilesDir(null)).canonicalFile, "targets")
        val probes = child(context.filesDir.canonicalFile, "local-probes")
        val sourceRom = child(targets, romName)
        val romBytes = inputBytes(sourceRom, 0xC0, 32 * 1024 * 1024)
        val hash = sha256(romBytes)
        require(hash == RomProfiles.FE8J_SHA256 || hash == RomProfiles.FE8J_VOICE_SHA256) { "Unregistered FE8 ROM" }
        profile = checkNotNull(RomProfiles.identify(hash))
        val mapBytes = stateBytes(child(probes, mapName))
        val otherBytes = stateBytes(child(probes, otherName))
        directory = child(context.cacheDir.canonicalFile, "fe8-scene-${UUID.randomUUID()}")
        check(directory.mkdir())
        val rom = File(directory, "input.gba").apply { writeBytes(romBytes) }
        mapState = File(directory, "map.state").apply { writeBytes(mapBytes) }
        otherState = File(directory, "other.state").apply { writeBytes(otherBytes) }
        // A disposable UUID branch is sufficient; there is no reference to files/sessions.
        val session = GameSession(UUID.randomUUID().toString(), hash, "Local scene fixture", directory)
        runtime = CompanionRuntime { result -> results.add(result) }
        handle = NativeCore.open(rom.absolutePath, session.saveFile.absolutePath)
        check(handle != 0L) { "Cannot open isolated FE8 fixture core" }
        runtime.open(ImportedRom(hash, "Local FE8 fixture", rom), session)
        catalog = Fe8RomCatalog.load(profile, romBytes)
    }

    @After
    fun closeIsolatedCoreAndCheckInputs() {
        try { if (::runtime.isInitialized) runtime.close() }
        finally {
            try { if (handle != 0L) NativeCore.close(handle) }
            finally {
                handle = 0L
                try {
                    for ((file, expectedHash) in sources) assertEquals("Fixture must remain unchanged: ${file.name}", expectedHash, sha256(file.readBytes()))
                } finally {
                    if (::directory.isInitialized) {
                        check(directory.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }

    @Test
    fun sceneChangesReanalyzeTheSameBytesInANewEpochWhilePaused() {
        load(otherState)
        runtime.sample(handle, 0)
        awaitResult().invoke()
        assertEquals("The other fixture must be outside the tactical map", FeScene.UNAVAILABLE, runtime.state.value.battle?.scene)
        val oldMenu = runtime.capture(handle, 0)

        load(mapState)
        val mapBefore = runtime.capture(handle, 0)
        runtime.sample(handle, 0)
        awaitResult().invoke() // First result observes the scene change and invalidates.
        assertNull(runtime.state.value.battle)
        assertFalse(runtime.accepts(oldMenu.stamp))
        assertFalse(runtime.accepts(mapBefore.stamp))

        // No runFrame, capture or sample call schedules this second result.
        awaitResult().invoke()
        val map = checkNotNull(runtime.state.value.battle)
        assertEquals(FeScene.MAP, map.scene)
        assertEquals(mapBefore.stamp.epoch + 1, map.stamp.epoch)
        assertEquals(mapBefore.stamp.frame, map.stamp.frame)
        assertEquals(mapBefore.stamp.revision, map.stamp.revision)
        assertTrue(runtime.accepts(map.stamp))
        assertRamEquals(mapBefore)
        assertTrue("Map fixture must contain units for pin invalidation", map.units.isNotEmpty())
        runtime.pin(map.units.first().identity)
        assertNotNull(runtime.state.value.comparison)

        load(otherState)
        val menuBefore = runtime.capture(handle, 0)
        runtime.sample(handle, 0)
        awaitResult().invoke()
        assertNull(runtime.state.value.battle)
        assertNull(runtime.state.value.pinned)
        assertNull(runtime.state.value.comparison)
        assertFalse(runtime.accepts(map.stamp))
        awaitResult().invoke()
        val menu = checkNotNull(runtime.state.value.battle)
        assertEquals(FeScene.UNAVAILABLE, menu.scene)
        assertEquals(map.stamp.epoch + 1, menu.stamp.epoch)
        assertEquals(menuBefore.stamp.frame, menu.stamp.frame)
        assertTrue(runtime.accepts(menu.stamp))
        assertRamEquals(menuBefore)
        assertNull(runtime.state.value.pinned)
    }

    @Test
    fun realCapturedRamRoundTripsAndReplaysWithoutTouchingTheLiveCore() {
        load(mapState)
        val map = runtime.capture(handle, 0)
        val mapSemantics = Fe8Decoder(profile, catalog).decodeRaw(map)
        assertEquals(FeScene.MAP, mapSemantics.scene)
        assertTrue(mapSemantics.units.isNotEmpty())
        load(otherState)
        runtime.invalidate("Restore another private fixture")
        val menu = runtime.capture(handle, 0)
        val menuSemantics = Fe8Decoder(profile, catalog).decodeRaw(menu)
        assertEquals(FeScene.UNAVAILABLE, menuSemantics.scene)

        fun roundTrip(snapshot: MemorySnapshot): MemorySnapshot {
            val encoded = SnapshotCodec.encode(snapshot)
            val decoded = SnapshotCodec.decode(ByteArrayInputStream(encoded), profile.identity.sha256)
            assertEquals(snapshot.stamp, decoded.stamp)
            assertEquals(snapshot.ranges, decoded.ranges)
            for (range in snapshot.ranges) assertArrayEquals(
                snapshot.readBytes(range.address, range.length), decoded.readBytes(range.address, range.length),
            )
            return decoded
        }
        val replay = SnapshotReplay(listOf(roundTrip(map), roundTrip(menu)))
        val decoder = Fe8Decoder(profile, catalog)
        assertEquals(mapSemantics, decoder.decodeRaw(replay.current))
        assertEquals(menuSemantics, decoder.decodeRaw(replay.seek(1)))
        assertEquals(mapSemantics, decoder.decodeRaw(replay.seek(0)))
        assertRamEquals(menu)
        assertTrue("Offline replay must not change the live context", runtime.accepts(menu.stamp))
        assertFalse(runtime.accepts(map.stamp))
    }

    private fun load(file: File) {
        assertTrue("Fixture state must match this ROM/core: ${file.name}", NativeCore.loadState(handle, file.absolutePath))
    }

    private fun assertRamEquals(snapshot: MemorySnapshot) {
        for (range in snapshot.ranges) assertArrayEquals("Analysis must not change captured RAM",
            snapshot.readBytes(range.address, range.length), NativeCore.readMemory(handle, range.address, range.length))
    }

    private fun awaitResult(): () -> Unit = checkNotNull(results.poll(10, TimeUnit.SECONDS)) {
        "Scene analysis did not deliver its owner callback within ten seconds"
    }

    private fun stateBytes(file: File): ByteArray = inputBytes(file, 128, 1024 * 1024).also {
        require(String(it, 0, 8, Charsets.US_ASCII) == "ELSTATE2") { "Scene fixtures must use ELSTATE2" }
    }

    private fun inputBytes(file: File, minimum: Int, maximum: Int): ByteArray {
        val length = file.length()
        require(file.isFile && length in minimum.toLong()..maximum.toLong()) { "Missing or oversized fixture: ${file.name}" }
        val bytes = DataInputStream(file.inputStream()).use { input ->
            ByteArray(length.toInt()).also { input.readFully(it); require(input.read() == -1) }
        }
        sources[file] = sha256(bytes)
        return bytes
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        return File(parent, name).also {
            require(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val ROM_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.gba")
        private val STATE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.state")
    }
}
