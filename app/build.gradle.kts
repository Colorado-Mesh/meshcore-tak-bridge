plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing. The release workflow (.github/workflows/release.yml) unpacks the keystore and
// passes it in through these environment variables; see docs/RELEASING.md. Without them a release
// build is left unsigned, so `assembleRelease` still works on any computer, and debug builds are
// never affected. The keystore itself must never be committed (.gitignore excludes it).
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")

android {
    namespace = "org.coloradomesh.takbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.coloradomesh.takbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        releaseKeystore?.let { path ->
            create("release") {
                storeFile = file(path)
                // A PKCS12 keystore uses one password for the store and the key.
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":cot"))
    implementation(project(":takserver"))
    implementation(project(":protocol"))
}
