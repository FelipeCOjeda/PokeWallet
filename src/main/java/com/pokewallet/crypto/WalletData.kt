package com.pokewallet.crypto

import org.json.JSONObject

/**
 * Representa o estado persistido da wallet.
 *
 * Este objeto é a fonte de verdade em memória,
 * refletindo exatamente o conteúdo do wallet.json.
 */
data class WalletData(

    // -----------------------------
    // Identidade da wallet no Core
    // -----------------------------
    val walletName: String,

    // -----------------------------
    // Segredo / Seed — null quando isWatchOnly (importada só por xpub, sem
    // acesso à chave privada nesta carteira/dispositivo)
    // -----------------------------
    val mnemonic: List<String>?,
    val passphrase: String?,
    val mnemonicVerified: Boolean,
    val isWatchOnly: Boolean,

    // -----------------------------
    // Identidade BIP32
    // -----------------------------
    val fingerprint: String,
    /** false quando [fingerprint] é um pseudo-ID interno (import watch-only
     *  por xpub pura, sem o fingerprint real da chave mestra) — nesse caso
     *  o PSBT air-gapped grava fingerprint desconhecido (zeros) em vez
     *  deste valor, pra não colidir com a checagem do lado assinante. */
    val hasVerifiedFingerprint: Boolean,

    // -----------------------------
    // Configuração
    // -----------------------------
    val network: Network,
    val spendType: SpendType,

    // -----------------------------
    // Chaves públicas
    // -----------------------------
    val xpub: String?,
    /** "[fingerprintHex/purpose'/coin'/0']xpub" — mesma string já usada nos
     *  descriptors, exportável via QR pra parear uma carteira watch-only
     *  noutro dispositivo sem digitação manual. */
    val accountOrigin: String?,

    // -----------------------------
    // Índices HD (estado mutável)
    // -----------------------------
    var nextExternalIndex: Int,
    var nextInternalIndex: Int,

    /** Índices (chain 0/1) que JÁ mostraram atividade em algum scan
     *  anterior — usado pelo scan incremental (WalletScanner.scan) pra
     *  saber quais endereços precisam ser RE-verificados (podem ter sido
     *  gastos ou recebido mais) sem precisar re-varrer o intervalo inteiro
     *  desde o índice 0 toda vez. Só cresce (uma vez ativo, sempre conta
     *  como ativo — tx_count nunca diminui). */
    var activeExternalIndices: Set<Int>,
    var activeInternalIndices: Set<Int>,
    /** true só logo após migrar um wallet.json de antes do scan incremental
     *  existir (nextIndex > 0 mas activeIndices desconhecido) — força UMA
     *  varredura completa no próximo scan antes de confiar no modo
     *  incremental. Ver migração em WalletStorage.loadLocked(). */
    var needsFullRescan: Boolean = false,

    // -----------------------------
    // Memória local do último saldo conhecido (estado mutável) — permite
    // pintar a tela com o saldo IMEDIATO ao abrir a carteira, sem esperar
    // a resposta de rede do scan (que continua rodando por trás, via o
    // scan incremental acima, pra manter isso atualizado). Nunca é a
    // fonte de verdade pra decisão financeira (assinatura de envio sempre
    // usa o resultado fresco de WalletScanner.scan), só serve pra exibição
    // inicial. null = nenhum scan bem-sucedido ainda nesta carteira.
    var cachedBalanceSats: Long? = null,
    var cachedPendingSats: Long? = null,
    var cachedUtxoCount: Int? = null,
    var cachedScanTimeMs: Long? = null,

    // -----------------------------
    // UTXOs congelados ("txid:vout"), fora da seleção automática e manual
    // -----------------------------
    val frozenUtxoKeys: Set<String>,

    // -----------------------------
    // Silent Payments (BIP-352, Fase 3) — UTXOs recebidos via endereço SP,
    // já CONFIRMADOS contra uma fonte própria (nunca só o filtro de 8
    // bytes do oracle, ver SilentPaymentsConfirmer). Ao contrário dos
    // UTXOs normais (recalculáveis de graça reescaneando os endereços
    // derivados), um UTXO SP não vem de um endereço fixo — se não
    // persistir aqui, ele é irrecuperável sem re-escanear a blockchain
    // inteira desde o genesis (não dá pra saber a altura em que chegou
    // sem achar de novo). spScanTipHeight é até onde o scan SP já foi —
    // mesmo papel de nextExternalIndex pro scan normal, mas em altura de
    // bloco em vez de índice de endereço.
    // -----------------------------
    var spUtxos: List<com.pokewallet.network.SilentPaymentsConfirmer.ConfirmedUtxo> = emptyList(),
    var spScanTipHeight: Long = 0L,
    /** Altura do bloco mais recente no momento em que esta carteira foi
     *  CRIADA (não restaurada/importada — nesses casos fica null, porque
     *  não há como saber a altura real de nascimento de uma seed que já
     *  existia antes) — ver WalletViewModel.createWallet(). Ponto de
     *  partida do primeiro scan de Silent Payments quando conhecida (2
     *  blocos de margem, ver SilentPaymentsSync.sync): uma carteira nova
     *  não pode ter recebido nada antes de existir, então não tem razão
     *  nenhuma pra escanear anterior a isso — bem mais preciso e mais
     *  rápido que o lookback fixo (DEFAULT_LOOKBACK_BLOCKS), que só serve
     *  de fallback pra carteira restaurada/watch-only/antiga sem esse
     *  campo. */
    var birthHeight: Long? = null,

    // -----------------------------
    // JSON bruto (preservação futura)
    // -----------------------------
    val raw: JSONObject
)
