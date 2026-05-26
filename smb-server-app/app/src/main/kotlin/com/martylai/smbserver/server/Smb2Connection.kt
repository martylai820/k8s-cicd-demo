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
                val message = readMessage(input, output) ?: break
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
     * Handles NetBIOS Session Request (0x81) by replying with a Positive Response
     * (0x82) — this is required by clients such as Cyberduck / SMBJ that send the
     * NBT session establishment step even on non-standard ports.
     *
     * Returns null on EOF or unrecoverable error.
     */
    private fun readMessage(input: InputStream, output: OutputStream): ByteArray? {
        while (true) {
            // Read 4-byte NetBIOS header
            val nbHeader = ByteArray(4)
            if (!readFully(input, nbHeader)) return null

            val msgType = nbHeader[0].toInt() and 0xFF

            // 24-bit big-endian length in bytes 1-3
            val length = ((nbHeader[1].toInt() and 0xFF) shl 16) or
                         ((nbHeader[2].toInt() and 0xFF) shl 8)  or
                         (nbHeader[3].toInt() and 0xFF)

            when (msgType) {
                0x00 -> {  // SESSION MESSAGE — normal SMB2 payload
                    if (length == 0) return ByteArray(0)
                    if (length > 16 * 1024 * 1024) {
                        Log.e(TAG, "Message too large: $length bytes")
                        return null
                    }
                    val data = ByteArray(length)
                    if (!readFully(input, data)) return null
                    return data
                }
                0x81 -> {  // SESSION REQUEST — consume name fields, reply with positive ack
                    // Must drain ALL 'length' bytes to keep the stream in sync.
                    // Using coerceAtMost(512) left the excess bytes in the stream and
                    // caused the next header read to misparse, breaking the connection.
                    var remaining = length
                    while (remaining > 0) {
                        val chunk = ByteArray(minOf(remaining, 4096))
                        if (!readFully(input, chunk)) return null
                        remaining -= chunk.size
                    }
                    Log.d(TAG, "NetBIOS SESSION REQUEST — sending POSITIVE RESPONSE")
                    output.write(byteArrayOf(0x82.toByte(), 0x00, 0x00, 0x00))
                    output.flush()
                    // Loop back to read the actual SMBv2 NEGOTIATE
                }
                0x85 -> {  // SESSION KEEPALIVE — reply in kind and continue
                    output.write(byteArrayOf(0x85.toByte(), 0x00, 0x00, 0x00))
                    output.flush()
                }
                else -> {
                    Log.w(TAG, "Unexpected NetBIOS msg type: 0x${msgType.toString(16)}, skipping $length bytes")
                    if (length > 0) {
                        val skip = ByteArray(length.coerceAtMost(16 * 1024 * 1024))
                        if (!readFully(input, skip)) return null
                    }
                    // Loop and try the next framed message
                }
            }
        }
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
