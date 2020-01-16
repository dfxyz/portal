@file:Suppress("PropertyName")

val APPLICATION_NAME = project.name
val MAIN_CLASS_NAME = "dfxyz.portal.PortalKt"

val coreRuntimeClasspath by lazy {
    project(":portal-core")
        .convention.getPlugin<JavaPluginConvention>()
        .sourceSets[SourceSet.MAIN_SOURCE_SET_NAME]
        .runtimeClasspath
}
val coreRuntimeDependencies by lazy {
    project(":portal-core").configurations["runtimeClasspath"] as FileCollection
}
val coreCompileTask = tasks.getByPath(":portal-core:mainClasses")
val coreJarTask = tasks.getByPath(":portal-core:jar") as Jar

val webRuntimeDependencies by lazy {
    project(":portal-web").configurations["runtimeClasspath"] as FileCollection
}
val webJarTask = tasks.getByPath(":portal-web:JsJar") as Jar

inline fun <reified T : Task> registerTask(name: String, noinline block: T.() -> Unit): TaskProvider<T> {
    return tasks.register(name, T::class.java) {
        group = APPLICATION_NAME
        block()
    }
}

registerTask<JavaExec>("run") {
    dependsOn(coreCompileTask, webJarTask)
    classpath = files(
        coreRuntimeClasspath,
        webRuntimeDependencies,
        webJarTask.archiveFile
    )
    main = MAIN_CLASS_NAME
    args = listOf("run")
}

val collectDependenciesTask = registerTask<Sync>("collectDependencies") {
    dependsOn(coreJarTask, webJarTask)
    from(coreRuntimeDependencies)
    from(coreJarTask.archiveFile)
    from(webRuntimeDependencies)
    from(webJarTask.archiveFile)
    into("lib")
}

val createStartScriptsTask = registerTask<CreateStartScripts>("createStartScripts") {
    applicationName = APPLICATION_NAME
    mainClassName = MAIN_CLASS_NAME
    classpath = files(file("lib").listFiles() ?: emptyArray<File>())
    outputDir = file("bin")
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

registerTask<Task>("install") {
    dependsOn(collectDependenciesTask, createStartScriptsTask)
}

registerTask<Delete>("uninstall") {
    delete("bin", "lib")
}
