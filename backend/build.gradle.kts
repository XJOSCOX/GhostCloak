plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":protocol")); implementation(libs.cbor)
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")
    implementation("org.postgresql:postgresql:42.7.11")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")
}
application { mainClass.set("org.ghostcloak.backend.ProductionServerKt") }
tasks.register<JavaExec>("runLocal") { classpath = sourceSets.main.get().runtimeClasspath; mainClass.set("org.ghostcloak.backend.LocalServerKt") }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
