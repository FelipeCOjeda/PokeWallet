package com.pokewallet.nostr

import com.pokewallet.crypto.CryptoUtils
import com.pokewallet.crypto.KeyDerivation
import com.pokewallet.crypto.Secp256k1
import java.math.BigInteger
import org.bouncycastle.crypto.ec.CustomNamedCurves

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

    /**
     * @param accountIndex Índice de conta NIP-06 (m/44'/1237'/accountIndex'/0/0).
     *   Default 0 preserva o comportamento existente (identidade única por
     *   seed); um índice diferente permite múltiplas identidades Nostr da
     *   mesma wallet, se algum dia for exposto na UI.
     * @return par (chave privada 32 bytes, chave pública x-only 32 bytes)
     */
    fun deriveFromSeed(seed: ByteArray, accountIndex: Int = 0): Pair<ByteArray, ByteArray> {
        val key = KeyDerivation.derive(
            seed,
            intArrayOf(
                KeyDerivation.hardened(44),
                KeyDerivation.hardened(NOSTR_COIN_TYPE),
                KeyDerivation.hardened(accountIndex),
                0,
                0
            )
        )
        val pubKey = Secp256k1.xOnlyPublicKeyFromPrivate(key.privateKey)
        return key.privateKey to pubKey
    }

    /**
     * Identidade Nostr EFÊMERA, gerada por SecureRandom em vez de derivada
     * da seed — usada só pelo lado watch-only (Fase C) do broadcast
     * air-gapped via BitChat, que não tem seed nenhuma nesse aparelho.
     * Nunca persistida (nasce e morre dentro da mesma chamada de envio);
     * o bot correlaciona a resposta pelo txid no conteúdo da mensagem, não
     * pela identidade do publicador, então uma chave nova a cada envio não
     * quebra o protocolo.
     */
    fun random(): Pair<ByteArray, ByteArray> {
        val n = CustomNamedCurves.getByName("secp256k1").n
        while (true) {
            val candidate = CryptoUtils.randomEntropy(32)
            val d = BigInteger(1, candidate)
            if (d > BigInteger.ZERO && d < n) {
                return candidate to Secp256k1.xOnlyPublicKeyFromPrivate(candidate)
            }
        }
    }
}
