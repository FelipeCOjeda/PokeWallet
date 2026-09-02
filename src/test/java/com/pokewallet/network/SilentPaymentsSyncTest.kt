package com.pokewallet.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** Só [SilentPaymentsSync.defaultStartHeight] é testável sem rede real —
 *  [SilentPaymentsSync.sync]/[SilentPaymentsSync.scanRange] dependem do
 *  oracle de verdade, validados ao vivo (mesmo padrão de
 *  BlindBitOracleClient, ver histórico da sessão). */
class SilentPaymentsSyncTest {

    @Test
    fun `defaultStartHeight volta DEFAULT_LOOKBACK_BLOCKS do tip`() {
        assertEquals(
            900_000L - SilentPaymentsSync.DEFAULT_LOOKBACK_BLOCKS,
            SilentPaymentsSync.defaultStartHeight(900_000L)
        )
    }

    @Test
    fun `defaultStartHeight nunca fica negativo pra chains muito curtas`() {
        assertEquals(0L, SilentPaymentsSync.defaultStartHeight(500L))
        assertEquals(0L, SilentPaymentsSync.defaultStartHeight(0L))
    }
}
