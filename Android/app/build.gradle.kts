import java.net.URI
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Gradle's exec provider tracks Git output as a configuration input. Never infer a
// release identity from a version label or from a parent repository's HEAD.
val checkoutRoot = rootProject.projectDir.parentFile.canonicalFile
fun gitOutput(vararg arguments: String): String = providers.exec {
    workingDir = checkoutRoot
    commandLine("git", *arguments)
}.standardOutput.asText.get()
val gitTopLevel = File(gitOutput("rev-parse", "--show-toplevel").trim()).canonicalFile
require(gitTopLevel == checkoutRoot) { "Android build requires Git metadata for this checkout" }
val sourceCommit = gitOutput("rev-parse", "--verify", "HEAD").trim()
require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}"))) { "Android build requires a full Git commit SHA" }
val gitStatus = gitOutput("-c", "core.quotepath=false", "status", "--porcelain=v1", "-z", "--untracked-files=all", "--", ".")
fun sourceAffectingUntracked(path: String): Boolean {
    if (path.endsWith(".log") || path.endsWith(".tmp")) return false
    return path.startsWith("Android/") || path.startsWith("backend/") ||
        path.startsWith("protocol/") || path.startsWith("infrastructure/") ||
        (path.substringBefore('/') == path && path.endsWith(".md"))
}
val sourceDirty = gitStatus.split('\u0000').any { entry ->
    entry.length >= 4 && (entry.substring(0, 2) != "??" || sourceAffectingUntracked(entry.substring(3)))
}
val reviewedInvocation = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "assembleSecurityReviewed" }
if (reviewedInvocation) {
    require(!gradle.startParameter.isDryRun && gradle.startParameter.excludedTaskNames.isEmpty()) {
        "Security-reviewed build cannot skip required tasks"
    }
    require(gradle.startParameter.dependencyVerificationMode.toString() == "STRICT") {
        "Security-reviewed build requires strict dependency verification"
    }
    require(!sourceDirty) { "Security-reviewed build requires a clean source checkout" }
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
        buildConfigField("String", "GIT_SHA", "\"$sourceCommit\"")
        buildConfigField("boolean", "GIT_DIRTY", sourceDirty.toString())

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
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedAndroidTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedAndroidTest/java")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("assembleSecurityReviewed") {
    group = "verification"
    description = "Build and test debug/release from a clean Git checkout with strict dependency verification."
    dependsOn(":test-support:test", ":app:testDebugUnitTest", ":app:testReleaseUnitTest",
        ":app:assembleDebug", ":app:assembleRelease")
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
// Phase 1K.4E: release arming and destruction were enabled only after disposable-AVD acceptance.
android.buildTypes.getByName("debug").buildConfigField("boolean", "EMERGENCY_PIN_ARMING_ENABLED", "true")
android.buildTypes.getByName("release").buildConfigField("boolean", "EMERGENCY_PIN_ARMING_ENABLED", "true")
android.buildTypes.all { buildConfigField("boolean", "EMERGENCY_WIPE_DESTRUCTIVE_READY", "true") }
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
    // Local QR encoding/decoding and camera capture; no cloud scanning service.
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") {
        exclude(group = "com.google.zxing", module = "core")
    }
    // Biometric needs FragmentActivity; its old transitive Fragment rejects Activity Result request codes.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
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
