package dfxyz.portal

import io.vertx.core.AbstractVerticle
import io.vertx.core.Launcher
import io.vertx.core.Vertx
import java.io.File
import java.util.*

private const val PK_PORTAL_HOME = "portal.home"

private val homeDirectory: File = run {
    val path = System.getProperty(PK_PORTAL_HOME) ?: throw RuntimeException("property '$PK_PORTAL_HOME' not set")
    val file = File(path)
    if (!file.isDirectory) {
        throw RuntimeException("property '$PK_PORTAL_HOME' is invalid")
    }
    return@run file
}

private const val PK_LOG4J_CONFIG_PATH = "log4j.configurationFile"
private const val LOG4J_CONFIG_FILENAME = "portal.log4j2.xml"

fun main(args: Array<String>) {
    System.setProperty(PK_LOG4J_CONFIG_PATH, getFile(LOG4J_CONFIG_FILENAME).absolutePath)
    when (args.getOrNull(0)) {
        "start" -> start()
        "stop" -> stop()
        "list" -> list()
        "run" -> run()
        else -> help()
    }
}

fun getFile(relativePath: String) = File(homeDirectory, relativePath)

private const val VERTX_ID_FILENAME = "portal.vertx.id"

private fun loadVertxId(createIfNotExists: Boolean): String? {
    val file = File(VERTX_ID_FILENAME)
    try {
        return file.readText()
    } catch (e: Exception) {
        // ignore
    }

    if (!createIfNotExists) {
        error("failed to load file '$VERTX_ID_FILENAME'")
        return null
    }

    val vertxId = "dfxyz.portal:${UUID.randomUUID().toString().substring(0 until 8)}"
    info("vertx-id generated: $vertxId")

    try {
        file.createNewFile()
        file.writeText(vertxId)
    } catch (e: Exception) {
        error("failed to save vertx-id")
        return null
    }
    return vertxId
}

private fun start() {
    val vertxId = loadVertxId(true) ?: return
    Launcher.main(arrayOf("start", PortalVerticle::class.java.name, "-id", vertxId))
}

private fun stop() {
    val vertxId = loadVertxId(false) ?: return
    Launcher.main(arrayOf("stop", vertxId))
}

private fun list() {
    Launcher.main(arrayOf("list"))
}

private fun run() {
    Vertx.vertx().deployVerticle(PortalVerticle())
}

private fun help() {
    println("Portal - available commands:")
    println("  * start")
    println("  * stop")
    println("  * list")
    println("  * run")
}

class PortalVerticle : AbstractVerticle() {
    override fun start() = init(vertx)
}
