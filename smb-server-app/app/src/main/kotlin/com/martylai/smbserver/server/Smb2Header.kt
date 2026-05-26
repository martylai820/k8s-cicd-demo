package com.martylai.smbserver.server

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents an SMB2 message header (64 bytes, little-endian).
 *
 * Layout (MS-SMB2 §2.2.1):
 *  0  -  3  : ProtocolId      = 0xFE 0x53 0x4D 0x42
 *  4  -  5  : StructureSize   = 64
 *  6  -  7  : CreditCharge
 *  8  - 11  : Status (responses) / ChannelSequence+Reserved (requests SMB3.1.1)
 * 12  - 13  : Command
 * 14  - 15  : Credits (request: requested; response: granted)
 * 16  - 19  : Flags
 * 20  - 23  : NextCommand
 * 24  - 31  : MessageId
 * 32  - 35  : Reserved (sync) / AsyncId.High (async)
 * 36  - 39  : TreeId  (sync) / AsyncId.Low  (async)
 * 40  - 47  : SessionId
 * 48  - 63  : Signature (16 bytes)
 */
data class Smb2Header(
    val creditCharge: Short = 0,
    val status: Long = 0L,          // NTSTATUS (as unsigned 32-bit stored in Long)
    val command: Short = 0,
    val credits: Short = 1,
    val flags: Int = 0,
    val nextCommand: Int = 0,
    val messageId: Long = 0L,
    val treeId: Int = 0,
    val sessionId: Long = 0L,
    val signature: ByteArray = ByteArray(16)
) {
    companion object {
        fun fromBuffer(buf: ByteBuffer): Smb2Header? {
            if (buf.remaining() < Smb2Constants.SMB2_HEADER_SIZE) return null

            val savedOrder = buf.order()
            buf.order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.getInt()   // 0-3
            if (magic != Smb2Constants.SMB2_MAGIC) {
                buf.order(savedOrder)
                return null
            }
            val structSize = buf.getShort()  // 4-5
            val creditCharge = buf.getShort() // 6-7
            val statusRaw = buf.getInt()      // 8-11 (NTSTATUS, treat as unsigned)
            val command = buf.getShort()      // 12-13
            val credits = buf.getShort()      // 14-15
            val flags = buf.getInt()          // 16-19
            val nextCmd = buf.getInt()        // 20-23
            val msgId = buf.getLong()         // 24-31
            buf.getInt()                      // 32-35 reserved / async
            val treeId = buf.getInt()         // 36-39
            val sessionId = buf.getLong()     // 40-47
            val sig = ByteArray(16)
            buf.get(sig)                      // 48-63

            buf.order(savedOrder)
            return Smb2Header(
                creditCharge = creditCharge,
                status = Integer.toUnsignedLong(statusRaw),
                command = command,
                credits = credits,
                flags = flags,
                nextCommand = nextCmd,
                messageId = msgId,
                treeId = treeId,
                sessionId = sessionId,
                signature = sig
            )
        }
    }

    /**
     * Build the response header bytes.
     * Flips the SERVER_TO_REDIR flag and copies MessageId / SessionId / TreeId.
     */
    fun toResponseBytes(
        responseStatus: Long,
        responseCommand: Short = command,
        responseCredits: Short = 1,
        responseFlags: Int = flags or Smb2Constants.FLAGS_SERVER_TO_REDIR,
        responseTreeId: Int = treeId,
        responseSessionId: Long = sessionId
    ): ByteArray {
        val buf = ByteBuffer.allocate(Smb2Constants.SMB2_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(Smb2Constants.SMB2_MAGIC)          // 0-3
        buf.putShort(64)                                // 4-5  StructureSize
        buf.putShort(0)                                 // 6-7  CreditCharge
        buf.putInt(responseStatus.toInt())              // 8-11 Status
        buf.putShort(responseCommand)                   // 12-13 Command
        buf.putShort(responseCredits)                   // 14-15 Credits
        buf.putInt(responseFlags)                       // 16-19 Flags
        buf.putInt(0)                                   // 20-23 NextCommand
        buf.putLong(messageId)                          // 24-31 MessageId (echo client's)
        buf.putInt(0)                                   // 32-35 Reserved
        buf.putInt(responseTreeId)                      // 36-39 TreeId
        buf.putLong(responseSessionId)                  // 40-47 SessionId
        buf.put(ByteArray(16))                          // 48-63 Signature (unsigned)
        return buf.array()
    }

    val isResponse: Boolean
        get() = (flags and Smb2Constants.FLAGS_SERVER_TO_REDIR) != 0
}
