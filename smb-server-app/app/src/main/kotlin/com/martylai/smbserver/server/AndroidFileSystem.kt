package com.martylai.smbserver.server

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * File system abstraction for the SMB server.
 * Operates on a "root" directory exposed as the single SMB share.
 */
class AndroidFileSystem(val rootDir: File) {

    data class FileInfo(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val createdTime: Long,      // Windows FILETIME
        val modifiedTime: Long,     // Windows FILETIME
        val accessTime: Long,       // Windows FILETIME
        val changeTime: Long,       // Windows FILETIME
        val attributes: Int
    )

    /**
     * Resolve a path (from SMB client) to an actual File.
     * Paths from SMB are Windows-style (\) and relative to share root.
     */
    fun resolve(smbPath: String): File {
        if (smbPath.isEmpty() || smbPath == "\\" || smbPath == "/") return rootDir
        val normalized = smbPath.replace('\\', '/').trimStart('/')
        val resolved = File(rootDir, normalized).canonicalFile
        // Security: ensure resolved path is inside root
        if (!resolved.absolutePath.startsWith(rootDir.canonicalPath)) {
            throw SecurityException("Path escapes share root: $smbPath")
        }
        return resolved
    }

    fun getFileInfo(file: File): FileInfo? {
        if (!file.exists()) return null
        val created  = Smb2Constants.toWindowsTime(file.lastModified())
        val modified = Smb2Constants.toWindowsTime(file.lastModified())
        val access   = Smb2Constants.toWindowsTime(System.currentTimeMillis())
        var attrs = if (file.isDirectory) Smb2Constants.FILE_ATTRIBUTE_DIRECTORY
                    else Smb2Constants.FILE_ATTRIBUTE_ARCHIVE
        if (!file.canWrite()) attrs = attrs or Smb2Constants.FILE_ATTRIBUTE_READONLY
        if (file.isHidden) attrs = attrs or Smb2Constants.FILE_ATTRIBUTE_HIDDEN
        return FileInfo(
            name         = file.name,
            isDirectory  = file.isDirectory,
            size         = if (file.isDirectory) 0L else file.length(),
            createdTime  = created,
            modifiedTime = modified,
            accessTime   = access,
            changeTime   = modified,
            attributes   = attrs
        )
    }

