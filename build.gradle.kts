import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.3.50"
}

group = "dfxyz"
version = "0.1.2-SNAPSHOT"

application {
    mainClassName = "dfxyz.portal.MainKt"
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.vertx:vertx-core:3.8.3")
    implementation("io.vertx:vertx-lang-kotlin:3.8.3")
    implementation("org.apache.logging.log4j:log4j-core:2.12.1")
    implementation("org.apache.logging.log4j:log4j-api:2.12.1")
    implementation("com.github.dfxyz:main-wrapper:0.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.getByName<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.readText().replace("cd \"\$SAVED\" >/dev/null\n", "").also {
            unixScript.writeText(it)
        }
        windowsScript.readText().replace(
            "set APP_HOME=%DIRNAME%..\r\n",
            "set APP_HOME=%DIRNAME%..\r\ncd /d %APP_HOME%\r\n"
        ).also {
            windowsScript.writeText(it)
        }
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
