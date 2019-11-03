package dfxyz.portal

import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.ProxyType
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.core.net.proxyOptionsOf
import java.net.URI
import java.net.URL
import java.util.*

interface RelayHandler {
    fun relayConnectRequest(request: HttpServerRequest, uri: String)
    fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, uri: String)
}

private const val PK_RELAY_TYPE = "portal.directProxy.relayType"

fun createRelayHandler(properties: Properties): RelayHandler {
    return when (properties.getString(PK_RELAY_TYPE)) {
        "portal" -> RelayByPortal(properties)
        "proxy" -> RelayByProxy(properties)
        else -> throw RuntimeException("unknown relay type '${properties.getString(PK_RELAY_TYPE)}'")
    }
}

private const val PK_RELAY_PORTAL_URL = "portal.directProxy.relay.portal.url"
private const val PK_RELAY_PORTAL_AUTH = "portal.directProxy.relay.portal.auth"
private const val PK_RELAY_PORTAL_CLIENT_MAX_POOL_SIZE = "portal.directProxy.relay.portal.client.maxPoolSize"

private class RelayByPortal(properties: Properties) : RelayHandler {
    private val client: HttpClient
    private val url: String
    private val auth: String

    init {
        val clientOptions = httpClientOptionsOf()
        val clientPoolSize = properties.getProperty(PK_RELAY_PORTAL_CLIENT_MAX_POOL_SIZE)?.toIntOrNull()
        if (clientPoolSize != null) {
            clientOptions.maxPoolSize = clientPoolSize
        }
        client = vertx.createHttpClient(clientOptions)

        url = properties.getString(PK_RELAY_PORTAL_URL)
        URL(url) // check if the url is valid

        val rawAuth = properties.getProperty(PK_RELAY_PORTAL_AUTH)
        auth = if (rawAuth.isEmpty()) {
            rawAuth
        } else {
            Base64.getEncoder().encodeToString(rawAuth.toByteArray())
        }
    }

    private fun setupHeaders(relayedRequest: HttpClientRequest, request: HttpServerRequest) {
        relayedRequest.copyHeaders(request)
        relayedRequest
            .putHeader("upgrade", "portal")
            .putHeader("connection", "upgrade")
            .setRelayedMethod(request.rawMethod())
            .setRelayedUri(request.uri())
            .setPortalAuth(auth)
    }

    override fun relayConnectRequest(request: HttpServerRequest, uri: String) {
        @Suppress("DEPRECATION")
        val relayedRequest = vertx.createHttpClient().requestAbs(HttpMethod.OTHER, url)
            .setRawMethod(PORTAL_RELAYED_REQUEST_METHOD)
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { relayedResponse ->
                val statusCode = relayedResponse.statusCode()
                if (statusCode != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                    request.response().setStatusCode(statusCode).endAndClose()
                    failedAccess(request)
                    return@handler
                }

                val sourceSocket = request.netSocket()
                val targetSocket = relayedResponse.netSocket()

                sourceSocket.pipeTo(targetSocket)
                targetSocket.pipeTo(sourceSocket)
            }
        setupHeaders(relayedRequest, request)
        relayedRequest.sendHead()
    }

    override fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, uri: String) {
        @Suppress("DEPRECATION")
        val relayedRequest = client.requestAbs(method, url)
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { onProxiedRequestResponded(request, it) }
        setupHeaders(relayedRequest, request)
        request.pipeTo(relayedRequest)
    }
}

private const val PK_RELAY_PROXY_PROTOCOL = "portal.directProxy.relay.proxy.protocol"
private const val PK_RELAY_PROXY_HOST = "portal.directProxy.relay.proxy.host"
private const val PK_RELAY_PROXY_PORT = "portal.directProxy.relay.proxy.port"
private const val PK_RELAY_PROXY_USERNAME = "portal.directProxy.relay.proxy.username"
private const val PK_RELAY_PROXY_PASSWORD = "portal.directProxy.relay.proxy.password"
private const val PK_RELAY_PROXY_CLIENT_MAX_POOL_SIZE = "portal.directProxy.relay.proxy.client.maxPoolSize"

private class RelayByProxy(properties: Properties) : RelayHandler {
    private val client: HttpClient
    private val proxyOptions: ProxyOptions

    init {
        val proxyType = ProxyType.valueOf(properties.getString(PK_RELAY_PROXY_PROTOCOL).toUpperCase())
        val host = properties.getString(PK_RELAY_PROXY_HOST)
        val port = properties.getInt(PK_RELAY_PROXY_PORT)
        val username = properties.getProperty(PK_RELAY_PROXY_USERNAME) ?: ""
        val password = properties.getProperty(PK_RELAY_PROXY_PASSWORD) ?: ""
        val clientPoolSize = properties.getProperty(PK_RELAY_PROXY_CLIENT_MAX_POOL_SIZE)?.toIntOrNull()

        proxyOptions = proxyOptionsOf(type = proxyType, host = host, port = port)
        if (username.isNotEmpty() && password.isNotEmpty()) {
            proxyOptions.username = username
            proxyOptions.password = password
        }

        val clientOptions = httpClientOptionsOf(proxyOptions = proxyOptions)
        if (clientPoolSize != null) {
            clientOptions.maxPoolSize = clientPoolSize
        }
        client = vertx.createHttpClient(clientOptions)
    }

    override fun relayConnectRequest(request: HttpServerRequest, uri: String) {
        val parsedUri = URI("//$uri") // checked before
        vertx.createNetClient(netClientOptionsOf(proxyOptions = proxyOptions))
            .connect(parsedUri.port, parsedUri.host) { onProxiedRequestConnected(request, it, asDirectProxy = true) }
    }

    override fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, uri: String) {
        @Suppress("DEPRECATION")
        val relayedRequest = client.requestAbs(request.method(), request.uri())
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { onProxiedRequestResponded(request, it) }
        relayedRequest.copyHeaders(request)
        request.pipeTo(relayedRequest)
    }
}
