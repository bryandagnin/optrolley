plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Synthetic histories for the app's debug benchmark (tier 4 §7.2). They come
// from the simulator, which is test code, so they are made here and handed
// to the app's debug build as assets; none is committed.
tasks.register<JavaExec>("benchHistories") {
    val out = layout.buildDirectory.dir("bench-assets")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.optrolley.core.BenchHistoriesKt"
    outputs.dir(out)
    argumentProviders += CommandLineArgumentProvider { listOf(out.get().dir("bench").asFile.absolutePath) }
}