    fun listDirectory(dir: File): List<FileInfo> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { getFileInfo(it) }
            .sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name })
    }

    fun readFile(file: File, offset: Long, length: Int): ByteArray {
        if (!file.isFile) return ByteArray(0)
        val raf = RandomAccessFile(file, "r")
        return raf.use {
            it.seek(offset)
            val remaining = (it.length() - offset).coerceAtLeast(0)
            val toRead = minOf(length.toLong(), remaining).toInt()
            if (toRead <= 0) return@use ByteArray(0)
            val buf = ByteArray(toRead)
            var read = 0
            while (read < toRead) {
                val r = it.read(buf, read, toRead - read)
                if (r < 0) break
                read += r
            }
            buf.copyOf(read)
        }
    }

    fun writeFile(file: File, offset: Long, data: ByteArray): Int {
        val raf = RandomAccessFile(file, "rw")
        return raf.use {
            it.seek(offset)
            it.write(data)
            data.size
        }
    }

    fun createDirectory(dir: File): Boolean = dir.mkdirs()

    fun createFile(file: File): Boolean {
        file.parentFile?.mkdirs()
        return file.createNewFile()
    }

    fun deleteFile(file: File): Boolean = if (file.isDirectory) file.deleteRecursively() else file.delete()

    fun renameFile(from: File, to: File): Boolean = from.renameTo(to)

    // ─────────────────────────────────────────────────────────────────────────
    // Build SMB2 directory entry buffers (FileBothDirectoryInformation class 3)
    // ─────────────────────────────────────────────────────────────────────────
    fun buildBothDirInfo(info: FileInfo): ByteArray {
        val nameBytes = info.name.toByteArray(StandardCharsets.UTF_16LE)
        val structSize = 94 + nameBytes.size  // aligned
        val buf = ByteBuffer.allocate(structSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)                             // NextEntryOffset (set later)
        buf.putInt(0)                             // FileIndex
        buf.putLong(info.createdTime)             // CreationTime
        buf.putLong(info.accessTime)              // LastAccessTime
        buf.putLong(info.modifiedTime)            // LastWriteTime
        buf.putLong(info.changeTime)              // ChangeTime
        buf.putLong(info.size)                    // EndOfFile
        buf.putLong(if (info.isDirectory) 0L else roundUpToBlock(info.size)) // AllocationSize
        buf.putInt(info.attributes)               // FileAttributes
        buf.putInt(nameBytes.size)                // FileNameLength
        buf.putInt(0)                             // EaSize
        buf.put(0)                                // ShortNameLength
        buf.put(0)                                // Reserved
        buf.put(ByteArray(24))                    // ShortName (24 bytes)
        buf.put(nameBytes)                        // FileName
        return buf.array().copyOf(structSize)
    }

    fun buildFileBasicInfo(info: FileInfo): ByteArray {
        val buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(info.createdTime)
        buf.putLong(info.accessTime)
        buf.putLong(info.modifiedTime)
        buf.putLong(info.changeTime)
        buf.putInt(info.attributes)
        buf.putInt(0) // pad
        return buf.array()
    }

    fun buildFileStandardInfo(info: FileInfo): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(roundUpToBlock(info.size))  // AllocationSize
        buf.putLong(info.size)                  // EndOfFile
        buf.putInt(1)                           // NumberOfLinks
        buf.put(if (info.isDirectory) 0 else 0) // DeletePending
        buf.put(if (info.isDirectory) 1 else 0) // Directory
        buf.putShort(0)                         // Reserved
        return buf.array()
    }

    fun buildFileAllInfo(info: FileInfo): ByteArray {
        val buf = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        // BasicInformation (40 bytes)
        buf.putLong(info.createdTime)
        buf.putLong(info.accessTime)
        buf.putLong(info.modifiedTime)
        buf.putLong(info.changeTime)
        buf.putInt(info.attributes)
        buf.putInt(0)
        // StandardInformation (24 bytes)
        buf.putLong(roundUpToBlock(info.size))
        buf.putLong(info.size)
        buf.putInt(1)
        buf.put(0.toByte())
        buf.put(if (info.isDirectory) 1.toByte() else 0.toByte())
        buf.putShort(0)
        // InternalInformation (8 bytes)
        buf.putLong(info.name.hashCode().toLong())
        return buf.array()
    }

    fun buildFileNetworkOpenInfo(info: FileInfo): ByteArray {
        val buf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(info.createdTime)
        buf.putLong(info.accessTime)
        buf.putLong(info.modifiedTime)
        buf.putLong(info.changeTime)
        buf.putLong(roundUpToBlock(info.size))  // AllocationSize
        buf.putLong(info.size)                  // EndOfFile
        buf.putInt(info.attributes)
        buf.putInt(0)
        return buf.array()
    }

    // Filesystem info
    fun buildFsSizeInfo(rootDir: File): ByteArray {
        val totalSpace = rootDir.totalSpace
        val freeSpace  = rootDir.freeSpace
        val sectorsPerUnit = 8L
        val bytesPerSector = 512L
        val totalUnits = totalSpace / (sectorsPerUnit * bytesPerSector)
        val freeUnits  = freeSpace  / (sectorsPerUnit * bytesPerSector)
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(totalUnits)
        buf.putLong(freeUnits)
        buf.putInt(sectorsPerUnit.toInt())
        buf.putInt(bytesPerSector.toInt())
        return buf.array()
    }

    fun buildFsFullSizeInfo(rootDir: File): ByteArray {
        val totalSpace = rootDir.totalSpace
        val freeSpace  = rootDir.freeSpace
        val sectorsPerUnit = 8L
        val bytesPerSector = 512L
        val totalUnits = totalSpace / (sectorsPerUnit * bytesPerSector)
        val freeUnits  = freeSpace  / (sectorsPerUnit * bytesPerSector)
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(totalUnits)
        buf.putLong(freeUnits)
        buf.putLong(freeUnits)
        buf.putInt(sectorsPerUnit.toInt())
        buf.putInt(bytesPerSector.toInt())
        return buf.array()
    }

    fun buildFsVolumeInfo(label: String): ByteArray {
        val labelBytes = label.toByteArray(StandardCharsets.UTF_16LE)
        val buf = ByteBuffer.allocate(18 + labelBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(Smb2Constants.toWindowsTime(System.currentTimeMillis()))  // VolumeCreationTime
        buf.putInt(0x12345678)           // VolumeSerialNumber
        buf.putInt(labelBytes.size)      // VolumeLabelLength
        buf.put(0)                       // SupportsObjects
        buf.put(0)                       // Reserved
        buf.put(labelBytes)
        return buf.array()
    }

    fun buildFsAttributeInfo(fsLabel: String = "SMBShare"): ByteArray {
        val nameBytes = "NTFS".toByteArray(StandardCharsets.UTF_16LE)
        val buf = ByteBuffer.allocate(12 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x03e700ff)  // FileSystemAttributes (case-sensitive, unicode, etc.)
        buf.putInt(255)         // MaximumComponentNameLength
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        return buf.array()
    }

    private fun roundUpToBlock(size: Long, blockSize: Long = 4096L): Long =
        if (size == 0L) 0L else ((size + blockSize - 1) / blockSize) * blockSize
}
