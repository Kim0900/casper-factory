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
        versionCode = 7
        versionName = "0.2.5-test"
    }


}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
