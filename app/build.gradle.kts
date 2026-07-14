import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ktlint via the CLI rather than the Gradle plugin: ktlint-gradle discovers sources through the
// `org.jetbrains.kotlin.android` plugin, which AGP 9's built-in Kotlin doesn't apply — so the plugin
// silently lints only *.kts and reports green. Driving the CLI keeps the check honest.
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint(libs.ktlint.cli)
}

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin code style."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("src/**/*.kt", "**/*.kts", "!**/build/**")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-fix Kotlin code style violations."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("-F", "src/**/*.kt", "**/*.kts", "!**/build/**")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

// Signing config loaded from keystore.properties (gitignored).
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) load(FileInputStream(f))
    }

android {
    namespace = "net.triton.frigateviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.triton.frigateviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/io.netty.versions.properties",
                    "/META-INF/native-image/**",
                    "/META-INF/*.kotlin_module",
                )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Don't fail debug CI on lint warnings; report still uploaded as artifact.
        // Release builds remain strict via lintRelease in the release task graph.
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

// Gradle resolves `kotlin {}` here to the Project-level Kotlin extension regardless of
// nesting (it was previously misleadingly nested inside `android {}` above, which looks
// like ApplicationExtension.kotlin but isn't — moved to top level to match reality).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlin.RequiresOptIn",
            // Material 3 Expressive (wavy/loading indicators, button groups, expressive motion) is
            // opted into once here rather than with an @OptIn on every call site — the app uses these
            // components throughout, so per-site annotations would be pure noise.
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.datastore.preferences)
    implementation(libs.tink.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.stream.webrtc.android)
    implementation(libs.mqtt.client)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialkolor)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.retrofit.converter.scalars)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
