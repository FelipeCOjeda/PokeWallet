package com.pokewallet.crypto

fun SpendType.bipPurpose(): Int =
    when (this) {
        SpendType.BIP84 -> 84
        SpendType.BIP86 -> 86
    }
