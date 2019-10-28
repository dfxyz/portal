import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.3.50"
}

group = "dfxyz"
version = "0.1"

application {
    mainClassName = "dfxyz.portal.PortalKt"
}

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
