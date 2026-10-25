plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.obdmaru.watcher"
    compileSdk = 35
    defaultConfig { applicationId = "dev.obdmaru.watcher"; minSdk = 31; targetSdk = 35; versionCode = 2; versionName = "0.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets["main"].java.srcDir("../shared/src/main/java")
    sourceSets["test"].java.srcDir("../shared/src/test/java")
    sourceSets["androidTest"].java.srcDir("../shared/src/androidTest/java")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
