package com.pokewallet.crypto

/**
 * DescriptorImporter
 *
 * Responsável por:
 * - Exibir templates canônicos de descriptors BIP84 / BIP86
 * - Importar descriptors automaticamente no Bitcoin Core
 *
 * Pode:
 * - Ser usado de forma educacional (manual)
 * - Ser usado de forma automática no wallet-init
 *
 * NÃO faz:
 * - Serialização de XPUB
 * - Base58
 * - Derivação de chaves privadas
 *
 * Importante:
 * - Descriptor SEM chave (xpub/tpub) NÃO FUNCIONA
 * - O Bitcoin Core é a fonte da verdade para indexação
 */
object DescriptorImporter {

    // =================================================
    // 🔧 IMPORTAÇÃO AUTOMÁTICA (CANÔNICA)
    // =================================================

    /**
     * Importa descriptors externos e internos no Bitcoin Core.
     *
     * Deve ser chamado UMA VEZ no wallet-init.
     *
     * - idempotente
     * - seguro repetir
     * - não depende de seed
     */
    fun importToCore(
        walletName: String,
        externalDescriptor: String,
        internalDescriptor: String
    ) {
        val rpc = BitcoinRpcClient(wallet = walletName)

        rpc.call(
            "importdescriptors",
            listOf(
                listOf(
                    mapOf(
                        "desc" to externalDescriptor,
                        "active" to true,
                        "internal" to false,
                        "timestamp" to "now",
                        "watchonly" to true
                    ),
                    mapOf(
                        "desc" to internalDescriptor,
                        "active" to true,
                        "internal" to true,
                        "timestamp" to "now",
                        "watchonly" to true
                    )
                )
            )
        )
    }

    // =================================================
    // 📘 MODO EDUCACIONAL / MANUAL
    // =================================================

    /**
     * BIP84 — SegWit v0 (wpkh)
     *
     * Exibe template de descriptor para import MANUAL.
     */
    fun importBip84Descriptors(
        seed: ByteArray,
        fingerprint: Int
    ) {
        val network = Network.REGTEST
        val coinType = network.coinType

        val fpHex = fingerprint.toUInt().toString(16)

        println("DescriptorImporter: BIP84 (watch-only)")
        println("fingerprint = $fpHex")
        println()

        println("👉 TEMPLATE DE DESCRIPTOR (substitua XPUB):")
        println()

        println(
            "wpkh([$fpHex/84'/$coinType'/0']XPUB/0/*)"
        )

        println()
        println("⚠️ ATENÇÃO:")
        println("- XPUB (ou TPUB no regtest/testnet) é OBRIGATÓRIO")
        println("- fingerprint sozinho NÃO identifica chaves")
        println("- Use getdescriptorinfo para gerar o checksum")
        println()
        println("Exemplo:")
        println(
            "bitcoin-cli -regtest getdescriptorinfo \"wpkh([$fpHex/84'/$coinType'/0']tpub.../0/*)\""
        )
    }

    /**
     * BIP86 — Taproot (tr)
     *
     * Exibe template de descriptor para import MANUAL.
     */
    fun importBip86Descriptors(
        seed: ByteArray,
        fingerprint: Int
    ) {
        val network = Network.REGTEST
        val coinType = network.coinType

        val fpHex = fingerprint.toUInt().toString(16)

        println("DescriptorImporter: BIP86 (watch-only)")
        println("fingerprint = $fpHex")
        println()

        println("👉 TEMPLATE DE DESCRIPTOR (substitua XPUB):")
        println()

        println(
            "tr([$fpHex/86'/$coinType'/0']XPUB/0/*)"
        )

        println()
        println("⚠️ ATENÇÃO:")
        println("- XPUB (ou TPUB no regtest/testnet) é OBRIGATÓRIO")
        println("- fingerprint sozinho NÃO identifica chaves")
        println("- Use getdescriptorinfo para gerar o checksum")
        println()
        println("Exemplo:")
        println(
            "bitcoin-cli -regtest getdescriptorinfo \"tr([$fpHex/86'/$coinType'/0']tpub.../0/*)\""
        )
    }
}
