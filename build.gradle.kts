plugins {
    kotlin("jvm") version "1.9.23"
    application
}

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Criptografia (secp256k1, ECDSA, etc.)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // JSON parsing (bitcoin-cli listunspent)
    implementation("org.json:json:20240303")
}
