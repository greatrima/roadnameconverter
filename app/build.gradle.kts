import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "kr.co.addresslens"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.addresslens"
        minSdk = 23
        targetSdk = 35
        versionCode = 26
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val vworldApiKey = localProperties.getProperty("VWORLD_API_KEY")
            ?: providers.gradleProperty("VWORLD_API_KEY").orNull
            ?: ""
        buildConfigField("String", "VWORLD_API_KEY", vworldApiKey.asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    val cameraXVersion = "1.4.2"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.0.1")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // The Korean OCR model is bundled so scanning works immediately and offline.
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Verify the dictionary shipped inside the APK, not just the source asset.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn("assembleDebug")
        systemProperty("dictionary.testApk", layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath)
        systemProperty("dictionary.sourceAsset", layout.projectDirectory.file("src/main/assets/address_dictionary.tsv.gz").asFile.absolutePath)
    }
}
