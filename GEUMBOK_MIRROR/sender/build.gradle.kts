plugins { id("com.android.application") }
android {
    namespace = "kr.magi.mirror.sender"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.magi.mirror.sender"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-synthetic"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/geumbok-debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    packaging {
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies { implementation(project(":common")) }
