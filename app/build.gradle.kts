import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import java.io.FileInputStream
import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}

// Load signing properties if they exist.
// This allows the project to build successfully even when checked out from GitHub
// without the signing keystore and properties files (e.g. local debug builds, forks).
val signingPropertiesFile = rootProject.file("signing.properties")
val keystoreFile = rootProject.file("dashspeed-release-key.keystore")
val signingProperties = Properties()
val hasSigningConfig = signingPropertiesFile.exists() && keystoreFile.exists()

if (hasSigningConfig) {
    signingProperties.load(FileInputStream(signingPropertiesFile))
}

android {
    namespace = "org.chaosorderx.dashspeed"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.chaosorderx.dashspeed"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "org.chaosorderx.dashspeed.HiltTestRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = "dashspeed-key-alias"
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Only apply signing config if keystore and properties exist
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kover {
    reports {
        filters {
            includes {
                packages(
                    "org.chaosorderx.dashspeed.speed",
                    "org.chaosorderx.dashspeed.tracking",
                    "org.chaosorderx.dashspeed.overlay",
                    "org.chaosorderx.dashspeed.alerts",
                    "org.chaosorderx.dashspeed.data",
                    "org.chaosorderx.dashspeed.gps",
                )
            }
            excludes {
                // Hilt-generated DI factories are not written by us and cannot be unit-tested.
                classes("*_Factory")
                // Compose composable files and Android Context-dependent classes require
                // instrumented tests (Robolectric/Compose test framework), not unit tests.
                classes(
                    "*.SpeedOverlayCanvasKt",
                    "*.AlertEngine",
                    // GpsManager uses FusedLocationProviderClient; bearingDelta() is tested separately.
                    "*.GpsManager",
                )
            }
        }
        verify {
            rule {
                minBound(80, CoverageUnit.LINE)
                minBound(70, CoverageUnit.BRANCH)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Model asset download — configuration-cache-compatible abstract task
// Set  modelUrl=<url>  in gradle.properties (or pass -PmodelUrl=<url>).
// Use  -PforceModelUpdate  to delete the cached file and re-download.
// ---------------------------------------------------------------------------
abstract class DownloadModelTask : DefaultTask() {
    @get:Input
    abstract val modelUrl: Property<String>

    @get:Input
    abstract val forceUpdate: Property<Boolean>

    @get:OutputFile
    abstract val destFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val dest = destFile.get().asFile
        if (dest.exists() && !forceUpdate.get()) {
            logger.lifecycle("'${dest.name}' already present — skipping. Use -PforceModelUpdate to re-download.")
            return
        }
        val url = modelUrl.get()
        if (url.isBlank()) {
            logger.warn(
                """
                |WARNING: '${dest.name}' not found and 'modelUrl' is not set.
                |Detection is disabled at runtime until the model is provided.
                |  • Run scripts/export_model_colab.py in Google Colab, then place
                |    the file in app/src/main/assets/ manually.
                |  • Or set  modelUrl=<url>  in gradle.properties to enable auto-download.
                """.trimMargin(),
            )
            return
        }
        logger.lifecycle("Downloading ${dest.name} from $url …")
        dest.parentFile.mkdirs()
        @Suppress("DEPRECATION") // URL(String) is fine on JVM 17
        URL(url).openStream().use { src ->
            dest.outputStream().use { dst -> src.copyTo(dst) }
        }
        logger.lifecycle("Saved ${dest.length() / 1_024} KB → ${dest.path}")
    }
}

tasks.register<DownloadModelTask>("downloadModel") {
    group = "dashspeed"
    description = "Download yolov8n_int8.tflite to assets/ when absent."
    modelUrl.set(providers.gradleProperty("modelUrl").orElse(""))
    forceUpdate.set(providers.gradleProperty("forceModelUpdate").map { true }.orElse(false))
    destFile.set(layout.projectDirectory.dir("src/main/assets").file("yolov8n_int8.tflite"))
}

tasks.named("preBuild").configure { dependsOn("downloadModel") }

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)

    implementation(libs.play.services.location)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.android)

    implementation(libs.timber)

    debugImplementation(libs.leakcanary)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
