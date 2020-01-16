package dfxyz.portal

import dfxyz.mainwrapper.AbstractMainWrapper
import dfxyz.portal.logger.*
import dfxyz.portal.proxyrule.hostBlocked
import dfxyz.portal.proxyrule.updateRemoteRules
import dfxyz.portal.relay.relayConnectRequest
import dfxyz.portal.relay.relayNonConnectRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import java.io.File
import java.net.URI
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

private const val PK_LOG4J_CONFIG_PATH = "log4j.configurationFile"
private const val LOG4J_CONFIG_FILENAME = "portal.log4j2.xml"

private const val PROCESS_UUID_FILENAME = "portal.process.uuid"

private const val PROPERTIES_FILENAME = "portal.properties"

private const val PK_SERVER_HOST = "portal.server.host"
private const val PK_SERVER_PORT = "portal.server.port"
private const val PK_CLIENT_MAX_POOL_SIZE = "portal.client.maxPoolSize"

private const val PK_DIRECT_PROXY_ENABLE = "portal.directProxy.enable"

private const val PROXY_STATUS_FILENAME = "portal.status"
private const val PK_LAST_PROXY_MODE = "lastProxyMode"
private const val PK_REMOTE_RULE_UPDATE_TIME = "remoteRuleUpdateTime"

private const val PK_RELAYED_PROXY_ENABLE = "portal.relayedProxy.enable"
private const val PK_RELAYED_PROXY_AUTH = "portal.relayedProxy.auth"

const val PORTAL_HTTP_METHOD = "PORTAL"

private const val RAW_101_RESPONSE = "HTTP/1.1 101 OK\r\n\r\n"

enum class ProxyMode { DIRECT, RELAY, RULE }

val vertx: Vertx = run {
    System.setProperty(PK_LOG4J_CONFIG_PATH, LOG4J_CONFIG_FILENAME)
    return@run Vertx.vertx()
}

private lateinit var httpServer: HttpServer
lateinit var httpClient: HttpClient; private set

var directProxyEnabled = false; private set
private val proxyStatus = Properties()
var proxyMode = ProxyMode.RULE; private set

private var relayedProxyEnabled = false
private lateinit var relayedProxyAuth: String

fun main(args: Array<String>) = Main().invoke(args)

private class Main : AbstractMainWrapper() {
    override fun processUuidFilename(): String = PROCESS_UUID_FILENAME
    override fun mainFunction(args: Array<out String>?) = vertx.deployVerticle(PortalVerticle())
}

private class PortalVerticle : AbstractVerticle() {
    override fun start() = init(firstTime = true)
}

fun init(firstTime: Boolean = false) {
    if (!firstTime) {
        httpServer.close()
        httpClient.close()
    }
    try {
        val properties = File(PROPERTIES_FILENAME).inputStream().use {
            return@use Properties().apply { load(it) }
        }
        initHttpComponents(properties)
        initDirectProxy(properties)
        initRelayedProxy(properties)
    } catch (e: Exception) {
        error("failed to initialize portal", e)
        vertx.close()
    }
    info("portal initialized")
}

private fun initHttpComponents(properties: Properties) {
    val host = properties.getString(PK_SERVER_HOST)
    val port = properties.getInt(PK_SERVER_PORT)
    httpServer = vertx.createHttpServer(httpServerOptionsOf(host = host, port = port))
        .exceptionHandler { error("exception caught by http server", it) }
        .requestHandler(::handleRequest)
        .listen {
            if (it.succeeded()) {
                info("listening at $host:$port")
                return@listen
            }
            error("failed to listen at $host:$port", it.cause())
            vertx.close()
        }

    val clientOptions = httpClientOptionsOf()
    val clientPoolSize = properties.getProperty(PK_CLIENT_MAX_POOL_SIZE)?.toIntOrNull()
    if (clientPoolSize != null) {
        clientOptions.maxPoolSize = clientPoolSize
    }
    httpClient = vertx.createHttpClient(clientOptions)
}

private fun initDirectProxy(properties: Properties) {
    properties.getProperty(PK_DIRECT_PROXY_ENABLE)?.toBoolean()?.also { directProxyEnabled = it }
    if (!directProxyEnabled) {
        return
    }

    dfxyz.portal.proxyrule.init(properties)
    dfxyz.portal.relay.init(properties)

    try {
        File(PROXY_STATUS_FILENAME).inputStream().use { proxyStatus.load(it) }

        val previousMode = proxyStatus.getProperty(PK_LAST_PROXY_MODE)
        if (previousMode != null) {
            try {
                proxyMode = ProxyMode.valueOf(previousMode)
            } catch (e: Exception) {
                // ignore
            }
        }

        val remoteRuleUpdateTime = proxyStatus.getProperty(PK_REMOTE_RULE_UPDATE_TIME)?.toLongOrNull() ?: 0
        val diff = System.currentTimeMillis() - remoteRuleUpdateTime
        if (diff >= TimeUnit.DAYS.toMillis(1)) {
            updateRemoteRules()
        }
    } catch (e: Exception) {
        updateRemoteRules()
    }
}

private fun initRelayedProxy(properties: Properties) {
    properties.getProperty(PK_RELAYED_PROXY_ENABLE)?.toBoolean()?.also { relayedProxyEnabled = it }
    if (!relayedProxyEnabled) {
        return
    }

    val rawAuth = properties.getString(PK_RELAYED_PROXY_AUTH)
    relayedProxyAuth = if (rawAuth.isEmpty()) {
        rawAuth
    } else {
        Base64.getEncoder().encodeToString(rawAuth.toByteArray())
    }
}

