import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

version = "0.1-SNAPSHOT"

plugins {
    kotlin("js") version "1.3.61"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-js"))
}

val compileTask = tasks.getByName<Kotlin2JsCompile>("compileKotlinJs").apply {
    kotlinOptions.metaInfo = false
    kotlinOptions.sourceMap = false
    kotlinOptions.outputFile = "${destinationDir}/portal.js"
}

val resourceTask = tasks.getByName<ProcessResources>("processResources")

tasks.register<Jar>("jar") {
    dependsOn(compileTask, resourceTask)
    val packagePath = "dfxyz/portal/web"
    for (file in configurations.compileClasspath.get()) {
        from(zipTree(file)) {
            include { it.name == "kotlin.js" }
            into(packagePath)
        }
    }
    from(compileTask.outputFile) { into(packagePath) }
    from(resourceTask.source) { into(packagePath) }
}
