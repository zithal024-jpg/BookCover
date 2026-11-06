import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Shared (release) builds: a separate app ID, so they never replace or read the owner's
// com.bookcover.diagnostic library. The signing key lives outside Git and OneDrive; without it
// assembleRelease still works but produces an unsigned APK. See docs/RELEASING.md.
val releaseApplicationId = "com.frankrodriguez.bookcover.reader"
val releaseSigningFile = (providers.gradleProperty("bookcover.signingProperties").orNull
    ?: System.getenv("BOOKCOVER_SIGNING_PROPERTIES")
    ?: System.getenv("USERPROFILE")?.let { "$it/BookCover-Signing/signing.properties" })
    ?.let(::File)?.takeIf { it.isFile }
val releaseSigning = releaseSigningFile?.let { source -> Properties().apply { source.inputStream().use(::load) } }

android {
    namespace = "com.bookcover.diagnostic"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.bookcover.diagnostic"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = releaseSigningFile!!.parentFile.resolve(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    // Connected instrumentation may uninstall its target during cleanup. Keep the test target
    // separate from the user's reader and library, even on the same physical phone.
    buildTypes {
        getByName("release") {
            // R8 shrinking stays off: Readium and PDFium rely on reflection that has not had device QA minified.
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "qa"
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { it.applicationId.set(releaseApplicationId) }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.window:window:1.5.1")
    implementation("org.readium.kotlin-toolkit:readium-shared:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:3.4.0")
    implementation("com.github.marain87:AndroidPdfViewer:3.2.8")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
