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
        noCompress += setOf("bible", "commentary", "lexicon")
    }

    packaging {
        jniLibs {
            // The Onyx SDK ships its own libc++_shared.so; take the first to avoid
            // a duplicate-native-library merge conflict with other deps.
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
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

    // Onyx BOOX SDK — handwriting capture (TouchHelper raw drawing) + EPD refresh
    // control. Same versions proven in notesprout_android.
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    // The BOOX SDK reflects into hidden Android APIs; Android 14+ blocks the
    // self-exemption it needs, so bypass the enforcement before any SDK code runs.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    testImplementation("junit:junit:4.13.2")
}

// Bundles the prebuilt read-only content DBs from /data into the app's assets
// (under assets/content/). Keeps /data the single source of truth — no duplicate
// binaries committed under the app. bsb.bible is the reader's spine; the large
// commentary DBs will move to on-demand download rather than being bundled.
val bundleContentDbs by tasks.registering(Copy::class) {
    description = "Copy prebuilt content databases from /data into app assets."
    from(rootProject.file("../../data/bible/bsb.bible"))
    // Three commentaries ship in the APK for now — Matthew Henry's Concise (~6MB)
    // and six-volume Complete (~50MB), and Jamieson-Fausset-Brown (~19MB). While
    // the app is sideloaded-only the large DBs are fine bundled; a downloadable-
    // sources model is far off.
    from(rootProject.file("../../data/commentaries/mhcc.commentary"))
    from(rootProject.file("../../data/commentaries/mhc.commentary"))
    from(rootProject.file("../../data/commentaries/jfb.commentary"))
    // Strong's Hebrew & Greek dictionaries (~2.6MB), for word study.
    from(rootProject.file("../../data/lexicons/strongs.lexicon"))
    into(layout.buildDirectory.dir("generated/contentAssets/content"))
}
tasks.named("preBuild") { dependsOn(bundleContentDbs) }
