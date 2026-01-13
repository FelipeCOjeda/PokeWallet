package com.pokewallet.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object CryptoProvider {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
