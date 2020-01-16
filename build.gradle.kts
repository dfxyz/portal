@Suppress("PropertyName")
val MAIN_CLASS_NAME = "dfxyz.portal.PortalKt"

val coreRuntimeClasspath by lazy {
    project(":portal-core")
        .convention.getPlugin<JavaPluginConvention>()
        .sourceSets[SourceSet.MAIN_SOURCE_SET_NAME]
        .runtimeClasspath
}
val coreCompileTask = tasks.getByPath(":portal-core:mainClasses")
val coreJarTask = tasks.getByPath(":portal-core:jar") as Jar
val webJarTask = tasks.getByPath(":portal-web:jar") as Jar

inline fun <reified T : Task> registerTask(name: String, noinline block: T.() -> Unit): TaskProvider<T> {
    return tasks.register(name, T::class.java) {
        group = "application"
        block()
    }
}

registerTask<JavaExec>("run") {
    dependsOn(coreCompileTask, webJarTask)
    main = MAIN_CLASS_NAME
    args = listOf("run")
    classpath = files(
        coreRuntimeClasspath,
        webJarTask.archiveFile
    )
}

val collectDependenciesTask = registerTask<Sync>("collectDependencies") {
    dependsOn(coreJarTask, webJarTask)
    from(coreRuntimeClasspath) { exclude { it.isDirectory } }
    from(coreJarTask.archiveFile)
    from(webJarTask.archiveFile)
    into("lib")
}

val createStartScriptsTask = registerTask<CreateStartScripts>("createStartScripts") {
    applicationName = project.name
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
