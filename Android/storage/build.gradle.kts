plugins { alias(libs.plugins.android.library); alias(libs.plugins.ksp) }
android { namespace = "org.ghostcloak.storage"; compileSdk = 37
 packaging { resources.excludes += setOf("**/*.dll", "**/*.dylib", "**/*.so"); jniLibs.excludes += "**/libsignal_jni_testing.so" }
 defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencies { coreLibraryDesugaring(libs.desugar); api(project(":crypto")); implementation(libs.room.runtime); ksp(libs.room.compiler); implementation(libs.sqlcipher)
 androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.espresso.core); androidTestImplementation(libs.signal.android)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
