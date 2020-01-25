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

val processedResourceRootPath = "$buildDir/resources"
val processedResourceContentPath = "$processedResourceRootPath/dfxyz/portal/web"
val resourceTask = tasks.replace("processResources", Sync::class.java).apply {
    dependsOn(compileTask)
    for (file in configurations.compileClasspath.get()) {
        from(zipTree(file)) { include { it.name == "kotlin.js" } }
    }
    from(compileTask.outputFile)
    from(kotlin.sourceSets["main"].resources)
    into(processedResourceContentPath)
    doLast {
        File("$processedResourceContentPath/.file_list").bufferedWriter().use {
            recordResources(it, File(processedResourceContentPath), firstCall = true)
        }
    }
}

fun recordResources(writer: java.io.BufferedWriter, file: File, prefix: String = "", firstCall: Boolean = false) {
    val filename = file.name
    if (filename.startsWith(".")) return

    val path = prefix + filename
    if (file.isFile) {
        writer.appendln(path)
    } else if (file.isDirectory) {
        val newPrefix = if (firstCall) "" else "$path/"
        file.listFiles()?.forEach {
            recordResources(writer, it, newPrefix)
        }
    }
}

tasks.register<Jar>("jar") {
    group = "build"
    dependsOn(resourceTask)
    from(processedResourceRootPath)
}

listOf("JsJar", "JsSourcesJar", "kotlinSourcesJar").forEach { name ->
    tasks[name].also {
        it.onlyIf { false }
        tasks.remove(it)
    }
}
