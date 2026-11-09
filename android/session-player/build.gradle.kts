plugins { id("com.android.application") }
android {
    namespace = "dev.rusteq.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "dev.rusteq.fixture"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "test-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
