package com.pokewallet.crypto

object Bech32 {

    // -------------------------
    // Constantes
    // -------------------------

    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_MAP = CHARSET.withIndex().associate { it.value to it.index }

    private val GENERATOR = intArrayOf(
        0x3b6a57b2,
        0x26508e6d,
        0x1ea119fa,
        0x3d4233dd,
        0x2a1462b3
    )

    // -------------------------
    // Polymod
    // -------------------------

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor v
            for (i in 0..4) {
                if (((top shr i) and 1) != 0) {
                    chk = chk xor GENERATOR[i]
                }
            }
        }
        return chk
    }

    // -------------------------
    // HRP
    // -------------------------

    private fun hrpExpand(hrp: String): IntArray {
        val ret = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            ret[i] = hrp[i].code shr 5
            ret[i + hrp.length + 1] = hrp[i].code and 31
        }
        ret[hrp.length] = 0
        return ret
    }

    // -------------------------
    // Checksum
    // -------------------------

    private fun createChecksum(
        hrp: String,
        data: IntArray,
        spec: Int
    ): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val polymod = polymod(values) xor spec
        return IntArray(6) { i ->
            (polymod shr (5 * (5 - i))) and 31
        }
    }

    private fun verifyChecksum(
        hrp: String,
        data: IntArray
    ): Boolean {
        val pm = polymod(hrpExpand(hrp) + data)
        return pm == BECH32_CONST || pm == BECH32M_CONST
    }

    // -------------------------
    // Encode / Decode (mantidos)
    // -------------------------

    fun encode(hrp: String, data: IntArray): String {
        val checksum = createChecksum(hrp, data, BECH32_CONST)
        val combined = data + checksum
        return buildString {
            append(hrp)
            append('1')
            for (d in combined) append(CHARSET[d])
        }
    }

    fun decode(bech: String): Pair<String, IntArray>? {
        val lower = bech.lowercase()
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return null

        val hrp = lower.substring(0, pos)
        val dataPart = lower.substring(pos + 1)

        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val c = dataPart[i]
            data[i] = CHARSET_MAP[c] ?: return null
        }

        if (!verifyChecksum(hrp, data)) return null
        return hrp to data.copyOfRange(0, data.size - 6)
    }

    // -------------------------
    // SegWit (BIP-173 / 350)
    // -------------------------

    /**
     * witnessVersion:
     *  - 0  → Bech32  (BIP84)
     *  - ≥1 → Bech32m (BIP86 / Taproot)
     */
    fun encodeSegWit(
        hrp: String,
        witnessVersion: Int,
        program: ByteArray
    ): String {
        require(witnessVersion in 0..16)

        when (witnessVersion) {
            0 -> require(program.size == 20 || program.size == 32) {
                "Invalid program length for SegWit v0"
            }
            1 -> require(program.size == 32) {
                "Invalid program length for Taproot"
            }
            else -> require(program.size in 2..40)
        }

        val spec = if (witnessVersion == 0) BECH32_CONST else BECH32M_CONST
        val prog5 = convertBits(program, 8, 5)

        val data = IntArray(1 + prog5.size)
        data[0] = witnessVersion
        prog5.copyInto(data, 1)

        val checksum = createChecksum(hrp, data, spec)
        val combined = data + checksum

        return buildString {
            append(hrp)
            append('1')
            for (d in combined) append(CHARSET[d])
        }
    }

    // -------------------------
    // Bit conversion
    // -------------------------

    fun convertBits(
        data: ByteArray,
        fromBits: Int,
        toBits: Int,
        pad: Boolean = true
    ): IntArray {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            acc = (acc shl fromBits) or (value.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }

        if (pad) {
            if (bits > 0) {
                ret.add((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Invalid padding")
        }

        return ret.toIntArray()
    }
}

