package com.martylai.smbserver.server

/**
 * Pure-Kotlin MD4 digest implementation.
 * Required for computing the NT hash of a password (MD4 of Unicode password).
 * Android does not include MD4 in its default security providers.
 *
 * Reference: RFC 1320
 */
object Md4 {

    fun hash(input: ByteArray): ByteArray {
        // Pad the message
        val msgLen = input.size
        val bitLen = msgLen.toLong() * 8

        // Pad to 448 mod 512 bits (56 mod 64 bytes), then append 64-bit length
        val padLen = if (msgLen % 64 < 56) 56 - msgLen % 64 else 120 - msgLen % 64
        val padded = ByteArray(msgLen + padLen + 8)
        input.copyInto(padded)
        padded[msgLen] = 0x80.toByte()
        // Append length in little-endian 64-bit
        var l = bitLen
        for (i in 0..7) {
            padded[msgLen + padLen + i] = (l and 0xFF).toByte()
            l = l ushr 8
        }

        // Initialize state
        var a = 0x67452301.toInt()
        var b = 0xEFCDAB89.toInt()
        var c = 0x98BADCFE.toInt()
        var d = 0x10325476.toInt()

        // Process each 512-bit block
        var i = 0
        while (i < padded.size) {
            val x = IntArray(16) { j ->
                val base = i + j * 4
                (padded[base].toInt() and 0xFF) or
                        ((padded[base + 1].toInt() and 0xFF) shl 8) or
                        ((padded[base + 2].toInt() and 0xFF) shl 16) or
                        ((padded[base + 3].toInt() and 0xFF) shl 24)
            }

            val aa = a; val bb = b; val cc = c; val dd = d

            // Round 1 — F(b,c,d) = (b and c) or (not b and d)
            a = r1(a, b, c, d, x[0],  3)
            d = r1(d, a, b, c, x[1],  7)
            c = r1(c, d, a, b, x[2], 11)
            b = r1(b, c, d, a, x[3], 19)
            a = r1(a, b, c, d, x[4],  3)
            d = r1(d, a, b, c, x[5],  7)
            c = r1(c, d, a, b, x[6], 11)
            b = r1(b, c, d, a, x[7], 19)
            a = r1(a, b, c, d, x[8],  3)
            d = r1(d, a, b, c, x[9],  7)
            c = r1(c, d, a, b, x[10], 11)
            b = r1(b, c, d, a, x[11], 19)
            a = r1(a, b, c, d, x[12],  3)
            d = r1(d, a, b, c, x[13],  7)
            c = r1(c, d, a, b, x[14], 11)
            b = r1(b, c, d, a, x[15], 19)

            // Round 2 — G(b,c,d) = (b and c) or (b and d) or (c and d)
            a = r2(a, b, c, d, x[0],  3)
            d = r2(d, a, b, c, x[4],  5)
            c = r2(c, d, a, b, x[8],  9)
            b = r2(b, c, d, a, x[12], 13)
            a = r2(a, b, c, d, x[1],  3)
            d = r2(d, a, b, c, x[5],  5)
            c = r2(c, d, a, b, x[9],  9)
            b = r2(b, c, d, a, x[13], 13)
            a = r2(a, b, c, d, x[2],  3)
            d = r2(d, a, b, c, x[6],  5)
            c = r2(c, d, a, b, x[10],  9)
            b = r2(b, c, d, a, x[14], 13)
            a = r2(a, b, c, d, x[3],  3)
            d = r2(d, a, b, c, x[7],  5)
            c = r2(c, d, a, b, x[11],  9)
            b = r2(b, c, d, a, x[15], 13)

            // Round 3 — H(b,c,d) = b xor c xor d
            a = r3(a, b, c, d, x[0],  3)
            d = r3(d, a, b, c, x[8],  9)
            c = r3(c, d, a, b, x[4], 11)
            b = r3(b, c, d, a, x[12], 15)
            a = r3(a, b, c, d, x[2],  3)
            d = r3(d, a, b, c, x[10],  9)
            c = r3(c, d, a, b, x[6], 11)
            b = r3(b, c, d, a, x[14], 15)
            a = r3(a, b, c, d, x[1],  3)
            d = r3(d, a, b, c, x[9],  9)
            c = r3(c, d, a, b, x[5], 11)
            b = r3(b, c, d, a, x[13], 15)
            a = r3(a, b, c, d, x[3],  3)
            d = r3(d, a, b, c, x[11],  9)
            c = r3(c, d, a, b, x[7], 11)
            b = r3(b, c, d, a, x[15], 15)

            a += aa; b += bb; c += cc; d += dd
            i += 64
        }

        // Output (little-endian)
        val result = ByteArray(16)
        intToLe(a, result, 0)
        intToLe(b, result, 4)
        intToLe(c, result, 8)
        intToLe(d, result, 12)
        return result
    }

    private fun rotateLeft(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun r1(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b.inv() and d)
        return rotateLeft(a + f + x, s)
    }

    private fun r2(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val g = (b and c) or (b and d) or (c and d)
        return rotateLeft(a + g + x + 0x5A827999.toInt(), s)
    }

    private fun r3(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val h = b xor c xor d
        return rotateLeft(a + h + x + 0x6ED9EBA1.toInt(), s)
    }

    private fun intToLe(v: Int, buf: ByteArray, off: Int) {
        buf[off]     = (v and 0xFF).toByte()
        buf[off + 1] = (v ushr 8  and 0xFF).toByte()
        buf[off + 2] = (v ushr 16 and 0xFF).toByte()
        buf[off + 3] = (v ushr 24 and 0xFF).toByte()
    }
}
