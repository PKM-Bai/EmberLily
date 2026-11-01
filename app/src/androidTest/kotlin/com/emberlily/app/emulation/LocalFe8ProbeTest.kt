package com.emberlily.app.emulation

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emberlily.app.BuildConfig
import com.emberlily.app.fe.RomProfiles
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit local diagnostic, skipped by normal test runs. No user session is opened.
 *
 * Runner arguments:
 * - probeRom: ASCII filename under externalFiles/targets, e.g. r03.gba.
 * - probeKeys: decimal GBA key-mask:frame-count pairs, e.g. 0:120,8:1,0:60.
 * - probeOutput: a new ASCII filename prefix, e.g. r03-title-01.
 * - probeState: optional ELSTATE2 filename under externalFiles, or probes/name.state
 *   under filesDir/local-probes (falls back to the legacy externalFiles/probes).
 *
 * The three required arguments together opt in. Partial configuration fails, while
 * no probe arguments skips. Output goes to filesDir/local-probes; existing outputs
 * are never replaced. Copying a previous output to probeState continues that exact
 * core/battery state. All frames run on this test thread against a disposable core.
 * Captures are private evidence, not redistributable fixtures or compatibility proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalFe8ProbeTest {
    @Test
    fun captureExplicitLocalFe8Probe() {
        val arguments = InstrumentationRegistry.getArguments()
        val argumentNames = listOf("probeRom", "probeKeys", "probeOutput", "probeState")
        assumeTrue("Local FE probe requires explicit runner arguments", argumentNames.any(arguments::containsKey))
        check(BuildConfig.DEBUG) { "Local FE probes require a debug build" }
        val romName = required(arguments, "probeRom")
        val scheduleText = required(arguments, "probeKeys")
        val outputPrefix = required(arguments, "probeOutput")
        require(ROM_NAME.matches(romName)) { "probeRom must be an ASCII .gba filename under targets" }
        require(PREFIX.matches(outputPrefix)) { "probeOutput must be 1..64 ASCII letters, digits, underscores or hyphens" }
        val steps = parseSteps(scheduleText)
        val stateName = arguments.getString("probeState")?.also {
            require(STATE_NAME.matches(it)) { "probeState must be name.state or probes/name.state" }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val probes = child(context.filesDir.canonicalFile, "local-probes")
        val targets = child(external, "targets")
        val sourceRom = child(targets, romName)
        val romBytes = boundedBytes(sourceRom, 0xC0, 32 * 1024 * 1024)
        val romSha256 = sha256(romBytes)
        require(romSha256 in setOf(RomProfiles.FE8J_SHA256, RomProfiles.FE8J_VOICE_SHA256,
            RomProfiles.FE7J_SHA256, RomProfiles.FE7J_CHINESE_SHA256)) {
            "Only registered FE7/FE8 R02/R03/R04/R06 ROM fingerprints are accepted"
        }
        val sourceState = stateName?.let { name ->
            if (name.startsWith("probes/")) {
                val filename = name.removePrefix("probes/")
                val internal = child(probes, filename)
                if (internal.exists()) internal else child(child(external, "probes"), filename)
            }
            else child(external, name)
        }
        val stateBytes = sourceState?.let { file ->
            boundedBytes(file, 128, 1024 * 1024).also {
                require(String(it, 0, 8, Charsets.US_ASCII) == "ELSTATE2") { "probeState must use ELSTATE2" }
            }
        }
        check(probes.isDirectory || probes.mkdir()) { "Cannot create probe output directory" }
        val suffixes = listOf(".png", ".state", ".ewram.bin", ".iwram.bin", ".json")
        val destinations = suffixes.associateWith { child(probes, outputPrefix + it) }
        require(destinations.values.none(File::exists)) { "probeOutput already exists; use a new prefix" }

        val directory = child(context.cacheDir.canonicalFile, "fe8-probe-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Cannot create private probe directory" }
        var handle = 0L
        try {
            val privateRom = File(directory, "input.gba").apply { writeBytes(romBytes) }
            val privateBattery = File(directory, "battery.sav")
            handle = NativeCore.open(privateRom.absolutePath, privateBattery.absolutePath)
            check(handle != 0L) { "Cannot open probe core" }
            if (stateBytes != null) {
                val privateState = File(directory, "input.state").apply { writeBytes(stateBytes) }
                check(NativeCore.loadState(handle, privateState.absolutePath)) { "Probe state does not match this ROM/core" }
            }
            val audio = ShortArray(4096)
            val started = System.nanoTime()
            for (step in steps) repeat(step.frames) {
                NativeCore.runFrame(handle, step.keys)
                NativeCore.drainAudio(handle, audio)
            }
            val elapsedNanos = System.nanoTime() - started

            // Capture all outputs between the same two emulated frames.
            val pixels = IntArray(240 * 160)
            check(NativeCore.copyFrame(handle, pixels)) { "Probe did not render a frame" }
            val ewram = NativeCore.readMemory(handle, 0x02000000, 0x40000)
            val iwram = NativeCore.readMemory(handle, 0x03000000, 0x8000)
            val staged = suffixes.associateWith { File(directory, "output$it") }
            staged.getValue(".ewram.bin").writeBytes(ewram)
            staged.getValue(".iwram.bin").writeBytes(iwram)
            check(NativeCore.saveState(handle, staged.getValue(".state").absolutePath)) { "Cannot save probe ELSTATE2" }
            val bitmap = Bitmap.createBitmap(pixels, 240, 160, Bitmap.Config.ARGB_8888)
            try {
                staged.getValue(".png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot encode probe frame" }
                }
            } finally { bitmap.recycle() }
            val manifest = JSONObject().apply {
                put("format", "emberlily-local-fe8-probe-1")
                put("romSha256", romSha256)
                put("romFilename", romName)
                put("coreVersion", NativeCore.coreVersion())
                put("stateFormat", "ELSTATE2")
                put("sourceState", stateName ?: JSONObject.NULL)
                put("sourceStateSha256", stateBytes?.let(::sha256) ?: JSONObject.NULL)
                put("keys", scheduleText)
                put("advancedFrames", steps.sumOf { it.frames })
                put("advanceElapsedNanos", elapsedNanos)
                put("artifacts", JSONArray().apply {
                    for (suffix in suffixes.filter { it != ".json" }) put(JSONObject().apply {
                        put("filename", outputPrefix + suffix)
                        put("size", staged.getValue(suffix).length())
                        put("sha256", sha256(staged.getValue(suffix).readBytes()))
                    })
                })
            }
            staged.getValue(".json").writeText(manifest.toString(2) + "\n")
            check(sha256(boundedBytes(sourceRom, 0xC0, 32 * 1024 * 1024)) == romSha256) { "Source ROM changed during probe" }
            if (sourceState != null && stateBytes != null) {
                check(boundedBytes(sourceState, 128, 1024 * 1024).contentEquals(stateBytes)) { "Source state changed during probe" }
            }
            // Copy without replacement. The manifest is last and marks a complete set.
            for (suffix in suffixes) Files.copy(staged.getValue(suffix).toPath(), destinations.getValue(suffix).toPath())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "FE probe captured: ${destinations.getValue(".json").absolutePath}\n")
            })
        } finally {
            try { if (handle != 0L) NativeCore.close(handle) }
            finally {
                // The unique cache directory is the only recursively removed path.
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    private data class Step(val keys: Int, val frames: Int)

    private fun parseSteps(text: String): List<Step> {
        require(text.length in 3..1800) { "Invalid probeKeys length" }
        val entries = text.split(',')
        require(entries.size in 1..120) { "probeKeys accepts at most 120 steps" }
        val steps = entries.map { entry ->
            require(STEP.matches(entry)) { "Use decimal key-mask:frames pairs" }
            val (keys, frames) = entry.split(':').map(String::toInt)
            require(keys in 0..1023 && frames in 1..36000) { "Invalid GBA key bits or frame count" }
            Step(keys, frames)
        }
        require(steps.sumOf { it.frames } <= 36000) { "A probe may advance at most 36000 frames" }
        return steps
    }

    private fun required(arguments: Bundle, name: String): String =
        requireNotNull(arguments.getString(name)) { "$name is required when opting in to a local FE probe" }

    /** Requires one child component and prevents symlink redirection outside its named directory. */
    private fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        val file = File(parent, name)
        require(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == parent.canonicalFile) {
            "Probe paths must stay within their named directory"
        }
        return file
    }

    private fun boundedBytes(file: File, minimum: Int, maximum: Int): ByteArray {
        val length = file.length()
        require(file.isFile && length in minimum.toLong()..maximum.toLong()) { "Invalid probe input size: ${file.name}" }
        return DataInputStream(file.inputStream()).use { input ->
            ByteArray(length.toInt()).also {
                input.readFully(it)
                require(input.read() == -1) { "Probe input grew while being read" }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val PREFIX = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val ROM_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.gba")
        private val STATE_NAME = Regex("(?:probes/)?[A-Za-z0-9][A-Za-z0-9._-]{0,91}\\.state")
        private val STEP = Regex("[0-9]{1,4}:[0-9]{1,5}")
    }
}
