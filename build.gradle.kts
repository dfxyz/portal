import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.3.50"
}

group = "dfxyz"
version = "0.1.1"

application {
    mainClassName = "dfxyz.portal.MainKt"
    applicationDefaultJvmArgs = listOf("-Dportal.home=PORTAL_HOME")
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

tasks.getByName<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(unixScript.readText().replace("PORTAL_HOME", "\$APP_HOME"))
        windowsScript.writeText(windowsScript.readText().replace("PORTAL_HOME", "%APP_HOME%"))
    }
}

tasks.getByName<Sync>("installDist").apply {
    doLast {
        for (dir in listOf("bin", "lib")) {
            sync {
                from("$destinationDir/$dir")
                into("$projectDir/$dir")
            }
        }
        for (dir in listOf("install", "libs", "scripts", "tmp")) {
            delete("$buildDir/$dir")
        }
    }
}
