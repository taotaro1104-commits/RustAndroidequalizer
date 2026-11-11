import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localBuildProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.rusteq"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.rusteq"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.4"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; prefab = true }
    externalNativeBuild { cmake { path = file("../../native/CMakeLists.txt"); version = providers.gradleProperty("cmakeVersion").getOrElse(localBuildProperties.getProperty("cmake.version", "3.22.1")) } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

// Rust static libraries must exist before CMake configures either ABI.
val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    val sdk = android.sdkDirectory.absolutePath
    environment("ANDROID_HOME", sdk)
    environment("ANDROID_NDK_HOME", "$sdk/ndk/${android.ndkVersion}")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "29", "build", "--locked", "--release", "-p", "ffi-android")
    inputs.files(fileTree("../../rust") { include("**/*.rs", "**/Cargo.toml") })
    inputs.files(fileTree("../../vendor/sofar") { include("src/**/*.rs", "Cargo.toml") })
    inputs.files("../../Cargo.toml", "../../Cargo.lock")
    outputs.files("../../target/aarch64-linux-android/release/libffi_android.a", "../../target/x86_64-linux-android/release/libffi_android.a")
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(buildRust)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.oboe:oboe:1.9.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

