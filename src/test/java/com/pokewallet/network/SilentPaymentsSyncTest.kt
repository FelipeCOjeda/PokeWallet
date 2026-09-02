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
        assertEquals(0L, SilentPaymentsSync.defaultStartHeight(SilentPaymentsSync.DEFAULT_LOOKBACK_BLOCKS - 1))
        assertEquals(0L, SilentPaymentsSync.defaultStartHeight(0L))
    }

    @Test
    fun `resolveStartHeight continua de onde parou quando ha scan anterior, ignorando birthHeight`() {
        assertEquals(
            901L,
            SilentPaymentsSync.resolveStartHeight(previousScanTipHeight = 900L, birthHeight = 500L, oracleTipHeight = 950L)
        )
    }

    @Test
    fun `resolveStartHeight prefere birthHeight - margem no primeiro scan quando conhecida`() {
        assertEquals(
            800_000L - SilentPaymentsSync.BIRTH_HEIGHT_MARGIN_BLOCKS,
            SilentPaymentsSync.resolveStartHeight(previousScanTipHeight = 0L, birthHeight = 800_000L, oracleTipHeight = 900_000L)
        )
    }

    @Test
    fun `resolveStartHeight cai pro lookback fixo quando nao ha scan anterior nem birthHeight`() {
        assertEquals(
            SilentPaymentsSync.defaultStartHeight(900_000L),
            SilentPaymentsSync.resolveStartHeight(previousScanTipHeight = 0L, birthHeight = null, oracleTipHeight = 900_000L)
        )
    }

    @Test
    fun `resolveStartHeight nunca fica negativo mesmo com birthHeight bem baixa`() {
        assertEquals(
            0L,
            SilentPaymentsSync.resolveStartHeight(previousScanTipHeight = 0L, birthHeight = 1L, oracleTipHeight = 900_000L)
        )
    }
}
