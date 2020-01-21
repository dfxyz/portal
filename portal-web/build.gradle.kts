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

val processedResourceDir = "$buildDir/resources"

val resourceTask = tasks.replace("processResources", Sync::class.java).apply {
    dependsOn(compileTask)
    for (file in configurations.compileClasspath.get()) {
        from(zipTree(file)) { include { it.name == "kotlin.js" } }
    }
    from(compileTask.outputFile)
    from(kotlin.sourceSets["main"].resources)
    into("$processedResourceDir/dfxyz/portal/web")
}

tasks.register<Jar>("jar") {
    group = "build"
    dependsOn(resourceTask)
    from(processedResourceDir)
}

listOf("JsJar", "JsSourcesJar", "kotlinSourcesJar").forEach { name ->
    tasks[name].also {
        it.onlyIf { false }
        tasks.remove(it)
    }
}
