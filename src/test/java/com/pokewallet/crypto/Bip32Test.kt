package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vetor de teste OFICIAL nº1 do BIP32 (bitcoin/bips, bip-0032.mediawiki),
 * baixado direto do GitHub — valida Bip32.fromSeed/deriveChild +
 * KeyDerivation.derive + ExtendedKeySerializer ponta a ponta contra os
 * xpub conhecidos da spec, incluindo derivação hardened e não-hardened
 * misturadas e um índice de endereço grande (1000000000).
 */
class Bip32Test {

    private val seed = "000102030405060708090a0b0c0d0e0f".hexToBytes()

    private fun xpubAt(path: IntArray): String =
        ExtendedKeySerializer.serializeXpub(KeyDerivation.derive(seed, path))

    @Test
    fun chainM() {
        assertEquals(
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            xpubAt(intArrayOf())
        )
    }

    @Test
    fun chainM0Hardened() {
        assertEquals(
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw",
            xpubAt(intArrayOf(KeyDerivation.hardened(0)))
        )
    }

    @Test
    fun chainM0Hardened1() {
        assertEquals(
            "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ",
            xpubAt(intArrayOf(KeyDerivation.hardened(0), 1))
        )
    }

    @Test
    fun chainM0Hardened1_2Hardened_2() {
        assertEquals(
            "xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV",
            xpubAt(intArrayOf(KeyDerivation.hardened(0), 1, KeyDerivation.hardened(2), 2))
        )
    }

    @Test
    fun chainM0Hardened1_2Hardened_2_bigIndex() {
        assertEquals(
            "xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy",
            xpubAt(intArrayOf(KeyDerivation.hardened(0), 1, KeyDerivation.hardened(2), 2, 1000000000))
        )
    }
}
