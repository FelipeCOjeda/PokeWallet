package com.pokewallet.crypto

sealed class PassphraseMode {
    object None : PassphraseMode()
    object Pokemon : PassphraseMode()
    data class Custom(val value: String) : PassphraseMode()

    /** Tag persistida em wallet.json, usada pra reconstruir o modo ao retomar a verificação. */
    fun tag(): String = when (this) {
        is None    -> "none"
        is Pokemon -> "pokemon"
        is Custom  -> "custom"
    }

    companion object {
        fun fromPersisted(tag: String?, passphrase: String): PassphraseMode = when (tag) {
            "none"    -> None
            "pokemon" -> Pokemon
            "custom"  -> Custom(passphrase)
            // wallet.json de antes dessa tag existir: melhor palpite pelo conteúdo da passphrase
            else -> if (passphrase.isEmpty()) None else Pokemon
        }
    }
}
