package com.pokewallet.crypto

object DescriptorPathParser {

    /**
     * Extrai o derivation path de um descriptor do Bitcoin Core.
     *
     * Ex:
     * wpkh([65966986/84h/1h/0h/0/24]02ab...)
     * -> m/84h/1h/0h/0/24
     */
    fun extractPath(desc: String): String {

        val insideBrackets =
            desc.substringAfter("[")
                .substringBefore("]")

        val path =
            insideBrackets.substringAfter("/")

        return "m/$path"
    }
}
