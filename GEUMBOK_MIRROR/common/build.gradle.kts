import java.util.zip.ZipFile

plugins { id("com.android.library") }

val certificateSource = configurations.create("certificateSource")
val isolatedCertificateJar = tasks.register<Jar>("isolateCertificateJar") {
    archiveFileName.set("bcpkix-certificate-only-1.86.jar")
    destinationDirectory.set(layout.buildDirectory.dir("isolated-deps"))
    from({ zipTree(certificateSource.singleFile) })
    exclude("org/bouncycastle/est/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    val sourcePath = layout.buildDirectory.file("isolated-deps/original-path.txt")
    outputs.file(sourcePath)
    doLast {
        sourcePath.get().asFile.writeText(certificateSource.singleFile.absolutePath)
        ZipFile(archiveFile.get().asFile).use { jar ->
            check(jar.entries().asSequence().none { it.name.startsWith("org/bouncycastle/est/") })
        }
    }
}

android {
    namespace = "kr.magi.mirror.common"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    add(certificateSource.name, "org.bouncycastle:bcpkix-jdk18on:1.86") { isTransitive = false }
    implementation(files(isolatedCertificateJar.flatMap { it.archiveFile }))
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
}
