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

val apiOrigin=providers.gradleProperty("ghostcloakApiOrigin").orElse("").get()
if(apiOrigin.isNotEmpty()) {
    val origin=URI(apiOrigin)
    require(origin.scheme=="https" && origin.host!=null && origin.host.matches(Regex("[a-z0-9][a-z0-9.-]{0,99}")) && !origin.host.matches(Regex("[0-9.]+")) && origin.rawUserInfo==null && origin.rawQuery==null && origin.rawFragment==null && origin.path in setOf("","/"))
}
android.defaultConfig.buildConfigField("String","API_ORIGIN","\"$apiOrigin\"")

dependencies {
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
