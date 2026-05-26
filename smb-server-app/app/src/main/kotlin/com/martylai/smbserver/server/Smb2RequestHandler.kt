package com.martylai.smbserver.server

import android.util.Log
import com.martylai.smbserver.data.ServerConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles all SMB2 commands, producing response byte arrays.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * IMPORTANT: SMB2 "offset" fields in request/response structures are always
 * relative to the start of the SMB2 message (header included, 64 bytes).
 * When indexing into the `body` array (= message minus the 64-byte header):
 *   bodyIndex = smbOffset - SMB2_HEADER_SIZE  (i.e. - 64)
 * ──────────────────────────────────────────────────────────────────────────
 */
class Smb2RequestHandler(
    private val config: ServerConfig,
    private val fileSystem: AndroidFileSystem
) {
    private val TAG = "Smb2Handler"

    // NTSTATUS for "object name collision" (file already exists)
    private val STATUS_OBJECT_NAME_COLLISION = 0xC0000035L

    // The dialect actually negotiated with the current client (used by VALIDATE_NEGOTIATE_INFO)
    @Volatile private var negotiatedDialect: Int = Smb2Constants.SMB2_DIALECT_2_1

    // Sessions keyed by sessionId
    private val sessions = ConcurrentHashMap<Long, Smb2Session>()
    private val sessionIdGen = AtomicLong(1)

    // Stable server GUID for this instance
    private val serverGuid: ByteArray = run {
        val uuid = UUID.randomUUID()
        ByteBuffer.wrap(ByteArray(16)).also {
            it.putLong(uuid.mostSignificantBits)
            it.putLong(uuid.leastSignificantBits)
        }.array()
    }

    val shareName: String get() = config.shareName.ifBlank { "share" }

    // ─────────────────────────────────────────────────────────────────────────
    // Dispatch incoming command
    // ─────────────────────────────────────────────────────────────────────────
    fun handleRequest(header: Smb2Header, body: ByteArray): ByteArray {
        return when (header.command) {
            Smb2Constants.CMD_NEGOTIATE       -> handleNegotiate(header, body)
            Smb2Constants.CMD_SESSION_SETUP   -> handleSessionSetup(header, body)
            Smb2Constants.CMD_LOGOFF          -> handleLogoff(header, body)
            Smb2Constants.CMD_TREE_CONNECT    -> handleTreeConnect(header, body)
            Smb2Constants.CMD_TREE_DISCONNECT -> handleTreeDisconnect(header, body)
            Smb2Constants.CMD_CREATE          -> handleCreate(header, body)
            Smb2Constants.CMD_CLOSE           -> handleClose(header, body)
            Smb2Constants.CMD_FLUSH           -> handleFlush(header, body)
            Smb2Constants.CMD_READ            -> handleRead(header, body)
            Smb2Constants.CMD_WRITE           -> handleWrite(header, body)
            Smb2Constants.CMD_QUERY_DIRECTORY -> handleQueryDirectory(header, body)
            Smb2Constants.CMD_QUERY_INFO      -> handleQueryInfo(header, body)
            Smb2Constants.CMD_SET_INFO        -> handleSetInfo(header, body)
            Smb2Constants.CMD_IOCTL           -> handleIoctl(header, body)
            Smb2Constants.CMD_ECHO            -> handleEcho(header, body)
            else -> {
                Log.w(TAG, "Unhandled cmd: 0x${header.command.toInt().and(0xFFFF).toString(16)}")
                errorResponse(header, Smb2Constants.STATUS_NOT_IMPLEMENTED)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 NEGOTIATE
    // Fixed body: 36 bytes + variable dialects
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleNegotiate(header: Smb2Header, body: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 36) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()   // StructureSize = 36
        val dialectCount = buf.getShort().toInt() and 0xFFFF
        buf.getShort()   // SecurityMode
        buf.getShort()   // Reserved
        buf.getInt()     // Capabilities
        buf.get(ByteArray(16))  // ClientGuid

        // Skip client-start-time / negotiate context (we handle only the dialect list)
        buf.getInt()     // ClientStartTime or NegotiateContextOffset

        val dialects = (0 until dialectCount).map {
            if (buf.remaining() >= 2) buf.getShort().toInt() and 0xFFFF else 0
        }
        Log.d(TAG, "NEGOTIATE dialects: ${dialects.map { "0x${it.toString(16)}" }}")

        val dialect = when {
            dialects.contains(Smb2Constants.SMB2_DIALECT_3_1_1) -> Smb2Constants.SMB2_DIALECT_3_1_1
            dialects.contains(Smb2Constants.SMB2_DIALECT_3_0_2) -> Smb2Constants.SMB2_DIALECT_3_0_2
            dialects.contains(Smb2Constants.SMB2_DIALECT_3_0)   -> Smb2Constants.SMB2_DIALECT_3_0
            dialects.contains(Smb2Constants.SMB2_DIALECT_2_1)   -> Smb2Constants.SMB2_DIALECT_2_1
            else                                                  -> Smb2Constants.SMB2_DIALECT_2_0_2
        }

        negotiatedDialect = dialect
        val secBlob = NtlmHandler.buildSpnegoNegotiateToken()

        // Response fixed body = 64 bytes, then secBlob
        val respBody = ByteBuffer.allocate(64 + secBlob.size).order(ByteOrder.LITTLE_ENDIAN)
        respBody.putShort(65)                                     // StructureSize (spec: 65)
        respBody.putShort(Smb2Constants.NEGOTIATE_SIGNING_ENABLED) // SecurityMode
        respBody.putShort(dialect.toShort())                      // DialectRevision
        respBody.putShort(0)                                      // NegotiateContextCount / Reserved
        respBody.put(serverGuid)                                  // ServerGuid (16 bytes)
        respBody.putInt(Smb2Constants.CAP_LARGE_MTU)              // Capabilities
        respBody.putInt(Smb2Constants.MAX_TRANS_SIZE)             // MaxTransactSize
        respBody.putInt(Smb2Constants.MAX_READ_SIZE)              // MaxReadSize
        respBody.putInt(Smb2Constants.MAX_WRITE_SIZE)             // MaxWriteSize
        respBody.putLong(Smb2Constants.toWindowsTime(System.currentTimeMillis())) // SystemTime
        respBody.putLong(Smb2Constants.toWindowsTime(System.currentTimeMillis())) // ServerStartTime
        // SecurityBufferOffset = header(64) + this fixed body(64) = 128
        respBody.putShort((Smb2Constants.SMB2_HEADER_SIZE + 64).toShort())
        respBody.putShort(secBlob.size.toShort())                 // SecurityBufferLength
        respBody.putInt(0)                                        // Reserved2
        respBody.put(secBlob)

        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + respBody.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 SESSION SETUP
    // Fixed body: 24 bytes
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleSessionSetup(header: Smb2Header, body: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 24) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()          // StructureSize = 25
        buf.get()               // Flags
        buf.get()               // SecurityMode
        buf.getInt()            // Capabilities
        buf.getInt()            // Channel
        val secBlobOffset = buf.getShort().toInt() and 0xFFFF
        val secBlobLen    = buf.getShort().toInt() and 0xFFFF
        buf.getLong()           // PreviousSessionId

        // Extract security blob from body (offset is from message start)
        val blobIdx = secBlobOffset - Smb2Constants.SMB2_HEADER_SIZE
        val blob = extractBytes(body, blobIdx, secBlobLen)

        val sessionId = if (header.sessionId != 0L) header.sessionId
                        else sessionIdGen.getAndIncrement()

        // ── Anonymous ─────────────────────────────────────────────────────
        if (blob.isEmpty() || NtlmHandler.isAnonymous(blob)) {
            if (config.requireAuth) return errorResponse(header, Smb2Constants.STATUS_LOGON_FAILURE)
            sessions[sessionId] = Smb2Session(sessionId, "", isAnonymous = true)
            return sessionSetupOk(header, sessionId)
        }

        // ── NTLM Negotiate (Type 1) ───────────────────────────────────────
        if (NtlmHandler.isNtlmNegotiate(blob)) {
            val challenge = NtlmHandler.generateChallenge()
            val session = sessions.getOrPut(sessionId) {
                Smb2Session(sessionId, "", isAnonymous = false)
            }
            session.ntlmChallenge = challenge
            session.awaitingNtlmAuthenticate = true

            val challengeBlob = NtlmHandler.buildNtlmChallenge(
                config.serverName.ifBlank { "ANDROID" }, challenge
            )
            return sessionSetupChallenge(header, sessionId, challengeBlob)
        }

        // ── NTLM Authenticate (Type 3) ────────────────────────────────────
        if (NtlmHandler.isNtlmAuthenticate(blob)) {
            val session = sessions[sessionId]
            val challenge = session?.ntlmChallenge
            if (session == null || challenge == null) {
                return errorResponse(header, Smb2Constants.STATUS_LOGON_FAILURE)
            }

            val authData = NtlmHandler.parseNtlmAuthenticate(blob)
            if (authData == null) {
                sessions.remove(sessionId); return errorResponse(header, Smb2Constants.STATUS_LOGON_FAILURE)
            }

            Log.d(TAG, "NTLM auth: user='${authData.username}' domain='${authData.domain}'")
            val userOk = authData.username.equals(config.username.trim(), ignoreCase = true)
            val passOk = NtlmHandler.verifyNtlmv2(
                authData.username, authData.domain,
                config.password, challenge, authData.ntResponse
            )

            if (!userOk || !passOk) {
                sessions.remove(sessionId); return errorResponse(header, Smb2Constants.STATUS_LOGON_FAILURE)
            }

            session.awaitingNtlmAuthenticate = false
            return sessionSetupOk(header, sessionId, NtlmHandler.buildSpnegoAuthSuccess())
        }

        // ── Unknown blob — allow if no-auth required ──────────────────────
        if (!config.requireAuth) {
            sessions[sessionId] = Smb2Session(sessionId, "", isAnonymous = true)
            return sessionSetupOk(header, sessionId)
        }
        return errorResponse(header, Smb2Constants.STATUS_LOGON_FAILURE)
    }

    private fun sessionSetupChallenge(header: Smb2Header, sid: Long, blob: ByteArray): ByteArray {
        // Body: StructureSize(2) + SessionFlags(2) + SecBufOffset(2) + SecBufLen(2) = 8 bytes
        val secBufOffset = Smb2Constants.SMB2_HEADER_SIZE + 8
        val body = ByteBuffer.allocate(8 + blob.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(9)                          // StructureSize
        body.putShort(0)                          // SessionFlags
        body.putShort(secBufOffset.toShort())     // SecurityBufferOffset
        body.putShort(blob.size.toShort())        // SecurityBufferLength
        body.put(blob)
        return header.toResponseBytes(Smb2Constants.STATUS_MORE_PROCESSING, responseSessionId = sid) + body.array()
    }

    private fun sessionSetupOk(header: Smb2Header, sid: Long, blob: ByteArray = ByteArray(0)): ByteArray {
        val secBufOffset = if (blob.isEmpty()) 0 else Smb2Constants.SMB2_HEADER_SIZE + 8
        val body = ByteBuffer.allocate(8 + blob.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(9)
        body.putShort(0)
        body.putShort(secBufOffset.toShort())
        body.putShort(blob.size.toShort())
        if (blob.isNotEmpty()) body.put(blob)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS, responseSessionId = sid) + body.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 LOGOFF
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleLogoff(header: Smb2Header, body: ByteArray): ByteArray {
        sessions.remove(header.sessionId)?.close()
        val resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(4); resp.putShort(0)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 TREE CONNECT
    // Fixed body: 8 bytes
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleTreeConnect(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 8) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()   // StructureSize = 9
        buf.getShort()   // Reserved
        val pathOffset = buf.getShort().toInt() and 0xFFFF
        val pathLen    = buf.getShort().toInt() and 0xFFFF

        val pathIdx = pathOffset - Smb2Constants.SMB2_HEADER_SIZE
        val path = extractUtf16(body, pathIdx, pathLen)
        Log.d(TAG, "TREE_CONNECT: '$path'")

        val treeId = session.addTree(fileSystem.rootDir)

        val resp = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(16)                             // StructureSize
        resp.put(Smb2Constants.SHARE_TYPE_DISK)      // ShareType
        resp.put(0)                                   // Reserved
        resp.putInt(0)                                // ShareFlags
        resp.putInt(0)                                // Capabilities
        resp.putInt(if (config.readOnly) 0x00120089 else 0x001F01FF)  // MaximalAccess

        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS, responseTreeId = treeId) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 TREE DISCONNECT
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleTreeDisconnect(header: Smb2Header, body: ByteArray): ByteArray {
        sessions[header.sessionId]?.removeTree(header.treeId)
        val resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(4); resp.putShort(0)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 CREATE (open file or directory)
    // Fixed body: 56 bytes + variable Buffer
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleCreate(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)
        val shareRoot = session.getTree(header.treeId)
            ?: return errorResponse(header, Smb2Constants.STATUS_NETWORK_NAME_DELETED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 56) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()           // StructureSize = 57
        buf.get()                // SecurityFlags
        buf.get()                // RequestedOplockLevel
        buf.getInt()             // ImpersonationLevel
        buf.getLong()            // SmbCreateFlags
        buf.getLong()            // Reserved
        val desiredAccess     = buf.getInt()
        buf.getInt()             // FileAttributes
        buf.getInt()             // ShareAccess
        val createDisposition = buf.getInt()
        val createOptions     = buf.getInt()
        val nameOffset = buf.getShort().toInt() and 0xFFFF
        val nameLen    = buf.getShort().toInt() and 0xFFFF
        // CreateContextsOffset and CreateContextsLength intentionally skipped

        val nameIdx  = nameOffset - Smb2Constants.SMB2_HEADER_SIZE
        val namePath = if (nameLen > 0) extractUtf16(body, nameIdx, nameLen) else ""
        Log.d(TAG, "CREATE: '$namePath' disp=$createDisposition opts=0x${createOptions.toString(16)}")

        val isDirectoryRequest = (createOptions and Smb2Constants.FILE_DIRECTORY_FILE) != 0

        // Security: deny write if read-only
        val isWriteAccess = (desiredAccess and (
            Smb2Constants.FILE_WRITE_DATA or Smb2Constants.FILE_APPEND_DATA or
            Smb2Constants.GENERIC_WRITE   or Smb2Constants.GENERIC_ALL      or
            Smb2Constants.DELETE
        )) != 0
        if (isWriteAccess && config.readOnly) {
            return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)
        }

        val file = try { fileSystem.resolve(namePath) }
        catch (e: SecurityException) { return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED) }

        var createAction = Smb2Constants.FILE_OPENED

        when (createDisposition) {
            Smb2Constants.FILE_OPEN -> {
                if (!file.exists()) return errorResponse(header, Smb2Constants.STATUS_OBJECT_NAME_NOT_FOUND)
            }
            Smb2Constants.FILE_OPEN_IF -> {
                if (!file.exists()) {
                    if (isDirectoryRequest) fileSystem.createDirectory(file)
                    else fileSystem.createFile(file)
                    createAction = Smb2Constants.FILE_CREATED
                }
            }
            Smb2Constants.FILE_CREATE -> {
                if (file.exists()) return errorResponse(header, STATUS_OBJECT_NAME_COLLISION)
                if (isDirectoryRequest) fileSystem.createDirectory(file) else fileSystem.createFile(file)
                createAction = Smb2Constants.FILE_CREATED
            }
            Smb2Constants.FILE_OVERWRITE -> {
                if (!file.exists()) return errorResponse(header, Smb2Constants.STATUS_OBJECT_NAME_NOT_FOUND)
                if (!file.isDirectory) file.writeBytes(ByteArray(0))
                createAction = Smb2Constants.FILE_OVERWRITTEN
            }
            Smb2Constants.FILE_OVERWRITE_IF -> {
                if (!file.exists()) {
                    fileSystem.createFile(file); createAction = Smb2Constants.FILE_CREATED
                } else {
                    if (!file.isDirectory) file.writeBytes(ByteArray(0))
                    createAction = Smb2Constants.FILE_OVERWRITTEN
                }
            }
            Smb2Constants.FILE_SUPERSEDE -> {
                if (file.exists()) { file.delete(); createAction = Smb2Constants.FILE_SUPERSEDED }
                fileSystem.createFile(file)
            }
        }

        val isDir    = file.isDirectory
        val handleId = session.createHandle(file, isDir, desiredAccess)
        val info     = fileSystem.getFileInfo(file) ?: emptyFileInfo(file)

        // CREATE response body: 88 bytes fixed
        val resp = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(89)                   // StructureSize (spec says 89)
        resp.put(0)                         // OplockLevel = NONE
        resp.put(0)                         // Flags
        resp.putInt(createAction)           // CreateAction
        resp.putLong(info.createdTime)      // CreationTime
        resp.putLong(info.accessTime)       // LastAccessTime
        resp.putLong(info.modifiedTime)     // LastWriteTime
        resp.putLong(info.changeTime)       // ChangeTime
        resp.putLong(info.size)             // AllocationSize
        resp.putLong(info.size)             // EndOfFile
        resp.putInt(info.attributes)        // FileAttributes
        resp.putInt(0)                      // Reserved2
        resp.putLong(handleId)              // FileId (Persistent)
        resp.putLong(0L)                    // FileId (Volatile)
        resp.putInt(0)                      // CreateContextsOffset
        resp.putInt(0)                      // CreateContextsLength

        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 CLOSE
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleClose(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() >= 24) {
            buf.getShort()        // StructureSize
            buf.getShort()        // Flags
            buf.getInt()          // Reserved
            val fileIdPersistent = buf.getLong()
            session?.closeHandle(fileIdPersistent)
        }

        // Response: 60 bytes (all zeros except StructureSize)
        val resp = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(60)
        repeat(29) { resp.putShort(0) }
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 FLUSH
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleFlush(header: Smb2Header, body: ByteArray): ByteArray {
        val resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(4); resp.putShort(0)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 READ
    // Fixed body: 48 bytes (StructureSize=49, includes 1 padding byte in spec)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleRead(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 48) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()                  // StructureSize = 49
        buf.get()                       // Padding
        buf.get()                       // Flags
        val length  = buf.getInt()
        val offset  = buf.getLong()
        val fileIdP = buf.getLong()
        buf.getLong()                   // FileId (Volatile)
        buf.getInt()                    // MinimumCount
        buf.getInt()                    // Channel
        buf.getInt()                    // RemainingBytes

        val handle = session.getHandle(fileIdP)
            ?: return errorResponse(header, Smb2Constants.STATUS_INVALID_HANDLE)
        if (handle.isDirectory) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        val data = fileSystem.readFile(handle.file, offset, minOf(length, Smb2Constants.MAX_READ_SIZE))
        if (data.isEmpty() && offset >= handle.file.length()) {
            return errorResponse(header, Smb2Constants.STATUS_END_OF_FILE)
        }

        // READ response body: 16 bytes fixed + data
        // DataOffset (UCHAR) = header(64) + fixed(16) = 80
        val dataOffset: Byte = (Smb2Constants.SMB2_HEADER_SIZE + 16).toByte()
        val resp = ByteBuffer.allocate(16 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(17)                // StructureSize
        resp.put(dataOffset)             // DataOffset (UCHAR)
        resp.put(0)                      // Reserved
        resp.putInt(data.size)           // DataLength
        resp.putInt(0)                   // DataRemaining
        resp.putInt(0)                   // Reserved2
        resp.put(data)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 WRITE
    // Fixed body: 48 bytes (StructureSize=49)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleWrite(header: Smb2Header, body: ByteArray): ByteArray {
        if (config.readOnly) return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 48) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()                  // StructureSize = 49
        val dataOffset = buf.getShort().toInt() and 0xFFFF
        val length     = buf.getInt()
        val fileOffset = buf.getLong()
        val fileIdP    = buf.getLong()
        buf.getLong()                   // FileId (Volatile)
        buf.getInt()                    // Channel
        buf.getInt()                    // RemainingBytes
        buf.getShort()                  // WriteChannelInfoOffset
        buf.getShort()                  // WriteChannelInfoLength
        buf.getInt()                    // Flags

        val handle = session.getHandle(fileIdP)
            ?: return errorResponse(header, Smb2Constants.STATUS_INVALID_HANDLE)

        // dataOffset is from start of SMB2 message → body index = dataOffset - 64
        val dataIdx = dataOffset - Smb2Constants.SMB2_HEADER_SIZE
        val data = extractBytes(body, dataIdx, length)
        val written = fileSystem.writeFile(handle.file, fileOffset, data)

        val resp = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(17)        // StructureSize
        resp.putShort(0)         // Reserved
        resp.putInt(written)     // Count
        resp.putInt(0)           // Remaining
        resp.putShort(0)         // WriteChannelInfoOffset
        resp.putShort(0)         // WriteChannelInfoLength
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 QUERY DIRECTORY
    // Fixed body: 32 bytes (StructureSize=33)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleQueryDirectory(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 32) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()                           // StructureSize = 33
        val fileInfoClass  = buf.get().toInt() and 0xFF
        val queryFlags     = buf.get().toInt() and 0xFF
        buf.getInt()                             // FileIndex
        val fileIdP        = buf.getLong()
        buf.getLong()                            // FileId (Volatile)
        val patternOffset  = buf.getShort().toInt() and 0xFFFF
        val patternLen     = buf.getShort().toInt() and 0xFFFF
        val outputBufLen   = buf.getInt()

        val handle = session.getHandle(fileIdP)
            ?: return errorResponse(header, Smb2Constants.STATUS_INVALID_HANDLE)
        if (!handle.isDirectory)
            return errorResponse(header, Smb2Constants.STATUS_NOT_A_DIRECTORY)

        // patternOffset is from start of SMB2 message → body index = patternOffset - 64
        val patIdx = patternOffset - Smb2Constants.SMB2_HEADER_SIZE
        val pattern = if (patternLen > 0) extractUtf16(body, patIdx, patternLen) else "*"

        val FLAG_RESTART = 0x02
        val FLAG_SINGLE  = 0x04
        val FLAG_REOPEN  = 0x10

        if (handle.firstQuery || (queryFlags and (FLAG_RESTART or FLAG_REOPEN)) != 0) {
            val all = mutableListOf<AndroidFileSystem.FileInfo>()
            val now = Smb2Constants.toWindowsTime(System.currentTimeMillis())
            val mtime = Smb2Constants.toWindowsTime(handle.file.lastModified())
            val dirAttrs = Smb2Constants.FILE_ATTRIBUTE_DIRECTORY
            all += AndroidFileSystem.FileInfo(".", true, 0, mtime, mtime, now, mtime, dirAttrs)
            all += AndroidFileSystem.FileInfo("..", true, 0, mtime, mtime, now, mtime, dirAttrs)
            all += fileSystem.listDirectory(handle.file)
            handle.dirEntries = all
            handle.dirOffset  = 0
            handle.searchPattern = pattern
            handle.firstQuery    = false
        }

        val entries = handle.dirEntries ?: emptyList()
        if (handle.dirOffset >= entries.size) {
            return errorResponse(header, Smb2Constants.STATUS_NO_MORE_FILES)
        }

        // Collect entries into output buffer
        val maxOut = minOf(outputBufLen, Smb2Constants.MAX_READ_SIZE)
        val entryBuffers = mutableListOf<ByteArray>()
        var totalSize = 0

        while (handle.dirOffset < entries.size) {
            val entry = entries[handle.dirOffset]
            if (!matchesPattern(entry.name, handle.searchPattern)) {
                handle.dirOffset++; continue
            }
            val eb = when (fileInfoClass) {
                Smb2Constants.FILE_ID_BOTH_DIR_INFO,   // 37
                Smb2Constants.FILE_ID_FULL_DIR_INFO    // 38
                -> fileSystem.buildFileIdBothDirInfo(entry)
                Smb2Constants.FILE_FULL_DIR_INFO       // 2
                -> fileSystem.buildFileFullDirInfo(entry)
                else -> fileSystem.buildBothDirInfo(entry)  // 3 and fallback
            }
            val aligned = ((eb.size + 7) / 8) * 8
            if (totalSize + aligned > maxOut && entryBuffers.isNotEmpty()) break
            entryBuffers += eb
            totalSize += aligned
            handle.dirOffset++
            if ((queryFlags and FLAG_SINGLE) != 0) break
        }

        if (entryBuffers.isEmpty()) return errorResponse(header, Smb2Constants.STATUS_NO_MORE_FILES)

        // Concatenate with proper NextEntryOffset fixups
        val outBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        var prevPos = 0
        for (i in entryBuffers.indices) {
            val eb = entryBuffers[i]
            val aligned = ((eb.size + 7) / 8) * 8
            val startPos = outBuf.position()
            if (i > 0) {
                // Patch previous entry's NextEntryOffset
                outBuf.putInt(prevPos, startPos - prevPos)
            }
            prevPos = startPos
            outBuf.put(eb)
            // Pad to alignment
            repeat(aligned - eb.size) { outBuf.put(0) }
        }
        // Last entry: NextEntryOffset = 0 (already 0)
        val outData = outBuf.array().copyOf(outBuf.position())

        // Response: StructureSize(2) + OutputBufferOffset(2) + OutputBufferLength(4) = 8 bytes fixed
        val outOffset = Smb2Constants.SMB2_HEADER_SIZE + 8
        val hdr = header.toResponseBytes(Smb2Constants.STATUS_SUCCESS)
        val resp = ByteBuffer.allocate(8 + outData.size).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(9)                      // StructureSize
        resp.putShort(outOffset.toShort())    // OutputBufferOffset
        resp.putInt(outData.size)             // OutputBufferLength
        resp.put(outData)
        return hdr + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 QUERY INFO
    // Fixed body: 40 bytes (StructureSize=41)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleQueryInfo(header: Smb2Header, body: ByteArray): ByteArray {
        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 40) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()                        // StructureSize = 41
        val infoType      = buf.get().toInt() and 0xFF
        val fileInfoClass = buf.get().toInt() and 0xFF
        buf.getInt()                          // OutputBufferLength
        buf.getShort()                        // InputBufferOffset
        buf.getShort()                        // Reserved
        buf.getInt()                          // InputBufferLength
        buf.getInt()                          // AdditionalInformation
        buf.getInt()                          // Flags
        val fileIdP = buf.getLong()
        buf.getLong()                         // FileId (Volatile)

        val handle = session.getHandle(fileIdP)
            ?: return errorResponse(header, Smb2Constants.STATUS_INVALID_HANDLE)
        val info = fileSystem.getFileInfo(handle.file)
            ?: return errorResponse(header, Smb2Constants.STATUS_OBJECT_NAME_NOT_FOUND)

        val data: ByteArray = when (infoType) {
            Smb2Constants.INFO_FILE.toInt() and 0xFF -> buildFileInfo(fileInfoClass, info, handle)
                ?: return errorResponse(header, Smb2Constants.STATUS_NOT_SUPPORTED)
            Smb2Constants.INFO_FILESYSTEM.toInt() and 0xFF -> buildFsInfo(fileInfoClass, handle.file)
                ?: return errorResponse(header, Smb2Constants.STATUS_NOT_SUPPORTED)
            Smb2Constants.INFO_SECURITY.toInt() and 0xFF  -> buildMinimalSD()
            else -> return errorResponse(header, Smb2Constants.STATUS_NOT_SUPPORTED)
        }

        val outOffset = Smb2Constants.SMB2_HEADER_SIZE + 8
        val hdr = header.toResponseBytes(Smb2Constants.STATUS_SUCCESS)
        val resp = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(9)                     // StructureSize
        resp.putShort(outOffset.toShort())   // OutputBufferOffset
        resp.putInt(data.size)               // OutputBufferLength
        resp.put(data)
        return hdr + resp.array()
    }

    private fun buildFileInfo(cls: Int, info: AndroidFileSystem.FileInfo, handle: FileHandle): ByteArray? {
        return when (cls) {
            Smb2Constants.FILE_BASIC_INFO        -> fileSystem.buildFileBasicInfo(info)
            Smb2Constants.FILE_STANDARD_INFO     -> fileSystem.buildFileStandardInfo(info)
            Smb2Constants.FILE_ALL_INFO,
            Smb2Constants.FILE_INTERNAL_INFO     -> fileSystem.buildFileAllInfo(info)
            Smb2Constants.FILE_NETWORK_OPEN_INFO -> fileSystem.buildFileNetworkOpenInfo(info)
            Smb2Constants.FILE_EA_INFO           -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            Smb2Constants.FILE_ACCESS_INFO       -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(handle.accessMask).array()
            Smb2Constants.FILE_ATTRIBUTE_TAG_INFO -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(info.attributes).putInt(0).array()
            Smb2Constants.FILE_STREAM_INFO       -> if (info.isDirectory) ByteArray(0) else buildStreamInfo(info.size)
            else -> null
        }
    }

    private fun buildFsInfo(cls: Int, file: File): ByteArray? {
        return when (cls) {
            Smb2Constants.FS_SIZE_INFO      -> fileSystem.buildFsSizeInfo(file)
            Smb2Constants.FS_FULL_SIZE_INFO -> fileSystem.buildFsFullSizeInfo(file)
            Smb2Constants.FS_VOLUME_INFO    -> fileSystem.buildFsVolumeInfo(shareName)
            Smb2Constants.FS_ATTRIBUTE_INFO -> fileSystem.buildFsAttributeInfo()
            Smb2Constants.FS_DEVICE_INFO    -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(7).putInt(0x20).array()
            else -> null
        }
    }

    private fun buildStreamInfo(size: Long): ByteArray {
        val name = "::${'$'}DATA".toByteArray(StandardCharsets.UTF_16LE)
        val buf = ByteBuffer.allocate(4 + 4 + 8 + 8 + name.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)
        buf.putInt(name.size)
        buf.putLong(size)
        buf.putLong((size + 4095) / 4096 * 4096)
        buf.put(name)
        return buf.array()
    }

    private fun buildMinimalSD(): ByteArray {
        val sd = ByteArray(20)
        sd[0] = 1        // Revision
        sd[2] = 4        // Control: SE_SELF_RELATIVE
        sd[3] = 0x80.toByte()
        return sd
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 SET INFO
    // Fixed body: 32 bytes (StructureSize=33)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleSetInfo(header: Smb2Header, body: ByteArray): ByteArray {
        if (config.readOnly) return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val session = sessions[header.sessionId]
            ?: return errorResponse(header, Smb2Constants.STATUS_ACCESS_DENIED)

        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 32) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)

        buf.getShort()                         // StructureSize = 33
        val infoType      = buf.get()
        val fileInfoClass = buf.get().toInt() and 0xFF
        val bufLen    = buf.getInt()
        val bufOffset = buf.getShort().toInt() and 0xFFFF
        buf.getShort()                         // Reserved
        buf.getInt()                           // AdditionalInformation
        val fileIdP   = buf.getLong()
        buf.getLong()                          // FileId (Volatile)

        val handle = session.getHandle(fileIdP)
            ?: return errorResponse(header, Smb2Constants.STATUS_INVALID_HANDLE)

        // bufOffset is from message start → body index = bufOffset - 64
        val dataIdx = bufOffset - Smb2Constants.SMB2_HEADER_SIZE
        val data = extractBytes(body, dataIdx, bufLen)

        when (fileInfoClass) {
            Smb2Constants.FILE_RENAME_INFO -> handleRename(handle, data)
            Smb2Constants.FILE_END_OF_FILE_INFO -> {
                if (data.size >= 8) {
                    val newLen = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong()
                    runCatching { java.io.RandomAccessFile(handle.file, "rw").use { it.setLength(newLen) } }
                }
            }
            Smb2Constants.FILE_DISPOSITION_INFO -> {
                // Mark for delete-on-close — simplified: do nothing
            }
            Smb2Constants.FILE_BASIC_INFO -> {
                // Could update attributes/timestamps — skip for now
            }
        }

        val resp = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(2)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    private fun handleRename(handle: FileHandle, data: ByteArray) {
        if (data.size < 20) return
        val d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        d.get(); d.get(); d.get(); d.get()   // ReplaceIfExists + 3 reserved
        d.getLong()                           // RootDirectory
        val nameLen = d.getInt()
        if (nameLen > 0 && d.remaining() >= nameLen) {
            val nameBytes = ByteArray(nameLen)
            d.get(nameBytes)
            val newName = String(nameBytes, StandardCharsets.UTF_16LE)
            runCatching {
                val newFile = fileSystem.resolve(newName)
                fileSystem.renameFile(handle.file, newFile)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 IOCTL
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIoctl(header: Smb2Header, body: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 4) return errorResponse(header, Smb2Constants.STATUS_INVALID_PARAMETER)
        buf.getShort()   // StructureSize
        buf.getShort()   // Reserved
        val ctlCode = buf.getInt()
        Log.d(TAG, "IOCTL: 0x${ctlCode.toString(16)}")

        // FSCTL_VALIDATE_NEGOTIATE_INFO
        if (ctlCode == 0x00140204) {
            val outData = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Smb2Constants.CAP_LARGE_MTU)
                .put(serverGuid)
                .putShort(Smb2Constants.NEGOTIATE_SIGNING_ENABLED)
                .putShort(negotiatedDialect.toShort())
                .array()

            val hdr = header.toResponseBytes(Smb2Constants.STATUS_SUCCESS)
            // IOCTL response fixed: 48 bytes (StructureSize=49)
            val outOffset = Smb2Constants.SMB2_HEADER_SIZE + 48
            val resp = ByteBuffer.allocate(48 + outData.size).order(ByteOrder.LITTLE_ENDIAN)
            resp.putShort(49)
            resp.putShort(0)
            resp.putInt(ctlCode)
            resp.putLong(0L); resp.putLong(0L)  // FileId (16 bytes)
            resp.putInt(outOffset)               // InputOffset
            resp.putInt(0)                       // InputCount
            resp.putInt(outOffset)               // OutputOffset
            resp.putInt(outData.size)            // OutputCount
            resp.putInt(0)                       // Flags
            resp.putInt(0)                       // Reserved2
            resp.put(outData)
            return hdr + resp.array()
        }

        return errorResponse(header, Smb2Constants.STATUS_NOT_SUPPORTED)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMB2 ECHO
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleEcho(header: Smb2Header, body: ByteArray): ByteArray {
        val resp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        resp.putShort(4); resp.putShort(0)
        return header.toResponseBytes(Smb2Constants.STATUS_SUCCESS) + resp.array()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun errorResponse(header: Smb2Header, status: Long): ByteArray {
        // SMB2 ERROR response: StructureSize(2) + ErrorContextCount(1) + Reserved(1) + ByteCount(4) + ErrorData(1)
        val body = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(9); body.put(0); body.put(0); body.putInt(0); body.put(0)
        return header.toResponseBytes(status) + body.array()
    }

    /** Extract [length] bytes from [data] starting at [bodyIndex]. Returns empty on out-of-bounds. */
    private fun extractBytes(data: ByteArray, bodyIndex: Int, length: Int): ByteArray {
        if (length <= 0 || bodyIndex < 0 || bodyIndex + length > data.size) return ByteArray(0)
        return data.copyOfRange(bodyIndex, bodyIndex + length)
    }

    /** Extract a UTF-16LE string from [data] at [bodyIndex] with [byteLen] bytes. */
    private fun extractUtf16(data: ByteArray, bodyIndex: Int, byteLen: Int): String {
        val bytes = extractBytes(data, bodyIndex, byteLen)
        return if (bytes.isEmpty()) "" else String(bytes, StandardCharsets.UTF_16LE)
    }

    private fun matchesPattern(name: String, pattern: String): Boolean {
        if (pattern == "*" || pattern.isEmpty()) return true
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return name.matches(Regex(regex, RegexOption.IGNORE_CASE))
    }

    private fun emptyFileInfo(file: File): AndroidFileSystem.FileInfo {
        val now = Smb2Constants.toWindowsTime(System.currentTimeMillis())
        return AndroidFileSystem.FileInfo(
            name = file.name, isDirectory = file.isDirectory,
            size = 0L, createdTime = now, modifiedTime = now,
            accessTime = now, changeTime = now,
            attributes = Smb2Constants.FILE_ATTRIBUTE_NORMAL
        )
    }

    fun onConnectionClosed(sessionId: Long) {
        sessions.remove(sessionId)?.close()
    }
}
