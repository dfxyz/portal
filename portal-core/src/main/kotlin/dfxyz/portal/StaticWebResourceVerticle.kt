package dfxyz.portal

import io.vertx.core.AbstractVerticle
import io.vertx.core.eventbus.Message

private const val RESOURCE_ROOT_PATH = "dfxyz/portal/web"

class StaticWebResourceVerticle : AbstractVerticle() {
    private val classLoader = this.javaClass.classLoader
    private val resources = hashMapOf<String, StaticWebResource>()

    override fun start() {
        vertx.eventBus().consumer(MSG_ADDR_GET_STATIC_WEB_RESOURCE, this::handleGetResourceRequest)
        vertx.eventBus().consumer<Unit>(MSG_ADDR_RELOAD_STATIC_WEB_RESOURCE) {
            resources.clear()
            loadResources()
        }
        loadResources()
    }

    private fun loadResources() {
        val fileList = arrayListOf<String>()
        classLoader.getResourceAsStream("$RESOURCE_ROOT_PATH/.file_list")?.bufferedReader()?.use {
            fileList.addAll(it.readLines())
        }
        if (fileList.isEmpty()) {
            logWarn("no static web resource to load")
            return
        }
        for (path in fileList) {
            val bytes = classLoader.getResourceAsStream("$RESOURCE_ROOT_PATH$path")?.readAllBytes()
            if (bytes == null) {
                logError("failed to load static web resource '$path'")
                continue
            }
            val contentType = when {
                path.endsWith(".html") -> "text/html"
                path.endsWith(".js") -> "text/javascript"
                else -> "application/octet-stream"
            }
            resources[path] = StaticWebResource(contentType, bytes)
        }
    }

    private fun handleGetResourceRequest(message: Message<String>) {
        message.reply(resources[message.body()])
    }
}

class StaticWebResource(
    val contentType: String,
    val bytes: ByteArray
)