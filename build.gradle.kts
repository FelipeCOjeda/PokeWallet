import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "1.9.23"
    id("com.squareup.wire") version "5.3.3"
}

// Lido de local.properties (nunca commitado, ver .gitignore) — chave da
// Breez SDK - Spark, escopada ao projeto "Pkmwallet". Vazio se ausente
// (build não quebra pra quem não tem a feature Lightning em uso/teste).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val breezApiKey: String = localProperties.getProperty("BREEZ_API_KEY", "")

android {
    namespace = "com.pokewallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pokewallet.btcwallet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BREEZ_API_KEY", "\"$breezApiKey\"")

        // libbreez_sdk_spark_bindings.so sozinha já é ~55-65MB POR
        // arquitetura (SDK Lightning inteira compilada em Rust — rede,
        // cripto, banco embutido) — sem isso o APK carrega as 4 (arm64-v8a,
        // armeabi-v7a, x86, x86_64) e passa de 270MB. Todo aparelho Android
        // real de interesse aqui é arm64-v8a (nenhum device físico de teste
        // é x86, e armeabi-v7a só importa pra aparelho de 32 bits, cada vez
        // mais raro) — reduz o instalável real sem perder nenhum device que
        // este projeto de fato testa. minifyEnabled (ver buildTypes.release
        // abaixo) NÃO afeta isso: R8/ProGuard só mexe em código Kotlin/Java,
        // nunca em biblioteca nativa.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

// Gera o cliente gRPC (Kotlin, sobre OkHttp) a partir de src/main/proto/ —
// scan de recebimento Silent Payments via blindbit-oracle (Fase 3). Wire
// (Square) em vez do gRPC-Java oficial: código gerado bem mais enxuto e
// reusa o OkHttp que o projeto já tem como dependência, evitando puxar a
// pilha protobuf-java+Guava+transporte próprio do gRPC oficial.
wire {
    kotlin {
        rpcRole = "client"
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation(kotlin("stdlib"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    // Cliente WebSocket pro modo de envio via Nostr/BitChat — não existe
    // suporte a WebSocket em java.net.HttpURLConnection, e nenhuma outra
    // dependência do projeto cobre isso.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Cliente gRPC pro blindbit-oracle (scan de recebimento Silent Payments,
    // Fase 3) — stubs gerados de src/main/proto/ pelo plugin Wire acima.
    implementation("com.squareup.wire:wire-runtime:5.3.3")
    implementation("com.squareup.wire:wire-grpc-client:5.3.3")

    // Breez SDK - Spark: pagamentos Lightning self-custodial (BOLT11 + LN
    // address), feature opt-in e isolada (ver com.pokewallet.lightning) —
    // saldo Spark nunca soma ao saldo on-chain principal. Versão conferida
    // direto no maven-metadata.xml do repositório da Breez em 2026-09-08
    // (<release>/<latest> real, não chutada).
    implementation("breez_sdk_spark:bindings-android:0.24.1")

    testImplementation("junit:junit:4.13.2")

    // org.json.* vem do android.jar em produção (stub que sempre existe no
    // device real) — mas testDebugUnitTest roda em JVM puro, onde o android.jar
    // de teste é um stub que lança "not mocked" em qualquer método real
    // (ex: JSONObject.put()). Implementação de verdade só pro classpath de
    // teste resolve sem afetar o app em produção.
    testImplementation("org.json:json:20240303")
}
