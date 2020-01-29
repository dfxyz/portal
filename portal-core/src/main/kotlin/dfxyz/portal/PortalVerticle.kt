package dfxyz.portal

import dfxyz.portal.extensions.httpClientRequest.setHeaders
import dfxyz.portal.extensions.httpClientRequest.setHeadersWithPortalHeaderPrefix
import dfxyz.portal.extensions.httpServerRequest.getAuthorization
import dfxyz.portal.extensions.httpServerRequest.getPortalMethod
import dfxyz.portal.extensions.httpServerRequest.getPortalPassword
import dfxyz.portal.extensions.httpServerRequest.getPortalUri
import dfxyz.portal.extensions.httpServerRequest.getProxyAuthorization
import dfxyz.portal.extensions.httpServerRequest.toLogString
import dfxyz.portal.extensions.httpServerResponse.setHeaders
import dfxyz.portal.extensions.httpServerResponse.setStatus
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.Json
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.core.http.httpClientOptionsOf
import io.vertx.kotlin.core.http.httpServerOptionsOf
import java.net.URL

class PortalVerticle(private val config: PortalConfig) : AbstractVerticle() {
    private lateinit var httpClient: HttpClient
    private lateinit var relayHandler: RelayHandler

    override fun start() {
        httpClient = vertx.createHttpClient(httpClientOptionsOf(maxPoolSize = config.clientPoolSizePerEndpoint))
        vertx.createHttpServer(httpServerOptionsOf(host = config.host, port = config.port))
            .requestHandler(this::handleRequest)
            .exceptionHandler {
                logError("exception caught before connection established", it)
            }
            .listen {
                if (it.failed()) {
                    logFatal("failed to listen at ${config.host}:${config.port}", it.cause())
                    vertx.close()
                    return@listen
                }
            }
        if (config.directProxyConfig.enabled) {
            val type = config.directProxyConfig.relayHandlerType
            relayHandler = when (type) {
                RelayHandlerType.PORTAL -> PortalRelayHandler(vertx, config.directProxyConfig.portalRelayHandlerConfig)
                RelayHandlerType.PROXY -> ProxyRelayHandler(vertx, config.directProxyConfig.proxyRelayHandlerConfig)
            }
        }
    }

    private fun handleRequest(request: HttpServerRequest) {
        val uri = request.uri()
        when {
            uri.startsWith("*") -> {
                // ignore asterisk-form
                logDeniedAccess(request)
                request.response().setStatus(HttpResponseStatus.BAD_REQUEST).end()
            }
            uri.startsWith("/") -> {
                if (request.rawMethod() == PORTAL_HTTP_METHOD) {
                    handleRequestAsRelayProxy(request)
                } else {
                    handleRequestAsWebServer(request)
                }
            }
            else -> {
                handleRequestAsDirectProxy(request)
            }
        }
    }


    // Web server logic

