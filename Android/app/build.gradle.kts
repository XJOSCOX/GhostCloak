import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    packaging { resources.excludes += setOf("**/*.dll", "**/*.dylib", "**/*.so"); jniLibs.excludes += "**/libsignal_jni_testing.so" }
    namespace = "org.ghostcloak.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.ghostcloak.app"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Android Studio's default debug variant is ready for physical-device staging.
// The legacy override is intentionally DEBUG ONLY; release has a separate opt-in.
val debugOriginOverride = providers.gradleProperty("ghostcloakApiOrigin").orNull
val releaseOriginOverride = providers.gradleProperty("ghostcloakReleaseApiOrigin").orNull
val debugApiOrigin = debugOriginOverride ?: "https://api.ghostcloak.org"
val releaseApiOrigin = releaseOriginOverride ?: ""
fun validateApiOrigin(value: String) {
    if (value.isEmpty()) return
    val origin = URI(value)
    require(origin.scheme == "https" && origin.host != null && origin.host.matches(Regex("[a-z0-9][a-z0-9.-]{0,99}")) &&
        !origin.host.matches(Regex("[0-9.]+")) && origin.rawUserInfo == null && origin.rawQuery == null &&
        origin.rawFragment == null && origin.path in setOf("", "/")) { "API origin must be an HTTPS DNS origin without credentials, query or path" }
}
validateApiOrigin(debugApiOrigin)
validateApiOrigin(releaseApiOrigin)
require(releaseApiOrigin.isEmpty() || URI(releaseApiOrigin).host.trimEnd('.') != "api.ghostcloak.org") {
    "Release must not use the staging API. Set ghostcloakReleaseApiOrigin to a reviewed release origin, or leave it empty."
}
android.buildTypes.getByName("debug").buildConfigField("String", "API_ORIGIN", "\"$debugApiOrigin\"")
android.buildTypes.getByName("release").buildConfigField("String", "API_ORIGIN", "\"$releaseApiOrigin\"")

// Verify generated BuildConfig against inputs rather than trusting shared/defaultConfig fields.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
}
tasks.withType<Test>().configureEach {
    debugOriginOverride?.let { value -> systemProperty("ghostcloak.test.debugOverride", value) }
    releaseOriginOverride?.let { value -> systemProperty("ghostcloak.test.releaseOverride", value) }
}

dependencies {
    implementation(libs.androidx.work.runtime)
    androidTestImplementation(libs.androidx.work.testing)
    implementation(project(":messaging"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.viewmodel.compose)
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":storage"))
    implementation(libs.signal.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
