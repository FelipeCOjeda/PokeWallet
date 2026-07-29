plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "1.9.23"
}

android {
    namespace = "com.pokewallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pokewallet.btcwallet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testImplementation("junit:junit:4.13.2")
}
