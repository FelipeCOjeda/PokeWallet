package com.pokewallet.nostr

import com.pokewallet.crypto.KeyDerivation
import com.pokewallet.crypto.Secp256k1

/**
 * Deriva a identidade Nostr (NIP-06) a partir da mesma seed BIP39 da
 * wallet — sem persistir nenhuma chave nova. A mesma seed sempre
 * produz a mesma identidade Nostr, então restaurar a wallet pelo
 * mnemonic também restaura essa identidade automaticamente.
 *
 * Path: m/44'/1237'/0'/0/0
 */
object NostrKeys {

    private const val NOSTR_COIN_TYPE = 1237

    /** @return par (chave privada 32 bytes, chave pública x-only 32 bytes) */
    fun deriveFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val key = KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(44),
                KeyDerivation.hardened(NOSTR_COIN_TYPE),
                KeyDerivation.hardened(0),
                0,
                0
            )
        )
        val pubKey = Secp256k1.xOnlyPublicKeyFromPrivate(key.privateKey)
        return key.privateKey to pubKey
    }
}
