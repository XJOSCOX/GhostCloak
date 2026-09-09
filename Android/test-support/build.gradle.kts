plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies { testImplementation(project(":crypto")); testImplementation(project(":transport")); testImplementation(project(":backend")); testImplementation(libs.junit); testImplementation(libs.signal.client) }
tasks.test { testLogging { events("passed", "failed"); showStandardStreams = true } }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
