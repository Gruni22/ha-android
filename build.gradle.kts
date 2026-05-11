import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.gruni22.btdashboard"
    compileSdk = libs.versions.androidSdk.compile.get().toInt()

    defaultConfig {
        applicationId = "io.github.gruni22.btdashboard"
        minSdk = 26
        targetSdk = libs.versions.androidSdk.target.get().toInt()
        versionCode = 10
        versionName = "1.0.9"
    }

    signingConfigs {
        create("release") {
            // Self-contained keystore checked into the repo. This is fine for a
            // hobby / community APK distribution: the key only proves that
            // updates come from the same builder, not from any specific person.
            // For Play Store uploads, replace this with a private keystore.
            storeFile = rootProject.file("btdashboard/release.jks")
            storePassword = "btdashboard"
            keyAlias = "btdashboard"
            keyPassword = "btdashboard"
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            // R8 minify + resource shrink. Produces mapping.txt that Play Console
            // uses to deobfuscate stack traces. Library consumer-rules cover most
            // of our deps (Hilt, Room, Compose, Car App Library, ML Kit, Nordic);
            // app-specific keeps live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Bundle native debug symbols (when present) so Play Console can
            // symbolicate native crashes from .so files like ML Kit's barcode lib.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("11"))
    }
}

dependencies {
    implementation(project(":common"))
    coreLibraryDesugaring(libs.tools.desugar.jdk)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.accompanist.permissions)

    implementation(libs.timber)

    // Android Auto (Car App Library)
    // car.core provides Screen, Session, CarContext, model.* classes
    // car.projected adds ProjectedCarAppService for phone→car projection
    implementation(libs.car.core)
    implementation(libs.car.projected)

    // Room database for local entity/area/dashboard cache
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // QR scanner: ML Kit Barcode Scanning + CameraX preview.
    // Replaces the old zxing-android-embedded which used a launcher activity
    // and didn't fit Compose; this stack is fully Compose-native.
    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Nordic BLE Library — robust BleManager + CCCD/MTU/reconnect handling.
    // The raw BluetoothGatt APIs lose notification delivery after long-lived
    // connections; this library has battle-tested workarounds.
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)

    // ── Tests ──────────────────────────────────────────────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
