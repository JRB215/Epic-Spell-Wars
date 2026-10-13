plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The card data files live in Base/cards and Expansion 1/cards. They are copied into the jar so the
// engine can load them from the classpath.
val copyCardData by tasks.registering(Copy::class) {
    from(rootProject.file("Base/cards")) { include("*.json"); into("cards/base") }
    from(rootProject.file("Expansion 1/cards")) { include("*.json"); into("cards/expansion1") }
    into(layout.buildDirectory.dir("generated/card-data"))
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/card-data"))
}

tasks.processResources {
    dependsOn(copyCardData)
}

tasks.test {
    useJUnitPlatform()
}
