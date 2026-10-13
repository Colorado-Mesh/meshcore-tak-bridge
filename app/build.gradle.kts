plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.coloradomesh.takbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.coloradomesh.takbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
