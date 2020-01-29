@Suppress("PropertyName")
val MAIN_CLASS_NAME = "dfxyz.portal.Main"

val coreRuntimeClasspath by lazy {
    project(":portal-core")
        .convention.getPlugin<JavaPluginConvention>()
        .sourceSets[SourceSet.MAIN_SOURCE_SET_NAME]
        .runtimeClasspath
}
val coreJarTask = tasks.getByPath(":portal-core:jar") as Jar

val webRuntimePath = "${project(":portal-web").buildDir}/resources"
val webJarTask = tasks.getByPath(":portal-web:jar") as Jar

inline fun <reified T : Task> registerTask(name: String, noinline block: T.() -> Unit): TaskProvider<T> {
    return tasks.register(name, T::class.java) {
        group = "application"
        block()
    }
}

registerTask<JavaExec>("run") {
    dependsOn(":portal-core:mainClasses", ":portal-web:mainClasses")
    main = MAIN_CLASS_NAME
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
    args = listOf("run")
    classpath = files(coreRuntimeClasspath, webRuntimePath)
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
    defaultJvmOpts = listOf("-Dfile.encoding=UTF-8")
    classpath = collectDependenciesTask.get().source
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
