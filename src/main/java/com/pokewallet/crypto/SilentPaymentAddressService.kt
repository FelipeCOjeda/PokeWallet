package com.pokewallet.crypto

/**
 * SilentPaymentAddressService
 *
 * Stateless, paralelo ao [ReceiveAddressService] — mas Silent Payments
 * (BIP-352) não usa cadeia de endereços: é UM endereço só por carteira
 * (as chaves scan/spend não avançam índice a cada uso, ao contrário do
 * endereço normal BIP84/86). Por isso não existe "addressAt(index)" aqui.
 */
object SilentPaymentAddressService {

    /** Endereço Silent Payments único desta carteira (scan pubkey || spend
     *  pubkey). Só carteiras com seed neste aparelho — watch-only "somente
     *  scan" (chave de scan privada + spend pública, sem seed) é a Fase 5. */
    fun ownAddress(seed: ByteArray, network: Network, account: Int = 0): String {
        val scanPubKey  = Secp256k1.publicKeyFromPrivate(Bip352KeyDerivation.scanKey(seed, network, account).privateKey)
        val spendPubKey = Secp256k1.publicKeyFromPrivate(Bip352KeyDerivation.spendKey(seed, network, account).privateKey)
        return SilentPaymentAddress.encode(scanPubKey, spendPubKey, network)
    }
}
