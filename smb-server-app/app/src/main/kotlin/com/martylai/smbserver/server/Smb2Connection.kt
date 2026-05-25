package com.martylai.smbserver.server

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles a single TCP client connection.
 *
 * SMB2-over-TCP uses a 4-byte NetBIOS Session Service framing:
 *   Byte 0    : Message type (0x00 = SESSION MESSAGE)
 *   Bytes 1-3 : Payload length (big-endian, 24-bit)
 */
class Smb2Connection(
    private val socket: Socket,
    private val handler: Smb2RequestHandler
) : Runnable {

    private val TAG = "Smb2Connection"
    private var sessionId: Long = 0L

    override fun run() {
        val addr = socket.remoteSocketAddress
        Log.i(TAG, "Client connected: $addr")
        try {
            socket.soTimeout = 60_000  // 60s read timeout
            val input  = socket.getInputStream()
            val output = socket.getOutputStream()

            while (!socket.isClosed) {
                val message = readMessage(input) ?: break
                val responses = processMessage(message)
                for (resp in responses) {
                    writeMessage(output, resp)
                }
            }
        } catch (e: IOException) {
            if (!socket.isClosed) Log.d(TAG, "Connection closed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
        } finally {
            if (sessionId != 0L) handler.onConnectionClosed(sessionId)
            runCatching { socket.close() }
            Log.i(TAG, "Client disconnected: $addr")
        }
    }

    /**
     * Read one NetBIOS-framed SMB2 message from the input stream.
     * Returns null on EOF or error.
     */
    private fun readMessage(input: InputStream): ByteArray? {
        // Read 4-byte NetBIOS header
        val nbHeader = ByteArray(4)
        if (!readFully(input, nbHeader)) return null

        val msgType = nbHeader[0].toInt() and 0xFF
        if (msgType != 0x00) {
            Log.w(TAG, "Non-session message type: 0x${msgType.toString(16)}")
            // Still try to read the length and skip
        }

        // 24-bit big-endian length in bytes 1-3
        val length = ((nbHeader[1].toInt() and 0xFF) shl 16) or
                     ((nbHeader[2].toInt() and 0xFF) shl 8)  or
                     (nbHeader[3].toInt() and 0xFF)

        if (length == 0) return ByteArray(0)
        if (length > 16 * 1024 * 1024) {  // Max 16 MB sanity check
            Log.e(TAG, "Message too large: $length bytes")
            return null
        }

        val data = ByteArray(length)
        if (!readFully(input, data)) return null
        return data
    }

    /**
     * Parse SMB2 message(s) (supporting compound requests) and dispatch.
     */
    private fun processMessage(data: ByteArray): List<ByteArray> {
        val results = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < data.size) {
            if (data.size - offset < Smb2Constants.SMB2_HEADER_SIZE) break

            val buf = ByteBuffer.wrap(data, offset, data.size - offset)
                .order(ByteOrder.LITTLE_ENDIAN)
            val header = Smb2Header.fromBuffer(buf) ?: break

            // Determine body size (NextCommand or rest of buffer)
            val bodyStart = offset + Smb2Constants.SMB2_HEADER_SIZE
            val nextOff = if (header.nextCommand > 0) header.nextCommand else (data.size - offset)
            val bodyEnd = (offset + nextOff).coerceAtMost(data.size)
            val body = data.copyOfRange(bodyStart, bodyEnd)

            // Track session ID from first request that has one
            if (header.sessionId != 0L && sessionId == 0L) {
                sessionId = header.sessionId
            }

            val response = handler.handleRequest(header, body)
            results.add(response)

            if (header.nextCommand == 0) break
            offset += header.nextCommand
        }

        return results
    }

    /**
     * Write one NetBIOS-framed SMB2 message to the output stream.
     */
    private fun writeMessage(output: OutputStream, data: ByteArray) {
        val len = data.size
        val nbHeader = byteArrayOf(
            0x00,                          // SESSION MESSAGE
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
        output.write(nbHeader)
        output.write(data)
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
