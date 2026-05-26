package com.martylai.smbserver.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a client session (post-authentication).
 * Manages tree connections and open file handles within this session.
 */
class Smb2Session(
    val sessionId: Long,
    val username: String,        // "" for anonymous
    val isAnonymous: Boolean
) {
    // Tree connections: treeId → share root File
    private val trees = ConcurrentHashMap<Int, File>()
    // File handles: fileId (as Pair<Long,Long>) → FileHandle
    private val handles = ConcurrentHashMap<Long, FileHandle>()

    private val treeIdCounter = AtomicInteger(1)
    private val handleIdCounter = AtomicLong(1)

    // NTLM negotiation state
    var ntlmChallenge: ByteArray? = null
    var awaitingNtlmAuthenticate: Boolean = false

    fun addTree(rootDir: File): Int {
        val id = treeIdCounter.getAndIncrement()
        trees[id] = rootDir
        return id
    }

    fun getTree(treeId: Int): File? = trees[treeId]

    fun removeTree(treeId: Int) {
        trees.remove(treeId)
    }

    fun createHandle(file: File, isDirectory: Boolean, accessMask: Int): Long {
        val id = handleIdCounter.getAndIncrement()
        handles[id] = FileHandle(id, file, isDirectory, accessMask)
        return id
    }

    fun getHandle(id: Long): FileHandle? = handles[id]

    fun closeHandle(id: Long) {
        handles.remove(id)
    }

    fun close() {
        handles.clear()
        trees.clear()
    }
}

/**
 * An open file/directory handle.
 */
class FileHandle(
    val id: Long,
    val file: File,
    val isDirectory: Boolean,
    val accessMask: Int
) {
    // For directory enumeration: list of entries + cursor
    var dirEntries: List<AndroidFileSystem.FileInfo>? = null
    var dirOffset: Int = 0
    var searchPattern: String = "*"
    var firstQuery: Boolean = true

    // For files: track current position (not strictly needed since we use offset-based reads)
    var fileOffset: Long = 0L

    fun encodeId(): Pair<Long, Long> = Pair(id, 0L)
}
