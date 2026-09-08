package com.pokewallet.lightning

import com.pokewallet.android.WalletRegistry
import java.io.File

/**
 * Diretório de dados da Breez SDK - Spark, isolado por carteira dentro de
 * `wallets/<fingerprint>/lightning/` — mesmo padrão de isolamento por
 * carteira já usado por wallet.json (ver [WalletRegistry]). Nunca
 * compartilhado entre carteiras diferentes, mesmo que usem o mesmo
 * mnemônico (import duplicado é caso raro e não vale a complexidade de
 * deduplicar aqui).
 */
object LightningStorage {

    fun storageDir(filesDir: File, walletId: String): File =
        File(WalletRegistry.walletDir(filesDir, walletId), "lightning").apply { mkdirs() }
}
