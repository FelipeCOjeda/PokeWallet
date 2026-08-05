package com.pokewallet.crypto

import org.json.JSONObject
import java.time.Instant

/**
 * Importa uma carteira watch-only (só xpub, sem seed) a partir da string
 * "accountOrigin" que a própria WalletInit/WalletRestore já exportam —
 * "[fingerprintHex/purpose'/coin'/0']xpub", a mesma que fica embutida nos
 * descriptors. Isso evita o usuário ter que digitar um fingerprint na mão:
 * o único fingerprint verdadeiro (o da chave MESTRA) só existe no
 * dispositivo que tem a seed, então a forma segura de obtê-lo é exportar
 * essa string pronta (Fase C2, tela "Ver Chave Pública") em vez de tentar
 * recalculá-lo a partir do xpub de conta (impossível — um xpub de conta não
 * carrega a chave mestra, só o fingerprint do PAI imediato, que não é o
 * mesmo dado).
 */
object WalletWatchOnlyImport {

    private val ORIGIN_REGEX = Regex("^\\[([0-9a-f]{8})/(\\d+)[h']/(\\d+)[h']/0[h']\\]([a-zA-Z0-9]+)$")

    fun run(accountOrigin: String, network: Network, spendType: SpendType) {
        require(!WalletStorage.exists()) { "Wallet já existe. Use 'Esquecer Wallet' antes de importar." }

        val trimmed = accountOrigin.trim()
        val match = ORIGIN_REGEX.find(trimmed)
            ?: error("Formato inválido — esperado algo como [a9fe848d/84'/0'/0']xpub... (exporte da tela \"Ver Chave Pública\" da carteira original).")

        val (fingerprintHex, purposeStr, coinStr, xpub) = match.destructured

        require(xpub.startsWith("xpub") || xpub.startsWith("tpub") || xpub.startsWith("zpub") || xpub.startsWith("vpub")) {
            "XPUB inválido dentro da chave pública informada."
        }

        val expectedPurpose = spendType.bipPurpose()
        require(purposeStr.toInt() == expectedPurpose) {
            "Essa chave é de purpose $purposeStr' — não bate com o tipo de endereço escolhido (esperado $expectedPurpose')."
        }
        require(coinStr.toInt() == network.coinType) {
            "Essa chave é de outra rede (coin type $coinStr') — não bate com a rede escolhida (esperado ${network.coinType}')."
        }

        val walletName = "pokewallet_$fingerprintHex"
        val externalDescriptor = when (spendType) {
            SpendType.BIP84 -> "wpkh($trimmed/0/*)"
            SpendType.BIP86 -> "tr($trimmed/0/*)"
        }
        val internalDescriptor = when (spendType) {
            SpendType.BIP84 -> "wpkh($trimmed/1/*)"
            SpendType.BIP86 -> "tr($trimmed/1/*)"
        }

        val json = JSONObject()
            .put("version",            2)
            .put("walletName",         walletName)
            .put("network",            network.name)
            .put("spendType",          spendType.name)
            .put("fingerprint",        fingerprintHex)
            .put("xpub",               xpub)
            .put("accountOrigin",      trimmed)
            .put("externalDescriptor", externalDescriptor)
            .put("internalDescriptor", internalDescriptor)
            .put("nextExternalIndex",  0)
            .put("nextInternalIndex",  0)
            .put("createdAt",          Instant.now().toString())
            .put("mnemonicVerified",   true) // não há seed pra confirmar
            .put("isWatchOnly",        true)

        WalletStorage.saveRaw(json)
    }
}
