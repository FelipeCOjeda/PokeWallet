package com.pokewallet.crypto

/**
 * DescriptorImporter
 *
 * Placeholder para futura implementação de importdescriptors.
 *
 * IMPORTANTE:
 * - Este projeto ainda não possui serialização canônica de xpub/tpub
 * - Nem builder de descriptor
 * - Nem payload JSON para importdescriptors
 *
 * Quando essas camadas existirem, esta classe será implementada.
 */
object DescriptorImporter {

    fun importBip84Descriptors(
        seed: ByteArray,
        fingerprint: Int
    ) {
        // TODO:
        // Implementar importdescriptors quando:
        // - derivação pública BIP32 existir
        // - serialização de xpub/tpub existir
        // - builder de descriptor existir
        //
        // Por enquanto, este método é NO-OP.
        println("DescriptorImporter: importBip84Descriptors() ainda não implementado")
    }
}