private fun handleRequest(request: HttpServerRequest) {
    val uri = request.uri()
    when {
        uri.startsWith("*") -> {
            // ignore asterisk-form
            request.response().setStatus(HttpResponseStatus.BAD_REQUEST).endAndClose()
            deniedAccess(request)
        }
        uri.startsWith("/") -> when (request.method()) {
            HttpMethod.GET -> dfxyz.portal.webui.handleRequest(request)
            HttpMethod.OTHER -> {
                if (request.rawMethod() != PORTAL_HTTP_METHOD) {
                    request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
                    deniedAccess(request)
                    return
                }
                proxyRelayedRequest(request)
            }
            else -> {
                request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
                deniedAccess(request)
            }
        }
        else -> when (request.method()) {
            HttpMethod.CONNECT -> proxyConnectRequest(request, uri, asDirectProxy = true)
            else -> proxyNonConnectRequest(request, request.method(), uri, asDirectProxy = true)
        }
    }
}

private fun proxyDirectly(host: String): Boolean {
    return when (proxyMode) {
        ProxyMode.DIRECT -> true
        ProxyMode.RELAY -> false
        ProxyMode.RULE -> !hostBlocked(host)
    }
}

private fun proxyConnectRequest(request: HttpServerRequest, rawUri: String, asDirectProxy: Boolean) {
    val uri: URI? = try {
        URI("//$rawUri").let { if (it.port == -1) null else it }
    } catch (ignore: Exception) {
        null
    }
    if (uri == null) {
        val status = if (asDirectProxy) HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.METHOD_NOT_ALLOWED
        request.response().setStatus(status).endAndClose()
        deniedAccess(request)
        return
    }

    if (asDirectProxy && !proxyDirectly(uri.host)) {
        relayConnectRequest(request)
        relayedAccess(request)
        return
    }

    vertx.createNetClient().connect(uri.port, uri.host) { onProxiedRequestConnected(request, it, asDirectProxy) }

    if (asDirectProxy) {
        directAccess(request)
    } else {
        acceptedAccess(request)
    }
}

fun onProxiedRequestConnected(request: HttpServerRequest, ar: AsyncResult<NetSocket>, asDirectProxy: Boolean) {
    if (ar.failed()) {
        request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).endAndClose()
        failedAccess(request)
        error("failed to proxy request (${request.toLogString()})", ar.cause())
        return
    }

    val sourceSocket = request.netSocket()
    if (!asDirectProxy) sourceSocket.write(RAW_101_RESPONSE)
    val targetSocket = ar.result()

    sourceSocket.pipeTo(targetSocket)
    targetSocket.pipeTo(sourceSocket)
}

private fun proxyNonConnectRequest(
    request: HttpServerRequest, method: HttpMethod, rawUri: String, asDirectProxy: Boolean
) {
    val url: URL? = try {
        URL(rawUri).let { if (it.protocol != "http") null else it }
    } catch (ignore: Exception) {
        null
    }
    if (url == null) {
        val status = if (asDirectProxy) HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.METHOD_NOT_ALLOWED
        request.response().setStatus(status).endAndClose()
        deniedAccess(request)
        return
    }

    if (asDirectProxy && !proxyDirectly(url.host)) {
        relayNonConnectRequest(request)
        relayedAccess(request)
        return
    }

    @Suppress("DEPRECATION")
    val proxiedRequest = httpClient.requestAbs(method, rawUri)
        .exceptionHandler { onProxiedRequestException(request, it) }
        .handler { onProxiedRequestResponded(request, it) }
    proxiedRequest.copyHeaders(request)
    request.pipeTo(proxiedRequest)

    if (asDirectProxy) {
        directAccess(request)
    } else {
        acceptedAccess(request)
    }
}

fun onProxiedRequestException(request: HttpServerRequest, throwable: Throwable) {
    request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).endAndClose()
    failedAccess(request)
    error("failed to proxy request (${request.toLogString()})", throwable)
}

fun onProxiedRequestResponded(request: HttpServerRequest, response: HttpClientResponse) {
    request.response().statusCode = response.statusCode()
    for (header in response.headers()) {
        request.response().putHeader(header.key, header.value)
    }
    response.pipeTo(request.response())
}

private fun proxyRelayedRequest(request: HttpServerRequest) {
    if (!relayedProxyEnabled || request.rawMethod() != PORTAL_HTTP_METHOD) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        deniedAccess(request)
        return
    }

    val method = request.getRelayedMethod()
    if (method == null) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        deniedAccess(request)
        return
    }

    val uri = request.getRelayedUri()
    if (uri == null) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        deniedAccess(request)
        return
    }

    val auth = request.getPortalAuth()
    if (auth != relayedProxyAuth) {
        request.response().setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED).endAndClose()
        deniedAccess(request)
        return
    }

    when (method) {
        HttpMethod.CONNECT -> proxyConnectRequest(request, uri, asDirectProxy = false)
        else -> proxyNonConnectRequest(request, method, uri, asDirectProxy = false)
    }
}

private fun saveProxyStatus() {
    val buffer = Buffer.buffer().apply {
        for (property in proxyStatus) {
            appendString("${property.key}=${property.value}\n")
        }
    }
    vertx.fileSystem().writeFile(PROXY_STATUS_FILENAME, buffer) {
        if (it.failed()) {
            error("failed to save proxy status", it.cause())
        }
    }
}

fun changeProxyMode(mode: ProxyMode) {
    if (proxyMode != mode) {
        val modeName = mode.name
        info("proxy mode switched: $modeName")
        proxyMode = mode
        proxyStatus.setProperty(PK_LAST_PROXY_MODE, modeName)
        saveProxyStatus()
    }
}

fun updateRemoteRuleUpdateTime() {
    proxyStatus.setProperty(PK_REMOTE_RULE_UPDATE_TIME, System.currentTimeMillis().toString())
    saveProxyStatus()
}
