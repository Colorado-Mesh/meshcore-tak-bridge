plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The server hands parsed chat messages to its listener, so the type is part of its API.
    api(project(":cot"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
