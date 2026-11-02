plugins {
    id("com.android.application")
}

// All installable APKs MUST be signed with the same dedicated private key.
// Key material and passwords are injected by the authorized signer; never committed.
val signingKeystorePath = System.getenv("GNSS_KEEPER_KEYSTORE_FILE")
val signingStorePassword = System.getenv("GNSS_KEEPER_STORE_PASSWORD")
val signingAlias = System.getenv("GNSS_KEEPER_KEY_ALIAS")
val signingKeyPassword = System.getenv("GNSS_KEEPER_KEY_PASSWORD")
val hasDedicatedSigner = listOf(
    signingKeystorePath, signingStorePassword, signingAlias, signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "kr.magi.gnsskeeper"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.magi.gnsskeeper"
        minSdk = 31
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.7"
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (hasDedicatedSigner) {
            create("keeperDedicated") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (hasDedicatedSigner) signingConfigs.getByName("keeperDedicated") else null
        }
        getByName("debug") {
            // Android's per-run generated debug certificate is never accepted.
            // Without Secrets, build a compile/check artifact only (no installable APK publication).
            signingConfig = if (hasDedicatedSigner)
                signingConfigs.getByName("keeperDedicated") else null
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