    private fun handleRequestAsWebServer(request: HttpServerRequest) {
        if (!config.webConfig.enabled) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.NOT_FOUND)
            return
        }
        if (!checkAuthorization(request)) return

        val path = request.path()
        when (request.path()) {
            "/" -> webServerGetStaticFile(request, "/index.html")
            "/api/proxyRuleInfo" -> webServerGetProxyRuleInfo(request)
            "/api/testProxyRule" -> webServerTestProxyRule(request)
            "/api/proxyMode/direct" -> webServerSetProxyMode(request, ProxyMode.DIRECT)
            "/api/proxyMode/relay" -> webServerSetProxyMode(request, ProxyMode.RELAY)
            "/api/proxyMode/rule" -> webServerSetProxyMode(request, ProxyMode.RULE)
            "/api/updateLocalProxyRules" -> webServerUpdateLocalProxyRules(request)
            "/api/updateRemoteProxyRules" -> webServerUpdateRemoteProxyRules(request)
            "/api/reloadConfigurations" -> webServerReloadConfigurations(request)
            "/api/reloadStaticFiles" -> webServerReloadStaticFiles(request)
            else -> webServerGetStaticFile(request, path)
        }
    }

    private fun checkAuthorization(request: HttpServerRequest): Boolean {
        val authorization = config.webConfig.authorization
        if (authorization.isEmpty()) return true
        if (request.getAuthorization() != authorization) {
            logDeniedAccess(request)
            request.response()
                .setStatus(HttpResponseStatus.UNAUTHORIZED)
                .putHeader("WWW-Authenticate", "Basic")
                .end()
            return false
        }
        return true
    }

    private fun checkAllowedMethods(
        request: HttpServerRequest,
        allowedMethods: Collection<HttpMethod> = listOf(HttpMethod.GET)
    ): Boolean {
        if (request.method() !in allowedMethods) {
            logDeniedAccess(request)
            request.response()
                .setStatus(HttpResponseStatus.METHOD_NOT_ALLOWED)
                .putHeader("Allow", allowedMethods.map { it.name })
                .end()
            return false
        }
        return true
    }

    private fun webServerGetStaticFile(request: HttpServerRequest, path: String) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().request<StaticWebResource?>(MSG_ADDR_GET_STATIC_WEB_RESOURCE, path) {
            if (it.failed()) {
                logError("failed to get static web resource ($path)", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            val resource = it.result().body()
            if (resource == null) {
                logDeniedAccess(request)
                request.response().setStatus(HttpResponseStatus.NOT_FOUND).end()
                return@request
            }
            logAcceptedAccess(request)
            request.response()
                .putHeader("Content-Type", resource.contentType)
                .end(Buffer.buffer(resource.bytes))
        }
    }

    private fun webServerGetProxyRuleInfo(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().request<ProxyRuleInfo>(MSG_ADDR_GET_PROXY_RULE_INFO, null) {
            if (it.failed()) {
                logError("failed to get proxy rule info", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            kotlin.runCatching {
                Json.encode(it.result().body())
            }.onSuccess { json ->
                logAcceptedAccess(request)
                request.response()
                    .putHeader("Content-Type", "application/json")
                    .end(json)
            }.onFailure { throwable ->
                logError("failed to encode proxy rule info", throwable)
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
            }
        }
    }

    private fun webServerTestProxyRule(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        val host = request.query()
        if (host == null) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.BAD_REQUEST).end()
            return
        }

        vertx.eventBus().request<Boolean>(MSG_ADDR_TEST_PROXY_RULE, TestProxyRuleArg(host, ignoreProxyMode = true)) {
            if (it.failed()) {
                logError("failed to test proxy rule", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            logAcceptedAccess(request)
            request.response().end(it.result().body().toString())
        }
    }

    private fun webServerSetProxyMode(request: HttpServerRequest, mode: ProxyMode) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().request<Unit>(MSG_ADDR_SET_PROXY_MODE, mode) {
            if (it.failed()) {
                logError("failed to set proxy mode", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            logAcceptedAccess(request)
            request.response().end()
        }
    }

    private fun webServerUpdateLocalProxyRules(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().request<ProxyRuleNumberInfo>(MSG_ADDR_UPDATE_LOCAL_PROXY_RULES, null) {
            if (it.failed()) {
                logError("failed to update local proxy rules", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            kotlin.runCatching {
                Json.encode(it.result().body())
            }.onSuccess { json ->
                logAcceptedAccess(request)
                request.response()
                    .putHeader("Content-Type", "application/json")
                    .end(json)
            }.onFailure { throwable ->
                logError("failed to encode update proxy rule result", throwable)
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
            }
        }
    }

    private fun webServerUpdateRemoteProxyRules(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().request<ProxyRuleNumberInfo>(MSG_ADDR_UPDATE_REMOTE_PROXY_RULES, null) {
            if (it.failed()) {
                logError("failed to update remote proxy rules", it.cause())
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                return@request
            }

            kotlin.runCatching {
                Json.encode(it.result().body())
            }.onSuccess { json ->
                logAcceptedAccess(request)
                request.response()
                    .putHeader("Content-Type", "application/json")
                    .end(json)
            }.onFailure { throwable ->
                logError("failed to encode update proxy rule result", throwable)
                logFailedAccess(request)
                request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
            }
        }
    }

    private fun webServerReloadConfigurations(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().send(MSG_ADDR_REDEPLOY, null)

        logAcceptedAccess(request)
        request.response().end()
    }

    private fun webServerReloadStaticFiles(request: HttpServerRequest) {
        if (!checkAllowedMethods(request)) return

        vertx.eventBus().send(MSG_ADDR_RELOAD_STATIC_WEB_RESOURCE, null)

        logAcceptedAccess(request)
        request.response().end()
    }


    private fun getRequestMethodAndUrl(request: HttpServerRequest, asDirectProxy: Boolean): Pair<HttpMethod, URL>? {
        val method = if (asDirectProxy) request.method() else request.getPortalMethod() ?: return null
        val uri = if (asDirectProxy) request.uri() else request.getPortalUri() ?: return null
        if (method == HttpMethod.CONNECT) {
            val url = kotlin.runCatching { URL("https://$uri") }.getOrNull() ?: return null
            if (url.port == -1) return null
            return method to url
        } else {
            val url = kotlin.runCatching { URL(uri) }.getOrNull() ?: return null
            return method to url
        }
    }


    // Direct Proxy Logic

    private fun handleRequestAsDirectProxy(request: HttpServerRequest) {
        if (!config.directProxyConfig.enabled) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.FORBIDDEN).end()
            return
        }
        if (!checkProxyAuthorization(request)) return

        val pair = getRequestMethodAndUrl(request, asDirectProxy = true)
        if (pair == null) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.BAD_REQUEST).end()
            return
        }
        val (method, url) = pair

        if (method == HttpMethod.CONNECT) {
            proxyConnectRequest(request, url, asDirectProxy = true)
        } else {
            proxyNonConnectRequest(request, method, url, asDirectProxy = true)
        }
    }

    private fun checkProxyAuthorization(request: HttpServerRequest): Boolean {
        val authorization = config.directProxyConfig.authorization
        if (authorization.isEmpty()) return true
        if (request.getProxyAuthorization() != authorization) {
            logDeniedAccess(request)
            request.response()
                .setStatus(HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED)
                .putHeader("Proxy-Authenticate", "Basic")
                .end()
            return false
        }
        return true
    }


    // Relay Proxy Logic

    private fun handleRequestAsRelayProxy(request: HttpServerRequest) {
        if (!config.relayProxyConfig.enabled) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.FORBIDDEN).end()
            return
        }
        if (!checkPortalPassword(request)) return

        val pair = getRequestMethodAndUrl(request, asDirectProxy = false)
        if (pair == null) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.BAD_REQUEST).end()
            return
        }
        val (method, url) = pair

        if (method == HttpMethod.CONNECT) {
            proxyConnectRequest(request, url, asDirectProxy = false)
        } else {
            proxyNonConnectRequest(request, method, url, asDirectProxy = false)
        }
    }

    private fun checkPortalPassword(request: HttpServerRequest): Boolean {
        val password = config.relayProxyConfig.password
        if (password.isEmpty()) return true
        if (request.getPortalPassword() != password) {
            logDeniedAccess(request)
            request.response().setStatus(HttpResponseStatus.FORBIDDEN).end()
            return false
        }
        return true
    }

    // Common Proxy Logic

    private fun proxyConnectRequest(request: HttpServerRequest, url: URL, asDirectProxy: Boolean) {
        val host = url.host
        val port = url.port

        request.pause() // resume later

        if (asDirectProxy) {
            vertx.eventBus().request<Boolean>(MSG_ADDR_TEST_PROXY_RULE, TestProxyRuleArg(host)) { ar ->
                val useRelayHandler = if (ar.failed()) null else ar.result().body()
                if (useRelayHandler == null) {
                    logError("failed to test proxy rules", ar.cause())
                    logFailedAccess(request)
                    request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                    return@request
                }

                if (useRelayHandler) {
                    logRelayedAccess(request)
                    relayHandler.relayConnectRequest(request, host, port)
                    return@request
                }

                logDirectAccess(request)
                doProxyConnectRequest(request, host, port, asDirectProxy = true)
            }
            return
        }

        logAcceptedAccess(request)
        doProxyConnectRequest(request, host, port, asDirectProxy = false)
    }

    private fun doProxyConnectRequest(request: HttpServerRequest, host: String, port: Int, asDirectProxy: Boolean) {
        val netClient = vertx.createNetClient()
        netClient.connect(port, host) {
            handleProxiedConnectRequest(netClient, request, it, asDirectProxy)
        }
    }

    private fun proxyNonConnectRequest(
        request: HttpServerRequest,
        method: HttpMethod,
        url: URL,
        asDirectProxy: Boolean
    ) {
        val urlString = url.toString()

        if (asDirectProxy) {
            request.pause() // resume later
            vertx.eventBus().request<Boolean>(MSG_ADDR_TEST_PROXY_RULE, TestProxyRuleArg(url.host)) { ar ->
                val useRelayHandler = if (ar.failed()) null else ar.result().body()
                if (useRelayHandler == null) {
                    logError("failed to test proxy rules", ar.cause())
                    logFailedAccess(request)
                    request.response().setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR).end()
                    return@request
                }

                if (useRelayHandler) {
                    logRelayedAccess(request)
                    relayHandler.relayNonConnectRequest(request, method, urlString)
                    return@request
                }

                logDirectAccess(request)
                doProxyNonConnectRequest(request, method, urlString, asDirectProxy = true)
            }
            return
        }

        logAcceptedAccess(request)
        doProxyNonConnectRequest(request, method, urlString, asDirectProxy = false)
    }

    private fun doProxyNonConnectRequest(
        request: HttpServerRequest,
        method: HttpMethod,
        url: String,
        asDirectProxy: Boolean
    ) {
        @Suppress("DEPRECATION")
        httpClient.requestAbs(method, url)
            .exceptionHandler { handleProxiedRequestException(request, it) }
            .handler { handleProxiedNonConnectRequest(request, it) }
            .also {
                if (asDirectProxy) {
                    it.setHeaders(request)
                } else {
                    it.setHeadersWithPortalHeaderPrefix(request)
                }
                request.pipeTo(it)
            }
    }
}

