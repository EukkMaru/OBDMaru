plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.obdmaru.scanner"
    compileSdk = 35
    defaultConfig { applicationId = "dev.obdmaru.scanner"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    sourceSets["main"].java.srcDir("../shared/src/main/java")
    sourceSets["test"].java.srcDir("../shared/src/test/java")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
