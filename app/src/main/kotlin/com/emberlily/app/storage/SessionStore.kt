package com.emberlily.app.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.Properties
import java.util.UUID

data class GameSession(
    val id: String,
    val romSha256: String,
    val label: String,
    val directory: File,
) {
    val saveFile: File get() = File(directory, "battery.sav")
    /** Latest automatic continuation state. Kept separate from a manual rollback point. */
    val stateFile: File get() = File(directory, "resume.state")
    val checkpointFile: File get() = File(directory, "checkpoint.state")
}

/**
 * Stores each ROM's main progress and independent experiments in separate directories.
 *
 * The caller must pause the emulator, flush the source battery save and save a
 * resume state before [fork], and keep it paused until copying finishes. This
 * store does not manufacture a coherent snapshot from a running emulator.
 * A fork copies the current battery save (if present) and resume state. That
 * copied resume also initializes the branch's checkpoint at its experimental
 * starting point; the source's older manual checkpoint is not inherited.
 * Metadata and these files are published together, or the branch stays invisible.
 */
class SessionStore(root: File) {
    private val root = root.canonicalFile

    @Synchronized
    fun main(sha256: String): GameSession {
        val hash = normalizeHash(sha256)
        val gameDirectory = gameDirectory(hash)
        ensureDirectory(gameDirectory)
        val destination = checkedChild(gameDirectory, MAIN)
        if (destination.exists()) return readSession(destination, hash, MAIN)
        val pending = newPending(gameDirectory)
        try {
            writeMetadata(pending, MAIN, hash, "Main")
            if (!pending.renameTo(destination)) {
                // A second store may have created the same main directory.
                if (destination.isDirectory) return readSession(destination, hash, MAIN)
                throw IOException("Could not publish main session")
            }
            return readSession(destination, hash, MAIN)
        } finally {
            pending.deleteRecursively()
        }
    }

    @Synchronized
    fun fork(source: GameSession, label: String): GameSession {
        require(label.isNotBlank()) { "Experiment label must not be blank" }
        val hash = normalizeHash(source.romSha256)
        require(isSessionId(source.id)) { "Invalid session ID" }
        val gameDirectory = gameDirectory(hash)
        val expected = checkedChild(gameDirectory, source.id)
        require(source.directory.canonicalFile == expected) { "Source session is outside this store" }
        readSession(expected, hash, source.id)
        val id = UUID.randomUUID().toString()
        val destination = checkedChild(gameDirectory, id)
        val pending = newPending(gameDirectory)
        try {
            // A new game may not have written a battery save yet. A resume state
            // is mandatory, and an existing battery save must copy successfully.
            val save = checkedChild(expected, "battery.sav")
            if (save.exists()) copyFile(save, File(pending, "battery.sav"))
            val resume = File(pending, "resume.state")
            copyFile(checkedChild(expected, "resume.state"), resume)
            copyFile(resume, File(pending, "checkpoint.state"))
            writeMetadata(pending, id, hash, label)
            if (!pending.renameTo(destination)) throw IOException("Could not publish experiment")
            return readSession(destination, hash, id)
        } finally {
            pending.deleteRecursively()
        }
    }

    @Synchronized
    fun list(sha256: String): List<GameSession> {
        val hash = normalizeHash(sha256)
        val directory = gameDirectory(hash)
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw IOException("Game session path is not a directory")
        return (directory.listFiles() ?: throw IOException("Cannot list game sessions"))
            .filter { isSessionId(it.name) }
            .map { readSession(checkedChild(directory, it.name), hash, it.name) }
            .sortedWith(compareBy<GameSession> { it.id != MAIN }.thenBy { it.id })
    }

    private fun gameDirectory(hash: String): File = checkedChild(root, hash)

    private fun checkedChild(parent: File, name: String): File {
        val child = File(parent, name).absoluteFile
        require(child.canonicalFile == child) { "Session path must not traverse symbolic links" }
        return child
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create session directory")
    }

    private fun newPending(gameDirectory: File): File {
        val pending = checkedChild(gameDirectory, ".pending-${UUID.randomUUID()}")
        if (!pending.mkdir()) throw IOException("Cannot create pending session")
        return pending
    }

    private fun writeMetadata(directory: File, id: String, hash: String, label: String) {
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("id", id)
            setProperty("romSha256", hash)
            setProperty("label", label)
        }
        FileOutputStream(File(directory, METADATA)).use {
            properties.store(it, "EmberLily session")
            it.fd.sync()
        }
    }

    private fun readSession(directory: File, hash: String, id: String): GameSession {
        if (!directory.isDirectory) throw IOException("Session is not a directory")
        val properties = Properties().apply {
            checkedChild(directory, METADATA).inputStream().use { load(it) }
        }
        if (properties.getProperty("version") != "1" || properties.getProperty("id") != id ||
            properties.getProperty("romSha256") != hash) {
            throw IOException("Session metadata does not match its ROM and directory")
        }
        val label = properties.getProperty("label") ?: throw IOException("Session label is missing")
        return GameSession(id, hash, label, directory)
    }

    private fun copyFile(source: File, destination: File) {
        if (!source.isFile) throw IOException("Required session file is missing or not a file: ${source.name}")
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun normalizeHash(hash: String): String {
        require(HASH.matches(hash)) { "ROM SHA-256 must contain exactly 64 hexadecimal characters" }
        return hash.lowercase(Locale.ROOT)
    }

    private fun isSessionId(id: String): Boolean = id == MAIN || SESSION_ID.matches(id)

    private companion object {
        const val MAIN = "main"
        const val METADATA = "session.properties"
        val HASH = Regex("[0-9a-fA-F]{64}")
        val SESSION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
