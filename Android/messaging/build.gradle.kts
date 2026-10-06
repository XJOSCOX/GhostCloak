plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies { api(project(":crypto")); api(project(":transport")); api(project(":attachments")); implementation(libs.cbor); testImplementation(libs.junit) }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
