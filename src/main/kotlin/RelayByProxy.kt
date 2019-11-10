package dfxyz.portal.relay

import dfxyz.portal.*
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.ProxyType
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.kotlin.core.net.proxyOptionsOf
import java.net.URI
import java.util.*

private const val PK_RELAY_PROXY_PROTOCOL = "portal.directProxy.relay.proxy.protocol"
private const val PK_RELAY_PROXY_HOST = "portal.directProxy.relay.proxy.host"
private const val PK_RELAY_PROXY_PORT = "portal.directProxy.relay.proxy.port"
private const val PK_RELAY_PROXY_USERNAME = "portal.directProxy.relay.proxy.username"
private const val PK_RELAY_PROXY_PASSWORD = "portal.directProxy.relay.proxy.password"
private const val PK_RELAY_PROXY_CLIENT_MAX_POOL_SIZE = "portal.directProxy.relay.proxy.client.maxPoolSize"

class RelayByProxy(properties: Properties) : RelayHandler {
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

    override fun relayConnectRequest(request: HttpServerRequest) {
        val parsedUri = URI("//${request.uri()}") // checked before
        vertx.createNetClient(netClientOptionsOf(proxyOptions = proxyOptions))
            .connect(parsedUri.port, parsedUri.host) { onProxiedRequestConnected(request, it, asDirectProxy = true) }
    }

    override fun relayNonConnectRequest(request: HttpServerRequest) {
        @Suppress("DEPRECATION")
        val relayedRequest = client.requestAbs(request.method(), request.uri())
            .exceptionHandler { onProxiedRequestException(request, it) }
            .handler { onProxiedRequestResponded(request, it) }
        relayedRequest.copyHeaders(request)
        request.pipeTo(relayedRequest)
    }
}
