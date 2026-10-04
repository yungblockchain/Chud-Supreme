import java.util.Properties

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.com.google.dagger.hilt.android)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.androidx.baselineprofile)
    id("kotlin-parcelize")
}

val m3uMockServerUrl = providers.gradleProperty("m3uMockServerUrl").orElse("http://10.0.2.2:8080")

// Dial: sign release builds with a fixed key when one is configured, so a new APK installs
// over the old one on the Fire TV (and keeps accounts/favourites). Without it, builds fall back
// to the machine's debug key, which differs between computers and CI runs.
//   Local:  put dial-keystore.properties next to this file (see DIAL.md)
//   CI:     set the DIAL_KEYSTORE_* repository secrets (see .github/workflows/firestick.yml)
val dialKeystoreProperties = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("dial-keystore.properties"))
        .asText.orNull
        ?.let { load(it.reader()) }
}
val dialSigningValue: (String, String) -> String? = { key, env ->
    dialKeystoreProperties.getProperty(key)
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
}
val dialStoreFile = dialSigningValue("storeFile", "DIAL_KEYSTORE_FILE")

android {
    namespace = "com.m3u.tv"
    compileSdk = 37
    defaultConfig {
        // Own application id so Chud Supreme installs next to earlier CHUD STREAMS
        // (app.dial.tv) and upstream M3U, instead of replacing them.
        applicationId = "app.dial.supreme"
        minSdk = 26
        targetSdk = 33
        versionCode = 2
        versionName = "Supreme 2"

        // Crash reports go to GitHub with this token (the CHUD_GITHUB_TOKEN repository secret on
        // GitHub Actions). Never in the source: GitHub cancels tokens it finds in public repos.
        val githubToken = System.getenv("CHUD_GITHUB_TOKEN").orEmpty().trim()
        buildConfigField("String", "CHUD_GITHUB_TOKEN", "\"${githubToken.replace("\"", "")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["m3uMockServerUrl"] = m3uMockServerUrl.get()
    }
    signingConfigs {
        dialStoreFile?.let { keystorePath ->
            create("dial") {
                storeFile = rootProject.file(keystorePath)
                storePassword = dialSigningValue("storePassword", "DIAL_KEYSTORE_PASSWORD")
                keyAlias = dialSigningValue("keyAlias", "DIAL_KEY_ALIAS")
                keyPassword = dialSigningValue("keyPassword", "DIAL_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("dial") ?: signingConfigs.getByName("debug")
        }
        all {
            isCrunchPngs = false
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/**"
    }
}

tasks.matching { task ->
    task.name.startsWith("connected") && task.name.endsWith("AndroidTest")
}.configureEach {
    dependsOn(":testing:mock-server:startMockServer")
    dependsOn(":testing:extension-reference:installDebug")
    finalizedBy(":testing:mock-server:stopMockServer")
    finalizedBy(":testing:extension-reference:uninstallDebug")
}

hilt {
    enableAggregatingTask = true
}

baselineProfile {
    dexLayoutOptimization = true
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    implementation(project(":core:foundation"))
    implementation(project(":data"))
    implementation(project(":extension:api"))
    // business
    implementation(project(":business:foryou"))
    implementation(project(":business:favorite"))
    implementation(project(":business:setting"))
    implementation(project(":business:playlist"))
    implementation(project(":business:channel"))
    implementation(project(":business:playlist-configuration"))
    // baselineprofile
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile:tv"))
    // base
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.google.material)
    // lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    // work
    implementation(libs.androidx.work.runtime.ktx)
    // dagger
    implementation(libs.google.dagger.hilt)
    ksp(libs.google.dagger.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    // compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.navigation.compose)
    // tv
    api(libs.androidx.tv.material)
    // accompanist
    implementation(libs.google.accompanist.permissions)
    // other
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.io.coil.kt)
    implementation(libs.io.coil.kt.compose)
    implementation(libs.io.coil.kt.gif)
    implementation(libs.zxing.core)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.airbnb.lottie.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.minabox)
    implementation(libs.net.mm2d.mmupnp.mmupnp)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator.uiautomator)
}
