plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies { testImplementation(project(":crypto")); testImplementation(project(":messaging")); testImplementation(project(":transport")); testImplementation(project(":backend")); testImplementation(libs.junit); testImplementation(libs.signal.client); testImplementation("org.postgresql:postgresql:42.7.11") }
tasks.test {
    exclude("**/PostgresTest*")
    exclude("**/StagingTest*")
    systemProperty("ghostcloak.root", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
tasks.register<Test>("postgresTest") {
    testClassesDirs=sourceSets.test.get().output.classesDirs
    classpath=sourceSets.test.get().runtimeClasspath
    include("**/PostgresTest*")
    doFirst { require(System.getenv("GHOSTCLOAK_TEST_DATABASE_URL")?.endsWith("/ghostcloak_test")==true) { "Explicit isolated ghostcloak_test database required" } }
    testLogging { events("passed","failed") }
    outputs.upToDateWhen {false}
}
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.register<Test>("stagingTest") {
    testClassesDirs=sourceSets.test.get().output.classesDirs
    classpath=sourceSets.test.get().runtimeClasspath
    include("**/StagingTest*")
    doFirst { require(System.getenv("GHOSTCLOAK_STAGING_CONFIRM")=="isolated-staging") {"Explicit isolated staging confirmation required"} }
    testLogging {events("passed","failed")}
    outputs.upToDateWhen {false}
}
