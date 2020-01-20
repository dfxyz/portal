import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = "0.1.2-SNAPSHOT"

plugins {
    kotlin("jvm") version "1.3.61"
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.vertx:vertx-core:3.8.4")
    implementation("io.vertx:vertx-lang-kotlin:3.8.4")
    implementation("org.apache.logging.log4j:log4j-core:2.12.1")
    implementation("org.apache.logging.log4j:log4j-api:2.12.1")
    implementation("com.github.dfxyz:main-wrapper:0.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
