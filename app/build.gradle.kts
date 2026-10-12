plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "tv.ezano.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "tv.ezano.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Fire TV and Android TV devices are ARM; skipping x86 speech libraries keeps the download small.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (System.getenv("EZANO_KEYSTORE") != null) create("personalRelease") {
            storeFile = file(System.getenv("EZANO_KEYSTORE"))
            storePassword = System.getenv("EZANO_STORE_PASSWORD")
            keyAlias = "ezano"
            keyPassword = System.getenv("EZANO_STORE_PASSWORD")
        }
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); if (System.getenv("EZANO_KEYSTORE") != null) signingConfig = signingConfigs.getByName("personalRelease") } }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Installs the Compose/Media3 startup profiles on sideloaded installs, which never get Play's cloud profiles.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("androidx.media3:media3-session:1.8.1")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
