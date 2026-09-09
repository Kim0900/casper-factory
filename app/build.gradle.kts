plugins {
    id("com.android.application")
}

android {
    namespace = "kr.magi.gnsskeeper"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.magi.gnsskeeper"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
