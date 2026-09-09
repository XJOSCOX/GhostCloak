plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies { implementation(project(":protocol")); implementation(libs.cbor) }
application { mainClass.set("org.ghostcloak.backend.LocalServerKt") }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
