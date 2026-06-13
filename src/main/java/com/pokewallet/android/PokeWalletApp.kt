package com.pokewallet.android

import android.app.Application
import com.pokewallet.crypto.ensureBouncyCastle

class PokeWalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureBouncyCastle()
    }
}
