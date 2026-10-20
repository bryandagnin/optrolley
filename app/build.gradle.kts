import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.optrolley.autoroute"
    // Compose 1.12 requires compiling against 37; targetSdk stays at 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.optrolley.autoroute"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// O23 is about the manifest that ships, with every library's additions, so
// ManifestTest reads the variant's merged one rather than the source file or
// the unit-test manifest (which test libraries add permissions to).
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val testTask = "test${variant.name.replaceFirstChar(Char::uppercase)}UnitTest"
        tasks.withType<Test>().matching { it.name == testTask }.configureEach {
            inputs.file(manifest).withPropertyName("mergedManifest").withPathSensitivity(PathSensitivity.NONE)
            jvmArgumentProviders += CommandLineArgumentProvider {
                listOf("-Doptrolley.mergedManifest=${manifest.get().asFile.absolutePath}")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Scanning (F12): CameraX frames, decoded on the device by ZXing. Not camera-view: it brings
    // camera-video and media3, which declare a network permission (O23).
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
