package com.pokewallet.network

import com.pokewallet.crypto.KeyDerivation
import com.pokewallet.crypto.Network
import com.pokewallet.crypto.SeedDerivation
import com.pokewallet.crypto.SpendType
import com.pokewallet.crypto.ReceiveAddressService
import com.pokewallet.crypto.XpubEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Investigando relato de "saldo existe on-chain (endereço antigo, poucos
 * endereços usados, confirmado) mas o app não reconhece — a MESMA xpub
 * encontra na BlueWallet em segundos". Não é gap limit (poucos endereços
 * usados) nem rede/API (testado antes). Suspeita: o endereço MOSTRADO pro
 * usuário na tela Receber (derivado via ReceiveAddressService, caminho da
 * chave PRIVADA) não bate com o endereço que o SCANNER verifica (derivado
 * via XpubAddressDeriver, caminho da chave PÚBLICA a partir do xpub
 * exportado) — os dois deveriam sempre produzir o MESMO endereço pro
 * mesmo chain/index, senão o app fica cego pros próprios endereços que
 * ele mesmo gerou.
 *
 * Teste autocontido: usa só código do próprio projeto (nenhum vetor
 * externo digitado de memória) — deriva os dois caminhos a partir da
 * MESMA seed e confere que batem.
 */
class XpubVsSeedDerivationConsistencyTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun `endereco mostrado no Receber bate com o que o scanner encontraria via xpub (BIP84, index 0)`() {
        val seed = SeedDerivation.fromMnemonic(testMnemonic, "")
        val network = Network.MAINNET

        val accountKey = KeyDerivation.derive(
            seed,
            intArrayOf(KeyDerivation.hardened(84), KeyDerivation.hardened(network.coinType), KeyDerivation.hardened(0))
        )
        val xpub = XpubEncoder.encode(accountKey, network)

        val addressViaSeed = ReceiveAddressService.addressAt(seed, SpendType.BIP84, network, index = 0)
        val addressViaXpub = XpubAddressDeriver.p2wpkhAddress(xpub, chain = 0, index = 0, network = network)

        assertEquals(
            "Endereço mostrado no Receber (via seed) DIFERENTE do que o scanner encontraria (via xpub) — bug real, fundos ficam invisíveis pro próprio app.",
            addressViaSeed, addressViaXpub
        )
    }

    @Test
    fun `mesma consistencia no indice 3 (poucos enderecos usados, cenario relatado)`() {
        val seed = SeedDerivation.fromMnemonic(testMnemonic, "")
        val network = Network.MAINNET

        val accountKey = KeyDerivation.derive(
            seed,
            intArrayOf(KeyDerivation.hardened(84), KeyDerivation.hardened(network.coinType), KeyDerivation.hardened(0))
        )
        val xpub = XpubEncoder.encode(accountKey, network)

        val addressViaSeed = ReceiveAddressService.addressAt(seed, SpendType.BIP84, network, index = 3)
        val addressViaXpub = XpubAddressDeriver.p2wpkhAddress(xpub, chain = 0, index = 3, network = network)

        assertEquals(addressViaSeed, addressViaXpub)
    }
}
