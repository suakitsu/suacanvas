import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

val signingProperties = Properties()
val signingPropertiesFile = providers.environmentVariable("PIC_PILOT_ANDROID_SIGNING_PROPERTIES")
    .orNull
    ?.let(::file)
    ?: rootProject.file("signing.properties")
val hasReleaseSigning = signingPropertiesFile.exists().also { exists ->
    if (exists) signingPropertiesFile.inputStream().use(signingProperties::load)
}
val productionReleaseRequested = gradle.startParameter.taskNames.any { task ->
    task.contains("ProductionRelease", ignoreCase = true)
}
if (productionReleaseRequested && !hasReleaseSigning) {
    throw GradleException("Production release signing is not configured. Set PIC_PILOT_ANDROID_SIGNING_PROPERTIES or provide a local signing.properties file.")
}

android {
    namespace = "com.suakitsu.picpilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.suakitsu.picpilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 2000005
        versionName = "2.0.5"
        testInstrumentationRunner = "com.suakitsu.picpilot.PicPilotTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:13000/\"")
            buildConfigField("String", "WEB_BASE_URL", "\"http://10.0.2.2:13000/\"")
            manifestPlaceholders["appLabel"] = "PicPilot 测试版"
        }
        create("production") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://suakitsu.com/\"")
            buildConfigField("String", "WEB_BASE_URL", "\"https://suakitsu.com/\"")
            manifestPlaceholders["appLabel"] = "PicPilot"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "META-INF/LICENSE.md",
        "META-INF/LICENSE-notice.md"
    )

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.3.1")
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("com.google.dagger:hilt-android:2.55")
    kapt("com.google.dagger:hilt-compiler:2.55")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("io.mockk:mockk:1.13.17")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.55")
    kaptAndroidTest("com.google.dagger:hilt-compiler:2.55")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
