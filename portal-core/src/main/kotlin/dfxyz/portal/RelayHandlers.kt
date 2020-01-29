package dfxyz.portal

import dfxyz.portal.extensions.httpClientRequest.setHeaders
import dfxyz.portal.extensions.httpClientRequest.setPortalMethod
import dfxyz.portal.extensions.httpClientRequest.setPortalPassword
import dfxyz.portal.extensions.httpClientRequest.setPortalUri
import dfxyz.portal.extensions.httpServerRequest.toLogString
import dfxyz.portal.extensions.httpServerResponse.setStatus
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.net.netClientOptionsOf

enum class RelayHandlerType { PORTAL, PROXY }

interface RelayHandler {
    fun relayConnectRequest(request: HttpServerRequest, host: String, port: Int)
    fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, url: String)
}

class PortalRelayHandler(
    private val vertx: Vertx,
    private val config: PortalRelayHandlerConfig
) : RelayHandler {
    private val httpClient = vertx.createHttpClient(httpClientOptionsOf(maxPoolSize = config.clientPoolSize))

    override fun relayConnectRequest(request: HttpServerRequest, host: String, port: Int) {
        val dedicateHttpClient = vertx.createHttpClient()
        @Suppress("DEPRECATION")
        dedicateHttpClient.requestAbs(HttpMethod.OTHER, config.url)
            .exceptionHandler { handleProxiedRequestException(request, it) }
            .handler { handleRelayedConnectRequest(dedicateHttpClient, request, it) }
            .setRawMethod(PORTAL_HTTP_METHOD)
            .putHeader("Connection", "Upgrade")
            .putHeader("Upgrade", "Portal")
            .setPortalMethod(HttpMethod.CONNECT)
            .setPortalUri("$host:$port")
            .setPortalPassword(config.password)
            .end()
    }

    private fun handleRelayedConnectRequest(
        dedicateHttpClient: HttpClient,
        request: HttpServerRequest,
        response: HttpClientResponse
    ) {
        val statusCode = response.statusCode()
        if (statusCode != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            logWarn("$statusCode received when relaying connect request (${request.toLogString()})")
            logFailedAccess(request)
            request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).end()
            dedicateHttpClient.close()
            return
        }

        val srcSocket = kotlin.runCatching { request.netSocket() }.getOrNull()
        if (srcSocket == null) {
            logError("failed to call 'request.netSocket()' (${request.toLogString()})")
            logFailedAccess(request)
            dedicateHttpClient.close()
            return
        }
        val dstSocket = response.netSocket()

        val srcCompletedFuture = Future.future<Unit> { promise ->
            srcSocket.pipeTo(dstSocket) { promise.complete() }
        }
        val dstCompletedFuture = Future.future<Unit> { promise ->
            dstSocket.pipeTo(srcSocket) { promise.complete() }
        }
        CompositeFuture.join(srcCompletedFuture, dstCompletedFuture).setHandler {
            dedicateHttpClient.close()
        }
    }

    override fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, url: String) {
        @Suppress("DEPRECATION")
        httpClient.requestAbs(HttpMethod.OTHER, config.url)
            .exceptionHandler { handleProxiedRequestException(request, it) }
            .handler { handleProxiedNonConnectRequest(request, it) }
            .setRawMethod(PORTAL_HTTP_METHOD)
            .setHeaders(request, prependPortalHeaderPrefix = true)
            .setPortalMethod(method)
            .setPortalUri(url)
            .setPortalPassword(config.password)
            .also { request.pipeTo(it) }
    }
}

class ProxyRelayHandler(
    private val vertx: Vertx,
    private val config: ProxyRelayHandlerConfig
) : RelayHandler {
    private val httpClient = vertx.createHttpClient(
        httpClientOptionsOf(
            maxPoolSize = config.clientPoolSizePerEndpoint,
            proxyOptions = config.proxyOptions
        )
    )

    override fun relayConnectRequest(request: HttpServerRequest, host: String, port: Int) {
        val netClient = vertx.createNetClient(netClientOptionsOf(proxyOptions = config.proxyOptions))
        netClient.connect(port, host) {
            handleProxiedConnectRequest(netClient, request, it, asDirectProxy = true)
        }
    }

    override fun relayNonConnectRequest(request: HttpServerRequest, method: HttpMethod, url: String) {
        @Suppress("DEPRECATION")
        httpClient.requestAbs(method, url)
            .exceptionHandler { handleProxiedRequestException(request, it) }
            .handler { handleProxiedNonConnectRequest(request, it) }
            .setHeaders(request)
            .also { request.pipeTo(it) }
    }
}