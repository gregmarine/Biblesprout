plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.symmetricalpalmtree.biblesprout"
    compileSdk = 35

    defaultConfig {
        // Reuses the Flutter app's application id — this build replaces it on device.
        applicationId = "com.symmetricalpalmtree.biblesprout"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // The only target devices (BOOX Go 6, later Supernote) are 64-bit ARM;
            // shipping just arm64-v8a drops unused ABIs and their native libs.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Ship the prebuilt content DBs uncompressed so SQLite can open the copied
    // file directly (and a future mmap path stays open).
    androidResources {
        noCompress += setOf("bible", "commentary")
    }

    // The content DBs are bundled from the repo-root /data folder at build time
    // (see the bundleContentDbs task below) rather than committed under the app.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/contentAssets"))

    buildTypes {
        debug {
            // Lets the debug build sit alongside a release install if needed.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Room over SQLCipher — same data stack as notesprout_android. Read-only Bible
    // and commentary DBs are opened plaintext (no key); SQLCipher's bundled SQLite
    // amalgamation includes FTS5, which the Android system library often lacks.
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Coroutines for off-main-thread DB work; lifecycleScope for UI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
}

// Bundles the prebuilt read-only content DBs from /data into the app's assets
// (under assets/content/). Keeps /data the single source of truth — no duplicate
// binaries committed under the app. bsb.bible is the reader's spine; the large
// commentary DBs will move to on-demand download rather than being bundled.
val bundleContentDbs by tasks.registering(Copy::class) {
    description = "Copy prebuilt content databases from /data into app assets."
    from(rootProject.file("../../data/bible/bsb.bible"))
    // Both Matthew Henry commentaries ship in the APK for now — the Concise (~6MB)
    // and the six-volume Complete (~50MB). While the app is sideloaded-only the
    // large DB is fine bundled; a downloadable-sources model is far off.
    from(rootProject.file("../../data/commentaries/mhcc.commentary"))
    from(rootProject.file("../../data/commentaries/mhc.commentary"))
    into(layout.buildDirectory.dir("generated/contentAssets/content"))
}
tasks.named("preBuild") { dependsOn(bundleContentDbs) }
