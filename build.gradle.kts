import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
}

group = "dfxyz"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.vertx:vertx-core:3.8.3")
    implementation("io.vertx:vertx-lang-kotlin:3.8.3")
    implementation("org.apache.logging.log4j:log4j-core:2.12.1")
    implementation("org.apache.logging.log4j:log4j-api:2.12.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val launcherClass = "io.vertx.core.Launcher"
val verticleClass = "dfxyz.portal.Portal"
val verticleId = "$group:${project.name}"

tasks.register<JavaExec>("start") {
    group = "application"
    workingDir = projectDir
    classpath = sourceSets["main"].runtimeClasspath
    main = launcherClass
    args = listOf("start", verticleClass, "-id", verticleId)
}

tasks.register<JavaExec>("stop") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    main = launcherClass
    args = listOf("stop", verticleId)
}

tasks.register<JavaExec>("status") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    main = launcherClass
    args = listOf("list")
}
