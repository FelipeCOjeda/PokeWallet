package com.pokewallet.crypto

enum class ScriptType {
    BIP84, // bc1q
    BIP86  // bc1p
}

object Derivation {

    fun accountPath(type: ScriptType, account: Int = 0): IntArray {
        val purpose = when (type) {
            ScriptType.BIP84 -> 84
            ScriptType.BIP86 -> 86
        }

        return intArrayOf(
            purpose or HARDENED,
            0 or HARDENED,        // coin type (BTC)
            account or HARDENED  // account
        )
    }

    fun fullPath(
        type: ScriptType,
        account: Int = 0,
        change: Int = 0,
        index: Int = 0
    ): IntArray {
        return accountPath(type, account) + intArrayOf(change, index)
    }

    private const val HARDENED = 0x80000000.toInt()
}
