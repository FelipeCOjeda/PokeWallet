package com.pokewallet.android

import com.pokewallet.crypto.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Só [BlindBitOraclePrefs.defaultHostFor] é testável em JVM pura — os
 * outros métodos dependem de android.content.Context/SharedPreferences,
 * que lançam "not mocked" fora de um device/Robolectric (mesma limitação
 * documentada em WalletStorage/NodePrefs, sem teste de unidade aqui).
 */
class BlindBitOraclePrefsTest {

    @Test
    fun `mainnet e signet tem host publico padrao, regtest nao tem`() {
        assertEquals(BlindBitOraclePrefs.DEFAULT_HOST_MAINNET, BlindBitOraclePrefs.defaultHostFor(Network.MAINNET))
        assertEquals(BlindBitOraclePrefs.DEFAULT_HOST_SIGNET, BlindBitOraclePrefs.defaultHostFor(Network.TESTNET))
        assertNull(BlindBitOraclePrefs.defaultHostFor(Network.REGTEST))
    }
}
