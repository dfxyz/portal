package dfxyz.portal

import com.fasterxml.jackson.databind.MapperFeature
import dfxyz.mainwrapper.AbstractMainWrapper
import dfxyz.portal.extensions.promise.handle
import io.vertx.core.AbstractVerticle
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.kotlin.core.deploymentOptionsOf

object Main : AbstractMainWrapper() {
    @JvmStatic
    fun main(args: Array<String>) {
        this.invoke(args)
    }

    override fun processUuidFilename(): String {
        return "portal.process_uuid"
    }

    /** Initialize loggers, global databind object mapper, message codecs and deploy main verticle */
    override fun mainFunction(args: Array<out String>) {
        initLogger()
        DatabindCodec.mapper().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        Vertx.vertx().apply {
            registerPortalMessageCodecs(this)
            deployVerticle(MainVerticle(), deploymentOptionsOf(worker = true))
        }
    }
}

private class MainVerticle : AbstractVerticle() {
    private var redeployable = false
    private val deploymentIds = hashSetOf<String>()

    override fun start() {
        vertx.eventBus().consumer<Unit>(MSG_ADDR_REDEPLOY) { this.handleRedeployRequest() }
        deploy()
    }

    private fun deploy() {
        val config = loadConfig()
        if (config == null) {
            vertx.close()
            return
        }

        // deploy auxiliary verticles firstly
        val futures = arrayListOf<Future<*>>()
        if (config.webConfig.enabled) {
            futures.add(deployStaticWebResourceVerticle())
        }
        if (config.directProxyConfig.enabled) {
            futures.add(deployProxyRuleVerticle(config))
        }

        // then deploy portal verticles
        CompositeFuture.all(futures).setHandler {
            if (it.failed()) {
                logFatal("failed to deploy auxiliary verticles", it.cause())
                vertx.close()
                return@setHandler
            }
            deployPortalVerticle(config)
        }
    }

    private fun deployStaticWebResourceVerticle() = Future.future<Unit> { promise ->
        vertx.deployVerticle(StaticWebResourceVerticle(), deploymentOptionsOf(worker = true)) {
            if (it.succeeded()) deploymentIds.add(it.result())
            promise.handle(it)
        }
    }

    private fun deployProxyRuleVerticle(config: PortalConfig) = Future.future<Unit> { promise ->
        vertx.deployVerticle(ProxyRuleVerticle(config.directProxyConfig), deploymentOptionsOf(worker = true)) {
            if (it.succeeded()) deploymentIds.add(it.result())
            promise.handle(it)
        }
    }

    private fun deployPortalVerticle(config: PortalConfig) {
        vertx.deployVerticle({ PortalVerticle(config) }, deploymentOptionsOf(instances = config.instanceNumber)) {
            if (it.failed()) {
                logFatal("failed to deploy portal verticle", it.cause())
                vertx.close()
                return@deployVerticle
            }

            logInfo("portal deployed at ${config.host}:${config.port} / ${config.instanceNumber} instance(s)")
            deploymentIds.add(it.result())
            redeployable = true
        }
    }

    private fun handleRedeployRequest() {
        if (!redeployable) return

        redeployable = false

        val futures = arrayListOf<Future<*>>()
        deploymentIds.forEach { id ->
            Future.future<Unit> { promise ->
                vertx.undeploy(id) { ar -> promise.handle(ar) }
            }.also {
                futures.add(it)
            }
        }
        CompositeFuture.all(futures).setHandler {
            if (it.failed()) {
                logFatal("failed to undeploy verticles", it.cause())
                vertx.close()
                return@setHandler
            }
            deploymentIds.clear()
            deploy()
        }
    }
}
