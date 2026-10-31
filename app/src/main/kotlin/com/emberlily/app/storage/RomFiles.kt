package com.emberlily.app.storage

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class ImportedRom(val sha256: String, val name: String, val file: File)

object RomFiles {
    private const val MAX_SIZE = 32 * 1024 * 1024

    fun import(input: InputStream, name: String, directory: File): ImportedRom {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建游戏目录" }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0
            temporary.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= MAX_SIZE) { "文件超过 GBA 的 32 MiB 限制，请选择未压缩的 .gba 文件" }
                    digest.update(buffer, 0, count)
                    out.write(buffer, 0, count)
                }
                out.fd.sync()
            }
            require(total >= 0xC0) { "文件过短，不是完整的 GBA 文件" }
            val header = ByteArray(0xC0)
            temporary.inputStream().use { java.io.DataInputStream(it).readFully(header) }
            require(header[0xB2].toInt() and 255 == 0x96) { "GBA 文件头无效，请选择未压缩的 .gba 文件" }
            val checksum = (-0x19 - (0xA0..0xBC).sumOf { header[it].toInt() and 255 }) and 255
            require(checksum == header[0xBD].toInt() and 255) { "GBA 文件头校验失败，请核对文件" }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val destination = File(directory, "$sha.gba")
            if (!destination.exists()) Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return ImportedRom(sha, name.ifBlank { "GBA 游戏" }, destination)
        } finally {
            temporary.delete()
        }
    }
}