fun handleProxiedConnectRequest(
    netClient: NetClient,
    request: HttpServerRequest,
    ar: AsyncResult<NetSocket>,
    asDirectProxy: Boolean
) {
    if (ar.failed()) {
        logError("failed to connect (${request.toLogString()})", ar.cause())
        logFailedAccess(request)
        request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).end()
        netClient.close()
        return
    }

    val srcSocket = runCatching { request.netSocket() }.getOrNull()
    if (srcSocket == null) {
        logError("failed to call 'request.netSocket()' (${request.toLogString()})")
        logFailedAccess(request)
        netClient.close()
        return
    }
    val dstSocket = ar.result()
    if (!asDirectProxy) { // trick reverse-proxy to create a transparent tunnel
        srcSocket.write("HTTP/1.1 101 Switching Protocol\r\n\r\n")
    }

    val srcDepletedFuture = Future.future<Unit> { promise ->
        srcSocket.pipeTo(dstSocket) { promise.complete() }
    }
    val dstDepletedFuture = Future.future<Unit> { promise ->
        dstSocket.pipeTo(srcSocket) { promise.complete() }
    }
    CompositeFuture.join(srcDepletedFuture, dstDepletedFuture).setHandler {
        netClient.close()
    }
}

fun handleProxiedRequestException(request: HttpServerRequest, throwable: Throwable) {
    logError("proxied write stream exception caught (${request.toLogString()})", throwable)
    logFailedAccess(request)
    request.response().setStatus(HttpResponseStatus.BAD_GATEWAY).end()
}

fun handleProxiedNonConnectRequest(request: HttpServerRequest, response: HttpClientResponse) {
    request.response()
        .setStatusCode(response.statusCode())
        .setHeaders(response)
        .also { response.pipeTo(it) }
}